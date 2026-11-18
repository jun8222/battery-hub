package com.jun8222.batteryhub

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * 위젯에 '항상 표시'하지 않는 기기가 기준 이하로 떨어지면 알림.
 * 한 번 알린 기기는 충전을 시작하거나 기준+5% 위로 올라가야 다시 알림.
 */
object LowAlerts {
    private const val CHANNEL = "low_battery"
    private val lock = Any()

    fun check(ctx: Context) = synchronized(lock) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val me = BatteryStore.deviceId(ctx)
        val th = BatteryStore.threshold(ctx)
        val notified = BatteryStore.notified(ctx).toMutableSet()
        val entries = BatteryStore.sorted(ctx)

        for (e in entries) {
            if (e.owner == me && !e.isAccessory) continue // 이 기기 자체는 시스템이 알려줌
            val id = notiId(e.key)
            if (BatteryStore.isLowAlert(ctx, e)) {
                if (e.key !in notified && BatteryStore.alertsEnabled(ctx)) {
                    post(ctx, nm, e, id)
                    notified += e.key
                }
            } else if (e.charging || e.level > th + 5) {
                if (notified.remove(e.key)) nm.cancel(id)
            }
        }
        // 목록에서 사라진 항목 정리
        val keys = entries.map { it.key }.toSet()
        notified.retainAll(keys)
        BatteryStore.setNotified(ctx, notified)
    }

    private fun notiId(key: String) = 1000 + (key.hashCode() and 0x0FFFFFFF)

    private fun post(ctx: Context, nm: NotificationManager, e: Entry, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "배터리 부족 알림", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_battery)
            .setContentTitle("🪫 ${e.name} 배터리 ${e.level}%")
            .setContentText("충전이 필요해요")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { nm.notify(id, n) } catch (_: SecurityException) {}
    }
}
