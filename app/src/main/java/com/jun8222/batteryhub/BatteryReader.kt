package com.jun8222.batteryhub

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object BatteryReader {

    /** 블루투스 배터리 변경 방송으로 받은 값 (주소 → 이름/잔량) */
    val btCache = ConcurrentHashMap<String, Accessory>()

    const val ACTION_BT_BATTERY = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
    const val EXTRA_BT_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    fun own(ctx: Context): Pair<Int, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return -1 to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    fun hasBtPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** 지금 연결돼 있고 배터리 정보를 알려주는 블루투스 기기 (이어폰, 워치 등) */
    @SuppressLint("MissingPermission")
    fun accessories(ctx: Context): List<Accessory> {
        if (!hasBtPermission(ctx)) return emptyList()
        return try {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
            if (!adapter.isEnabled) return emptyList()
            adapter.bondedDevices.orEmpty().mapNotNull { d ->
                if (!isConnected(d)) {
                    btCache.remove(d.address)
                    return@mapNotNull null
                }
                val lvl = reflectLevel(d).takeIf { it in 0..100 } ?: btCache[d.address]?.level ?: -1
                if (lvl < 0) null else Accessory(d.name ?: "블루투스 기기", lvl)
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun isConnected(d: BluetoothDevice): Boolean = try {
        d.javaClass.getMethod("isConnected").invoke(d) as Boolean
    } catch (e: Throwable) {
        false
    }

    private fun reflectLevel(d: BluetoothDevice): Int = try {
        d.javaClass.getMethod("getBatteryLevel").invoke(d) as Int
    } catch (e: Throwable) {
        -1
    }

    /** 다른 기기로 보낼 내 배터리 정보 */
    fun packet(ctx: Context, hello: Boolean = false): JSONObject {
        val (level, charging) = own(ctx)
        val acc = JSONArray()
        accessories(ctx).forEach { acc.put(JSONObject().put("name", it.name).put("level", it.level)) }
        return JSONObject()
            .put("app", "batteryhub").put("v", 1)
            .put("id", BatteryStore.deviceId(ctx))
            .put("name", BatteryStore.deviceName(ctx))
            .put("level", level).put("charging", charging)
            .put("acc", acc)
            .put("hello", hello)
    }
}
