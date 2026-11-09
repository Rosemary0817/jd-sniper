package com.lcs.jdsniper.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.lcs.jdsniper.data.AppConfig
import com.lcs.jdsniper.ui.SniperActivity
import java.time.Instant

object AlarmScheduler {

    private const val REQ_PREWARM = 1001
    private const val REQ_SHOW = 1002

    fun schedule(ctx: Context, cfg: AppConfig, now: Instant = Instant.now()): SaleCalendar.Trigger? {
        cancel(ctx)
        val trigger = SaleCalendar.upcoming(cfg, now).firstOrNull()
            ?: SaleCalendar.upcoming(cfg.copy(events = AppConfig.DEFAULT_EVENTS), now).firstOrNull()
            ?: return null

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(trigger.prewarmAt.toEpochMilli(), System.currentTimeMillis() + 1500L)

        val open = PendingIntent.getBroadcast(
            ctx,
            REQ_PREWARM,
            Intent(ctx, PrewarmReceiver::class.java).setAction(ACTION_PREWARM).putExtra(EXTRA_EVENT_KEY, trigger.key),
            flags()
        )
        val show = PendingIntent.getActivity(
            ctx,
            REQ_SHOW,
            Intent(ctx, SniperActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            flags()
        )

        try {
            val info = AlarmManager.AlarmClockInfo(at, show)
            am.setAlarmClock(info, open)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, open)
        } catch (e: Exception) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, open)
        }
        return trigger
    }

    fun scheduleNext(ctx: Context, cfg: AppConfig, now: Instant = Instant.now()): SaleCalendar.Trigger? {
        val trigger = SaleCalendar.upcoming(cfg, now).firstOrNull()
            ?: SaleCalendar.upcoming(cfg.copy(events = AppConfig.DEFAULT_EVENTS), now).firstOrNull()
            ?: return null
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val at = maxOf(trigger.prewarmAt.toEpochMilli(), System.currentTimeMillis() + 1500L)
        val open = PendingIntent.getBroadcast(
            ctx,
            REQ_PREWARM,
            Intent(ctx, PrewarmReceiver::class.java).setAction(ACTION_PREWARM).putExtra(EXTRA_EVENT_KEY, trigger.key),
            flags()
        )
        try {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, null), open)
        } catch (e: Exception) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, open)
        }
        return trigger
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            REQ_PREWARM,
            Intent(ctx, PrewarmReceiver::class.java).setAction(ACTION_PREWARM),
            flags()
        )
        am.cancel(pi)
        pi.cancel()
    }

    fun canUseExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    const val ACTION_PREWARM = "com.lcs.jdsniper.action.PREWARM"
    const val EXTRA_EVENT_KEY = "event_key"
}
