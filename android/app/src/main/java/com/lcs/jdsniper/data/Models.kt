package com.lcs.jdsniper.data

import org.json.JSONArray
import org.json.JSONObject

data class SaleEvent(
    val id: String,
    val dow: Int,
    val time: String,
    val mode: String,
    val leadMs: Long
)

data class ItemConfig(
    val alias: String,
    val sku: String,
    val url: String,
    val expectPrice: String,
    val priority: Int,
    val enabled: Boolean,
    val reserve: Boolean
)

data class RiskConfig(
    val jitterMinMs: Long,
    val jitterMaxMs: Long,
    val tapIntervalMs: Long,
    val maxAttemptsPerItem: Int,
    val stopOnSoldOut: Boolean,
    val manualCaptcha: Boolean
) {
    fun toJs(): JSONObject = JSONObject()
        .put("jitterMinMs", jitterMinMs)
        .put("jitterMaxMs", jitterMaxMs)
        .put("tapIntervalMs", tapIntervalMs)
        .put("maxAttemptsPerItem", maxAttemptsPerItem)
        .put("stopOnSoldOut", stopOnSoldOut)
        .put("manualCaptcha", manualCaptcha)
}

data class UiConfig(
    val keepScreenOn: Boolean,
    val autoSubmit: Boolean,
    val autoPay: Boolean,
    val vibrate: Boolean
)

