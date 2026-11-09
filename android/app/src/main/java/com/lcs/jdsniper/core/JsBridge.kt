package com.lcs.jdsniper.core

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

data class BridgeEvent(
    val type: String,
    val sku: String,
    val stage: String,
    val ts: Long,
    val attempt: Int,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", type)
        .put("sku", sku)
        .put("stage", stage)
        .put("ts", ts)
        .put("attempt", attempt)
        .put("detail", detail)

    fun toLine(): String = toJson().toString()

    companion object {
        fun parse(json: String): BridgeEvent? = try {
            val o = JSONObject(json)
            BridgeEvent(
                type = o.optString("type", "unknown"),
                sku = o.optString("sku", ""),
                stage = o.optString("stage", ""),
                ts = o.optLong("ts", System.currentTimeMillis()),
                attempt = o.optInt("attempt", 0),
                detail = firstDetail(o)
            )
        } catch (e: Exception) {
            null
        }

        private fun firstDetail(o: JSONObject): String {
            for (k in arrayOf("detail", "reason", "text", "url", "snapshot")) {
                val v = o.optString(k, "")
                if (v.isNotEmpty()) return v
            }
            return ""
        }
    }
}

/** JS -> 原生唯一入口。JS 线程回调，统一切主线程。 */
class JsBridge(private val onEvent: (BridgeEvent) -> Unit) {

    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun post(json: String) {
        val e = BridgeEvent.parse(json) ?: return
        main.post { onEvent(e) }
    }
}
