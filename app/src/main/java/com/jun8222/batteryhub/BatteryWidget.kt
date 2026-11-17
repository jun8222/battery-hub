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
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews

/** 공통: 위젯 종류별 그리기는 render()만 다름 */
abstract class BaseWidget : AppWidgetProvider() {
    abstract fun render(ctx: Context, opts: Bundle): RemoteViews

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { draw(context, manager, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        draw(context, manager, id) // 크기가 바뀌면 보이는 개수 다시 계산
    }

    fun draw(ctx: Context, m: AppWidgetManager, id: Int) {
        val v = render(ctx, m.getAppWidgetOptions(id))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.root, open)
        m.updateAppWidget(id, v)
    }
}

/** 목록형 */
class BatteryWidget : BaseWidget() {

    override fun render(ctx: Context, opts: Bundle): RemoteViews {
        val h = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180)
        val maxRows = ((h - 50) / 24).coerceIn(1, ROWS.size)
        val list = BatteryStore.sorted(ctx).take(maxRows)
        val now = System.currentTimeMillis()
        val v = RemoteViews(ctx.packageName, R.layout.widget_battery)
        v.setViewVisibility(R.id.empty, if (list.isEmpty()) View.VISIBLE else View.GONE)

        ROWS.forEachIndexed { i, rowId ->
            val e = list.getOrNull(i)
            if (e == null) { v.setViewVisibility(rowId, View.GONE); return@forEachIndexed }
            v.setViewVisibility(rowId, View.VISIBLE)
            val stale = now - e.ts > STALE_MS
            val tc = textColor(stale)
            v.setTextViewText(NAMES[i], (if (e.isAccessory) "   🎧 " else "") + e.name)
            v.setTextColor(NAMES[i], tc)
            v.setProgressBar(BARS[i], 100, e.level.coerceIn(0, 100), false)
            tint(v, BARS[i], if (stale) Color.GRAY else levelColor(e.level))
            v.setTextViewText(PCTS[i], buildString {
                if (e.charging && !stale) append("⚡")
                append(if (e.level >= 0) "${e.level}%" else "-")
                if (stale) append(" · ").append(ago(now - e.ts))
            })
            v.setTextColor(PCTS[i], tc)
        }
        return v
    }

    companion object {
        private val ROWS = intArrayOf(R.id.row0, R.id.row1, R.id.row2, R.id.row3, R.id.row4, R.id.row5, R.id.row6, R.id.row7)
        private val NAMES = intArrayOf(R.id.name0, R.id.name1, R.id.name2, R.id.name3, R.id.name4, R.id.name5, R.id.name6, R.id.name7)
        private val BARS = intArrayOf(R.id.bar0, R.id.bar1, R.id.bar2, R.id.bar3, R.id.bar4, R.id.bar5, R.id.bar6, R.id.bar7)
        private val PCTS = intArrayOf(R.id.pct0, R.id.pct1, R.id.pct2, R.id.pct3, R.id.pct4, R.id.pct5, R.id.pct6, R.id.pct7)

        val SLOTS = intArrayOf(R.id.slot0, R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4, R.id.slot5)
        val RINGS = intArrayOf(R.id.ring0, R.id.ring1, R.id.ring2, R.id.ring3, R.id.ring4, R.id.ring5)
        val RPCTS = intArrayOf(R.id.rpct0, R.id.rpct1, R.id.rpct2, R.id.rpct3, R.id.rpct4, R.id.rpct5)
        val RNAMES = intArrayOf(R.id.rname0, R.id.rname1, R.id.rname2, R.id.rname3, R.id.rname4, R.id.rname5)

        const val STALE_MS = 10L * 60 * 1000

        /** 모든 종류의 위젯 새로 그리기 */
        fun refresh(ctx: Context) {
            val m = AppWidgetManager.getInstance(ctx)
            listOf(BatteryWidget(), CircleWidget(), RowWidget()).forEach { w ->
                m.getAppWidgetIds(ComponentName(ctx, w.javaClass)).forEach { id -> w.draw(ctx, m, id) }
            }
        }

        fun levelColor(level: Int): Int = when {
            level < 0 -> Color.GRAY
            level <= 15 -> Color.parseColor("#FF5A4E")
            level <= 30 -> Color.parseColor("#FFB020")
            else -> Color.parseColor("#4CD964")
        }

        fun textColor(stale: Boolean) = if (stale) Color.parseColor("#88FFFFFF") else Color.WHITE

        fun tint(v: RemoteViews, id: Int, c: Int) {
            if (Build.VERSION.SDK_INT >= 31) v.setColorStateList(id, "setProgressTintList", ColorStateList.valueOf(c))
        }

        fun ago(ms: Long): String {
            val min = ms / 60000
            return when {
                min < 60 -> "${min}분 전"
                min < 60 * 24 -> "${min / 60}시간 전"
                else -> "${min / 1440}일 전"
            }
        }

        /** 원형 슬롯 하나 채우기 */
        fun fillSlot(v: RemoteViews, i: Int, e: Entry?, now: Long) {
            if (e == null) { v.setViewVisibility(SLOTS[i], View.GONE); return }
            v.setViewVisibility(SLOTS[i], View.VISIBLE)
            val stale = now - e.ts > STALE_MS
            v.setProgressBar(RINGS[i], 100, e.level.coerceIn(0, 100), false)
            tint(v, RINGS[i], if (stale) Color.GRAY else levelColor(e.level))
            v.setTextViewText(RPCTS[i], (if (e.charging && !stale) "⚡" else "") + (if (e.level >= 0) "${e.level}%" else "-"))
            v.setTextColor(RPCTS[i], textColor(stale))
            v.setTextViewText(RNAMES[i], (if (e.isAccessory) "🎧" else "") + e.name + (if (stale) " (${ago(now - e.ts)})" else ""))
            v.setTextColor(RNAMES[i], textColor(stale))
        }

        /** 기기가 하나도 없을 때 첫 칸에 안내 */
        fun emptyHint(v: RemoteViews) {
            v.setViewVisibility(SLOTS[0], View.VISIBLE)
            v.setProgressBar(RINGS[0], 100, 0, false)
            v.setTextViewText(RPCTS[0], "-")
            v.setTextViewText(RNAMES[0], "눌러서 시작")
        }
    }
}

/** 원형 (2줄 × 2~3칸) */
class CircleWidget : BaseWidget() {
    override fun render(ctx: Context, opts: Bundle): RemoteViews {
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 150)
        val cols = if (w >= 210) 3 else 2
        val list = BatteryStore.sorted(ctx).take(cols * 2)
        val now = System.currentTimeMillis()
        val v = RemoteViews(ctx.packageName, R.layout.widget_circle)
        for (i in 0 until 6) {
            val r = i / 3; val c = i % 3
            val e = if (c < cols) list.getOrNull(r * cols + c) else null
            BatteryWidget.fillSlot(v, i, e, now)
        }
        if (list.isEmpty()) BatteryWidget.emptyHint(v)
        return v
    }
}

/** 가로형 (한 줄, 가로 길이에 맞춰 2~6칸) */
class RowWidget : BaseWidget() {
    override fun render(ctx: Context, opts: Bundle): RemoteViews {
        val w = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300)
        val n = (w / 62).coerceIn(2, 6)
        val list = BatteryStore.sorted(ctx).take(n)
        val now = System.currentTimeMillis()
        val v = RemoteViews(ctx.packageName, R.layout.widget_row)
        for (i in 0 until 6) BatteryWidget.fillSlot(v, i, list.getOrNull(i), now)
        if (list.isEmpty()) BatteryWidget.emptyHint(v)
        return v
    }
}