data class AppConfig(
    val version: Int,
    val tz: String,
    val preloadLeadSec: Long,
    val fireLeadMs: Long,
    val retryWindowSec: Long,
    val staggerPerItemMs: Long,
    val events: List<SaleEvent>,
    val items: List<ItemConfig>,
    val risk: RiskConfig,
    val ui: UiConfig
) {

    fun enabledItems(): List<ItemConfig> =
        items.filter { it.enabled }.sortedBy { it.priority }

    /** 传给页面内 autopilot 的配置（字段名与 JS 默认项一致） */
    fun jsConfig(autoSubmitOverride: Boolean = ui.autoSubmit): JSONObject = JSONObject()
        .put("jitterMinMs", risk.jitterMinMs)
        .put("jitterMaxMs", risk.jitterMaxMs)
        .put("tapIntervalMs", risk.tapIntervalMs)
        .put("maxAttemptsPerItem", risk.maxAttemptsPerItem)
        .put("stopOnSoldOut", risk.stopOnSoldOut)
        .put("retryWindowSec", retryWindowSec)
        .put("autoSubmit", autoSubmitOverride)

    fun toJson(): String {
        val eventsArr = JSONArray()
        events.forEach { e ->
            eventsArr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("dow", e.dow)
                    .put("time", e.time)
                    .put("mode", e.mode)
                    .put("leadMs", e.leadMs)
            )
        }
        val itemsArr = JSONArray()
        items.forEach { i ->
            itemsArr.put(
                JSONObject()
                    .put("alias", i.alias)
                    .put("sku", i.sku)
                    .put("url", i.url)
                    .put("expectPrice", i.expectPrice)
                    .put("priority", i.priority)
                    .put("enabled", i.enabled)
                    .put("reserve", i.reserve)
            )
        }
        val riskObj = JSONObject()
            .put("jitterMinMs", risk.jitterMinMs)
            .put("jitterMaxMs", risk.jitterMaxMs)
            .put("tapIntervalMs", risk.tapIntervalMs)
            .put("maxAttemptsPerItem", risk.maxAttemptsPerItem)
            .put("stopOnSoldOut", risk.stopOnSoldOut)
            .put("manualCaptcha", risk.manualCaptcha)
        val uiObj = JSONObject()
            .put("keepScreenOn", ui.keepScreenOn)
            .put("autoSubmit", ui.autoSubmit)
            .put("autoPay", ui.autoPay)
            .put("vibrate", ui.vibrate)

        return JSONObject()
            .put("version", version)
            .put("tz", tz)
            .put("preloadLeadSec", preloadLeadSec)
            .put("fireLeadMs", fireLeadMs)
            .put("retryWindowSec", retryWindowSec)
            .put("staggerPerItemMs", staggerPerItemMs)
            .put("events", eventsArr)
            .put("items", itemsArr)
            .put("risk", riskObj)
            .put("ui", uiObj)
            .toString(2)
    }

    companion object {

        fun parse(text: String): AppConfig {
            val o = JSONObject(text)
            val eventsArr = o.optJSONArray("events") ?: JSONArray()
            val events = ArrayList<SaleEvent>(eventsArr.length())
            for (i in 0 until eventsArr.length()) {
                val e = eventsArr.getJSONObject(i)
                events.add(
                    SaleEvent(
                        id = e.optString("id", "ev$i"),
                        dow = e.optInt("dow", 5),
                        time = e.optString("time", "10:00:00"),
                        mode = e.optString("mode", "GRAB"),
                        leadMs = e.optLong("leadMs", o.optLong("fireLeadMs", 250L))
                    )
                )
            }

            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<ItemConfig>(itemsArr.length())
            for (i in 0 until itemsArr.length()) {
                val it = itemsArr.getJSONObject(i)
                items.add(
                    ItemConfig(
                        alias = it.optString("alias", "商品$i"),
                        sku = it.optString("sku", ""),
                        url = it.optString("url", ""),
                        expectPrice = it.optString("expectPrice", ""),
                        priority = it.optInt("priority", i + 1),
                        enabled = it.optBoolean("enabled", false),
                        reserve = it.optBoolean("reserve", true)
                    )
                )
            }

            val r = o.optJSONObject("risk") ?: JSONObject()
            val risk = RiskConfig(
                jitterMinMs = r.optLong("jitterMinMs", 60L),
                jitterMaxMs = r.optLong("jitterMaxMs", 260L),
                tapIntervalMs = r.optLong("tapIntervalMs", 600L),
                maxAttemptsPerItem = r.optInt("maxAttemptsPerItem", 3),
                stopOnSoldOut = r.optBoolean("stopOnSoldOut", true),
                manualCaptcha = r.optBoolean("manualCaptcha", true)
            )

            val u = o.optJSONObject("ui") ?: JSONObject()
            val ui = UiConfig(
                keepScreenOn = u.optBoolean("keepScreenOn", true),
                autoSubmit = u.optBoolean("autoSubmit", true),
                autoPay = u.optBoolean("autoPay", false),
                vibrate = u.optBoolean("vibrate", true)
            )

            return AppConfig(
                version = o.optInt("version", 1),
                tz = o.optString("tz", "Asia/Shanghai"),
                preloadLeadSec = o.optLong("preloadLeadSec", 300L),
                fireLeadMs = o.optLong("fireLeadMs", 250L),
                retryWindowSec = o.optLong("retryWindowSec", 90L),
                staggerPerItemMs = o.optLong("staggerPerItemMs", 60L),
                events = if (events.isEmpty()) DEFAULT_EVENTS else events,
                items = items,
                risk = risk,
                ui = ui
            )
        }

        /** 时间表是硬需求：任何情况下读不到事件，都回落到这套内置排期。 */
        val DEFAULT_EVENTS: List<SaleEvent> = listOf(
            SaleEvent("thu_reserve", 4, "20:00:00", "RESERVE_AND_GRAB", 400L),
            SaleEvent("fri_1000", 5, "10:00:00", "GRAB", 250L),
            SaleEvent("fri_1600", 5, "16:00:00", "GRAB", 250L),
            SaleEvent("fri_2000", 5, "20:00:00", "GRAB", 250L),
            SaleEvent("fri_2200", 5, "22:00:00", "GRAB", 250L)
        )

        fun default(assetsJson: String): AppConfig = parse(assetsJson)
    }
}
