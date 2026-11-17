package com.jun8222.batteryhub

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.view.View
import android.widget.RemoteViews

class BatteryWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids)
    }

    companion object {
        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5, R.id.name6, R.id.name7)
        private val BARS = intArrayOf(R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6, R.id.bar7)
        private val PCTS = intArrayOf(R.id.pct0, R.id.pct1, R.id.pct2, R.id.pct3, R.id.pct4, R.id.pct5, R.id.pct6, R.id.pct7)

        const val STALE_MS = 10L * 60 * 1000

        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            val ids = m.getAppWidgetIds(ComponentName(ctx, BatteryWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, m, ids)
        }

        fun levelColor(level: Int): Int = when {
            level < 0 -> Color.GRAY
            level <= 15 -> Color.parseColor("#FF5A4E")
            level <= 30 -> Color.parseColor("#FFB020")
            else -> Color.parseColor("#4CD964")
        }

        fun ago(ms: Long): String {
            val min = ms / 60000
            return when {
                min < 60 -> "${min}분 전"
                min < 60 * 24 -> "${min / 60}시간 전"
                else -> "${min / 1440}일 전"
            }
        }

        private fun render(ctx: Context, m: AppWidgetManager, ids: IntArray) {
            val list = BatteryStore.sorted(ctx).take(ROWS.size)
            val now = System.currentTimeMillis()
            val v = RemoteViews(ctx.packageName, R.layout.widget_battery)

            v.setViewVisibility(R.id.empty, if (list.isEmpty()) View.VISIBLE else View.GONE)

            ROWS.forEachIndexed { i, rowId ->
                val e = list.getOrNull(i)
                if (e == null) {
                    v.setViewVisibility(rowId, View.GONE)
                    return@forEachIndexed
                }
                v.setViewVisibility(rowId, View.VISIBLE)
                val stale = now - e.ts > STALE_MS
                val textColor = if (stale) Color.parseColor("#88FFFFFF") else Color.WHITE

                val label = (if (e.isAccessory) "   🎧 " else "") + e.name
                v.setTextViewText(NAMES[i], label)
                v.setTextColor(NAMES[i], textColor)

                v.setProgressBar(BARS[i], 100, e.level.coerceIn(0, 100), false)
                if (Build.VERSION.SDK_INT >= 31) {
                    val c = if (stale) Color.GRAY else levelColor(e.level)
                    v.setColorStateList(BARS[i], "setProgressTintList", ColorStateList.valueOf(c))
                }

                val pct = buildString {
                    if (e.charging && !stale) append("⚡")
                    append(if (e.level >= 0) "${e.level}%" else "-")
                    if (stale) append(" · ").append(ago(now - e.ts))
                }
                v.setTextViewText(PCTS[i], pct)
                v.setTextColor(PCTS[i], textColor)
            }

            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            v.setOnClickPendingIntent(R.id.root, open)
            m.updateAppWidget(ids, v)
        }
    }
}
