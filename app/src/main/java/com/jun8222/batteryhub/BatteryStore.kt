package com.jun8222.batteryhub

import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 한 줄에 표시될 항목 (폰/태블릿 또는 그 기기에 연결된 블루투스 액세서리) */
data class Entry(
    val key: String,
    val owner: String,        // 이 정보를 보낸 기기의 id
    val name: String,
    val level: Int,
    val charging: Boolean,
    val ts: Long,             // 마지막 수신 시각 (ms)
    val isAccessory: Boolean,
)

data class Accessory(val name: String, val level: Int)

object BatteryStore {
    private const val PREFS = "battery_hub"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_ID = "device_id"
    private const val KEY_NAME = "device_name"
    private const val KEY_RUNNING = "running"

    /** 이 시간 넘게 소식이 없으면 목록에서 지움 */
    const val FORGET_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun deviceId(ctx: Context): String {
        val p = prefs(ctx)
        p.getString(KEY_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString()
        p.edit().putString(KEY_ID, id).apply()
        return id
    }

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, null)
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun setDeviceName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun isRunning(ctx: Context) = prefs(ctx).getBoolean(KEY_RUNNING, false)
    fun setRunning(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_RUNNING, v).apply()

    /** 기기 하나가 보낸 배터리 정보(자기 자신 포함)를 저장. 그 기기의 액세서리 목록은 통째로 교체. */
    fun upsert(ctx: Context, packet: JSONObject) {
        val id = packet.getString("id")
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val all = load(ctx).filter { it.owner != id }.toMutableList()
            all += Entry(
                key = id, owner = id,
                name = packet.optString("name", "기기"),
                level = packet.optInt("level", -1),
                charging = packet.optBoolean("charging", false),
                ts = now, isAccessory = false,
            )
            val acc = packet.optJSONArray("acc") ?: JSONArray()
            for (i in 0 until acc.length()) {
                val a = acc.getJSONObject(i)
                val n = a.optString("name", "블루투스 기기")
                all += Entry(
                    key = "$id|$n", owner = id, name = n,
                    level = a.optInt("level", -1), charging = false,
                    ts = now, isAccessory = true,
                )
            }
            save(ctx, all.filter { now - it.ts < FORGET_AFTER_MS })
        }
    }

    fun remove(ctx: Context, owner: String) {
        synchronized(lock) { save(ctx, load(ctx).filter { it.owner != owner }) }
    }

    /** 표시 순서: 내 기기 → 다른 기기(이름순), 각 기기 바로 밑에 그 기기의 액세서리 */
    fun sorted(ctx: Context): List<Entry> {
        val me = deviceId(ctx)
        val all = synchronized(lock) { load(ctx) }
        val devices = all.filter { !it.isAccessory }
            .sortedWith(compareBy<Entry>({ it.owner != me }, { it.name }))
        val out = mutableListOf<Entry>()
        for (d in devices) {
            out += d
            out += all.filter { it.isAccessory && it.owner == d.owner }.sortedBy { it.name }
        }
        return out
    }

    private fun load(ctx: Context): List<Entry> {
        val raw = prefs(ctx).getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(
                    o.getString("key"), o.getString("owner"), o.getString("name"),
                    o.getInt("level"), o.getBoolean("charging"), o.getLong("ts"),
                    o.getBoolean("acc"),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("key", it.key).put("owner", it.owner).put("name", it.name)
                    .put("level", it.level).put("charging", it.charging).put("ts", it.ts)
                    .put("acc", it.isAccessory)
            )
        }
        prefs(ctx).edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }
}
