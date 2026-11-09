package com.lcs.jdsniper.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置持久化：assets/app-config.json 为出厂默认，SharedPreferences 为用户覆盖。
 */
class ConfigStore(private val ctx: Context) {

    private val prefs = ctx.getSharedPreferences("jd_sniper", Context.MODE_PRIVATE)

    fun assetsDefault(): String = try {
        ctx.assets.open("app-config.json").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        FALLBACK
    }

    fun load(): AppConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: assetsDefault()
        val parsed = try {
            AppConfig.parse(raw)
        } catch (e: Exception) {
            AppConfig.parse(assetsDefault())
        }
        // 自愈：历史上存过一份 events 为空的配置时，这里补回内置时间表并回写
        if (parsed.events.isEmpty()) {
            val fixed = parsed.copy(events = AppConfig.DEFAULT_EVENTS)
            save(fixed)
            return fixed
        }
        return parsed
    }

    fun save(cfg: AppConfig) {
        prefs.edit().putString(KEY_CONFIG, cfg.toJson()).apply()
    }

    fun saveRaw(json: String) {
        prefs.edit().putString(KEY_CONFIG, json).apply()
    }

    fun reset() {
        prefs.edit().remove(KEY_CONFIG).apply()
    }

    var offsetMs: Long
        get() = prefs.getLong(KEY_OFFSET, 0L)
        set(v) {
            prefs.edit().putLong(KEY_OFFSET, v).apply()
        }

    var offsetAt: Long
        get() = prefs.getLong(KEY_OFFSET_AT, 0L)
        set(v) {
            prefs.edit().putLong(KEY_OFFSET_AT, v).apply()
        }

    /** 暂停布防：不排闹钟、不自动拉起抢购台，配置与商品全部保留 */
    var paused: Boolean
        get() = prefs.getBoolean(KEY_PAUSED, false)
        set(v) {
            prefs.edit().putBoolean(KEY_PAUSED, v).apply()
        }

    fun saveOffset(offset: Long, at: Long) {
        prefs.edit().putLong(KEY_OFFSET, offset).putLong(KEY_OFFSET_AT, at).apply()
    }

    var lastEventKey: String
        get() = prefs.getString(KEY_LAST_EVENT, "") ?: ""
        set(v) {
            prefs.edit().putString(KEY_LAST_EVENT, v).apply()
        }

    /** 已执行过的 eventKey 集合，用于闹钟幂等 */
    fun markFired(key: String) {
        val arr = firedArray()
        for (i in 0 until arr.length()) {
            if (arr.optString(i) == key) return
        }
        arr.put(key)
        val trimmed = JSONArray()
        for (i in maxOf(0, arr.length() - 50) until arr.length()) trimmed.put(arr.optString(i))
        prefs.edit().putString(KEY_FIRED, trimmed.toString()).apply()
    }

    fun hasFired(key: String): Boolean {
        val arr = firedArray()
        for (i in 0 until arr.length()) if (arr.optString(i) == key) return true
        return false
    }

    fun firedKeys(): Set<String> {
        val arr = firedArray()
        val out = HashSet<String>()
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    private fun firedArray(): JSONArray = try {
        JSONArray(prefs.getString(KEY_FIRED, "[]") ?: "[]")
    } catch (e: Exception) {
        JSONArray()
    }

    fun itemsJson(): String {
        val arr = JSONArray()
        load().items.forEach { i ->
            arr.put(
                JSONObject()
                    .put("alias", i.alias)
                    .put("sku", i.sku)
                    .put("url", i.url)
                    .put("priority", i.priority)
                    .put("enabled", i.enabled)
            )
        }
        return arr.toString()
    }

    companion object {
        private const val KEY_CONFIG = "config_json"
        private const val KEY_OFFSET = "clock_offset_ms"
        private const val KEY_OFFSET_AT = "clock_offset_at"
        private const val KEY_PAUSED = "paused"
        private const val KEY_LAST_EVENT = "last_event_key"
        private const val KEY_FIRED = "fired_keys"

        private val FALLBACK = """
        {"version":1,"tz":"Asia/Shanghai","preloadLeadSec":300,"fireLeadMs":250,
         "retryWindowSec":90,"staggerPerItemMs":60,
         "events":[{"id":"fri_1000","dow":5,"time":"10:00:00","mode":"GRAB","leadMs":250}],
         "items":[],"risk":{"jitterMinMs":60,"jitterMaxMs":260,"tapIntervalMs":600,
         "maxAttemptsPerItem":3,"stopOnSoldOut":true,"manualCaptcha":true},
         "ui":{"keepScreenOn":true,"autoSubmit":true,"autoPay":false,"vibrate":true}}
        """.trimIndent()
    }
}
