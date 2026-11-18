package com.jun8222.batteryhub

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 인터넷 동기화 (Supabase). 같은 '연결 코드'를 넣은 기기끼리만 서로의 배터리를 봄.
 * 서버에는 코드의 해시만 저장되고, 표는 직접 읽을 수 없으며 함수(push_status/pull_status)로만 접근.
 */
object CloudSync {
    private const val BASE = "https://gfrtxtvgvxbummmrvtja.supabase.co/rest/v1/rpc/"
    private const val KEY = "sb_publishable_UpGShwvpmcSX83gqzGQ1EQ_CmCOcc2g"

    @Volatile var lastOk: Long = 0
    @Volatile var lastError: String? = null

    /** 내 배터리 올리고, 같은 코드의 다른 기기 배터리 받아오기 */
    fun sync(ctx: Context, packet: JSONObject) {
        if (!BatteryStore.cloudEnabled(ctx)) return
        val code = BatteryStore.groupCode(ctx)
        val me = BatteryStore.deviceId(ctx)
        try {
            val pushBody = JSONObject()
                .put("p_group", code)
                .put("p_device", me)
                .put("p_payload", JSONObject(packet.toString()).put("hello", false))
            post("push_status", pushBody)

            val res = post("pull_status", JSONObject().put("p_group", code)) ?: return
            val arr = JSONArray(res)
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val row = arr.getJSONObject(i)
                if (row.optString("device_id") == me) continue
                val p = row.optJSONObject("payload") ?: continue
                p.put("ts_override", now - row.optLong("age_seconds", 0) * 1000)
                BatteryStore.upsert(ctx, p)
            }
            lastOk = now
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        }
    }

    private fun post(fn: String, body: JSONObject): String? {
        val c = URL(BASE + fn).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("apikey", KEY)
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)
                ?.bufferedReader()?.use { it.readText() }
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return text
        } finally {
            c.disconnect()
        }
    }
}
