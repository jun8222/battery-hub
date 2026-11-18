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
    private const val KEY_HIDDEN = "hidden"          // 위젯에 '항상 표시' 안 하는 항목 key
    private const val KEY_THRESHOLD = "threshold"
    private const val KEY_ALERTS = "alerts"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_GROUP = "group_code"
    private const val KEY_CLOUD = "cloud"

    /** 배터리 부족 판정에 쓰는 최근성 (이보다 오래된 정보는 무시) */
    private const val LOW_FRESH_MS = 6L * 60 * 60 * 1000

    /** 이 시간 넘게 소식이 없으면 목록에서 지움 */
    const val FORGET_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    private val lock = Any()

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 기기 고유 ID. 앱을 지웠다 다시 깔아도 바뀌지 않도록 ANDROID_ID(같은 서명키·같은 기기면 고정)를 사용.
     */
    fun deviceId(ctx: Context): String {
        cachedId?.let { return it }
        val aid = try {
            android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
        } catch (e: Exception) { null }
        val id = if (!aid.isNullOrBlank() && aid != "9774d56d682e549c") {
            val d = java.security.MessageDigest.getInstance("SHA-256").digest(aid.toByteArray())
            "a-" + d.joinToString("") { "%02x".format(it) }.take(24)
        } else {
            val p = prefs(ctx)
            p.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also { p.edit().putString(KEY_ID, it).apply() }
        }
        cachedId = id
        return id
    }
    @Volatile private var cachedId: String? = null

    fun deviceName(ctx: Context): String =
        prefs(ctx).getString(KEY_NAME, null)
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    fun setDeviceName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_NAME, name.trim()).apply()
    }

    fun isRunning(ctx: Context) = prefs(ctx).getBoolean(KEY_RUNNING, false)
    fun setRunning(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_RUNNING, v).apply()

    // ---- 인터넷 동기화 연결 코드 ----
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun groupCode(ctx: Context): String {
        prefs(ctx).getString(KEY_GROUP, null)?.let { return it }
        val rnd = java.security.SecureRandom()
        val raw = (1..16).map { CODE_CHARS[rnd.nextInt(CODE_CHARS.length)] }.joinToString("")
        val code = format(raw)
        prefs(ctx).edit().putString(KEY_GROUP, code).apply()
        return code
    }

    private fun format(raw: String) = raw.chunked(4).joinToString("-")

    /** 입력한 코드 정리. 16자리가 아니면 null */
    fun normalizeCode(input: String): String? {
        val raw = input.uppercase().filter { it.isLetterOrDigit() }
        return if (raw.length == 16) format(raw) else null
    }

    fun setGroupCode(ctx: Context, code: String) = prefs(ctx).edit().putString(KEY_GROUP, code).apply()

    fun cloudEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_CLOUD, true)
    fun setCloudEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_CLOUD, v).apply()

    // ---- 위젯 표시 선택 / 부족 알림 설정 ----
    fun isHidden(ctx: Context, key: String) = key in prefs(ctx).getStringSet(KEY_HIDDEN, emptySet())!!
    fun setPinned(ctx: Context, key: String, pinned: Boolean) {
        val set = prefs(ctx).getStringSet(KEY_HIDDEN, emptySet())!!.toMutableSet()
        if (pinned) set -= key else set += key
        prefs(ctx).edit().putStringSet(KEY_HIDDEN, set).apply()
    }

    fun threshold(ctx: Context) = prefs(ctx).getInt(KEY_THRESHOLD, 20)
    fun setThreshold(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_THRESHOLD, v.coerceIn(5, 80)).apply()

    fun alertsEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_ALERTS, true)
    fun setAlertsEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_ALERTS, v).apply()

    fun notified(ctx: Context): Set<String> = prefs(ctx).getStringSet(KEY_NOTIFIED, emptySet())!!.toSet()
    fun setNotified(ctx: Context, v: Set<String>) = prefs(ctx).edit().putStringSet(KEY_NOTIFIED, v).apply()

    fun isLow(ctx: Context, e: Entry) =
        e.level in 0..threshold(ctx) && !e.charging && System.currentTimeMillis() - e.ts < LOW_FRESH_MS

    /** 항상 표시는 꺼져 있지만 배터리가 부족해서 잠깐 나타나는 항목 */
    fun isLowAlert(ctx: Context, e: Entry) = isHidden(ctx, e.key) && isLow(ctx, e)

    /** 위젯에 보여줄 항목: 항상 표시 + 부족한 항목(맨 앞) */
    fun forWidget(ctx: Context): List<Entry> {
        val all = sorted(ctx)
        val low = all.filter { isLowAlert(ctx, it) }
        return low + all.filter { !isHidden(ctx, it.key) }
    }

    /** 기기 하나가 보낸 배터리 정보(자기 자신 포함)를 저장. 그 기기의 액세서리 목록은 통째로 교체. */
    fun upsert(ctx: Context, packet: JSONObject) {
        val id = packet.getString("id")
        // 인터넷으로 받은 정보는 서버에 올라간 시각을 사용
        val now = if (packet.has("ts_override")) packet.getLong("ts_override") else System.currentTimeMillis()
        synchronized(lock) {
            val old = load(ctx)
            val name = packet.optString("name", "기기")
            // 와이파이로 이미 더 최신 정보를 받았으면 무시
            if (old.any { it.owner == id && !it.isAccessory && it.ts >= now }) return
            // 같은 이름의 기기 = 같은 기기(재설치 등으로 ID가 바뀐 경우). 더 최신 기록만 남김
            val sameName = old.filter { !it.isAccessory && it.owner != id && it.name == name }
            if (sameName.any { it.ts > now }) return
            val drop = sameName.map { it.owner }.toSet() + id
            val all = old.filter { it.owner !in drop }.toMutableList()
            all += Entry(
                key = id, owner = id,
                name = name,
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
            save(ctx, all.filter { System.currentTimeMillis() - it.ts < FORGET_AFTER_MS })
        }
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
