package com.lcs.jdsniper.core

import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.data.SaleEvent
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 与 sim/lib/schedule.mjs 同契约：固定 Asia/Shanghai（中国无夏令时），严格大于 now。
 */
object SaleCalendar {

    val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    private val KEY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

    data class Trigger(
        val eventId: String,
        val mode: String,
        val at: Instant,
        val fireAt: Instant,
        val prewarmAt: Instant,
        val key: String,
        val local: String
    )

    fun nextTrigger(now: Instant, e: SaleEvent): Instant {
        val lt = try {
            LocalTime.parse(e.time)
        } catch (ex: Exception) {
            LocalTime.of(10, 0)
        }
        val target = DayOfWeek.of(((e.dow - 1) % 7 + 7) % 7 + 1)
        val today = now.atZone(ZONE).toLocalDate()
        for (i in 0..7L) {
            val d = today.plusDays(i)
            if (d.dayOfWeek != target) continue
            val cand = ZonedDateTime.of(d, lt, ZONE).toInstant()
            if (cand.isAfter(now)) return cand
        }
        return ZonedDateTime.of(today.plusWeeks(1), lt, ZONE).toInstant()
    }

    fun buildTrigger(cfg: AppConfig, e: SaleEvent, now: Instant): Trigger {
        val at = nextTrigger(now, e)
        val lead = if (e.leadMs > 0) e.leadMs else cfg.fireLeadMs
        val local = at.atZone(ZONE)
        return Trigger(
            eventId = e.id,
            mode = e.mode,
            at = at,
            fireAt = at.minusMillis(lead),
            prewarmAt = at.minusSeconds(cfg.preloadLeadSec),
            key = "${e.id}@${local.format(KEY_FMT)}",
            local = local.format(KEY_FMT)
        )
    }

    fun upcoming(cfg: AppConfig, now: Instant): List<Trigger> =
        cfg.events
            .map { buildTrigger(cfg, it, now) }
            .sortedBy { it.at }

    fun next(cfg: AppConfig, now: Instant): Trigger? = upcoming(cfg, now).firstOrNull()

    /** 若 now 已过 prewarm 但未过 at，则返回该 Trigger（用于“进来就开始”的情况） */
    fun current(cfg: AppConfig, now: Instant): Trigger? =
        upcoming(cfg, now).firstOrNull()?.takeIf { !now.isBefore(it.prewarmAt) }

    fun formatLocal(instant: Instant): String =
        instant.atZone(ZONE).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
}
