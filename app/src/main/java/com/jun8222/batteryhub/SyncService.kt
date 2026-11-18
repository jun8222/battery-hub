package com.jun8222.batteryhub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 같은 와이파이의 다른 기기들과 배터리 정보를 주고받는 상시 실행 서비스.
 * UDP 브로드캐스트(포트 48821)로 1분마다, 그리고 잔량·충전 상태가 바뀔 때마다 보냄.
 */
class SyncService : Service() {

    companion object {
        const val PORT = 48821
        private const val CHANNEL = "status"
        private const val NOTI_ID = 1
        private const val INTERVAL_SEC = 60L

        fun start(ctx: Context) {
            BatteryStore.setRunning(ctx, true)
            ctx.startForegroundService(Intent(ctx, SyncService::class.java))
        }

        fun stop(ctx: Context) {
            BatteryStore.setRunning(ctx, false)
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }

    private var socket: DatagramSocket? = null
    private var listener: Thread? = null
    private var sender: ScheduledExecutorService? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile private var lastSent: Pair<Int, Boolean>? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val now = BatteryReader.own(c)
            if (now != lastSent) sendSoon()
        }
    }

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33)
                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            dev ?: return
            when (i.action) {
                BatteryReader.ACTION_BT_BATTERY -> {
                    val lvl = i.getIntExtra(BatteryReader.EXTRA_BT_LEVEL, -1)
                    if (lvl in 0..100) {
                        val name = try { dev.name } catch (e: SecurityException) { null } ?: "블루투스 기기"
                        BatteryReader.btCache[dev.address] = Accessory(name, lvl)
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> BatteryReader.btCache.remove(dev.address)
            }
            sendSoon()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground()

        multicastLock = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createMulticastLock("batteryhub").apply { setReferenceCounted(false); acquire() }

        registerSystem(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerSystem(btReceiver, IntentFilter().apply {
            addAction(BatteryReader.ACTION_BT_BATTERY)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
        })

        startListening()
        sender = Executors.newSingleThreadScheduledExecutor().also {
            it.execute { send(hello = true) }
            it.scheduleWithFixedDelay({ send(hello = false) }, INTERVAL_SEC, INTERVAL_SEC, TimeUnit.SECONDS)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
        sender?.shutdownNow()
        socket?.close()
        listener?.interrupt()
        multicastLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun registerSystem(r: BroadcastReceiver, f: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, Context.RECEIVER_EXPORTED)
        else registerReceiver(r, f)
    }

    private fun goForeground() {
        val n = buildStatus()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTI_ID, n)
        }
    }

    /** 상시 알림 = 잠금화면에서 보는 배터리 현황 (위젯에 표시하는 기기 + 부족한 기기) */
    private fun buildStatus(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel("sync") // 예전 채널(최소 중요도: 잠금화면에 안 보임) 정리
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "배터리 현황 (잠금화면 표시)", NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val list = BatteryStore.forWidget(this)
        fun fmt(e: Entry): String {
            val stale = now - e.ts > BatteryWidget.STALE_MS
            val low = BatteryStore.isLowAlert(this, e)
            return buildString {
                if (low) append("⚠") else if (e.isAccessory) append("🎧")
                append(e.name).append(' ')
                if (e.charging && !stale) append("⚡")
                append(if (e.level >= 0) "${e.level}%" else "-")
                if (stale) append(" (").append(BatteryWidget.ago(now - e.ts)).append(")")
            }
        }
        val devices = list.filter { !it.isAccessory || BatteryStore.isLowAlert(this, it) }
        val title = if (devices.isEmpty()) "배터리 허브" else devices.take(3).joinToString(" · ") { fmt(it) }
        val text = if (list.isEmpty()) "같은 와이파이의 기기를 찾는 중" else list.joinToString(" · ") { fmt(it) }
        val style = Notification.InboxStyle().setBigContentTitle("🔋 배터리 현황")
        list.forEach { style.addLine(fmt(it)) }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
    }

    private fun updateStatus() {
        try { getSystemService(NotificationManager::class.java).notify(NOTI_ID, buildStatus()) } catch (_: Exception) {}
    }

    private fun sendSoon() {
        sender?.let { if (!it.isShutdown) it.execute { send(hello = false) } }
    }

    private fun send(hello: Boolean) {
        try {
            val p = BatteryReader.packet(this, hello)
            lastSent = p.getInt("level") to p.getBoolean("charging")
            BatteryStore.upsert(this, p)          // 내 기기도 목록에 반영
            LowAlerts.check(this)
            BatteryWidget.refresh(this)
            updateStatus()

            val bytes = p.toString().toByteArray(Charsets.UTF_8)
            val s = socket ?: return
            for (addr in broadcastAddresses()) {
                try { s.send(DatagramPacket(bytes, bytes.size, addr, PORT)) } catch (_: Exception) {}
            }
        } catch (_: Exception) {
        }
    }

    private fun broadcastAddresses(): Set<InetAddress> {
        val out = mutableSetOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
        try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNullTo(out) { it.broadcast }
        } catch (_: Exception) {}
        return out
    }

    private fun startListening() {
        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(PORT))
            }
        } catch (e: Exception) {
            null
        }
        socket = s ?: return
        val myId = BatteryStore.deviceId(this)
        listener = Thread {
            val buf = ByteArray(8192)
            while (!Thread.currentThread().isInterrupted && !s.isClosed) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    s.receive(pkt)
                    val json = JSONObject(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                    if (json.optString("app") != "batteryhub") continue
                    if (json.optString("id") == myId) continue
                    BatteryStore.upsert(this, json)
                    LowAlerts.check(this)
                    BatteryWidget.refresh(this)
                    updateStatus()
                    // 새로 켜진 기기가 인사하면 바로 내 정보로 답장 → 기다리지 않고 목록이 채워짐
                    if (json.optBoolean("hello")) sendSoon()
                } catch (e: Exception) {
                    if (s.isClosed) break
                }
            }
        }.apply { isDaemon = true; name = "batteryhub-listen"; start() }
    }
}
