package com.jun8222.batteryhub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 재부팅·앱 업데이트 후 자동으로 다시 실행 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (BatteryStore.isRunning(context)) {
            try { SyncService.start(context) } catch (_: Exception) {}
        }
        BatteryWidget.refresh(context)
    }
}
