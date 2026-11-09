package com.lcs.jdsniper.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.lcs.jdsniper.data.ConfigStore
import java.time.Instant

/** 闹钟落地：拉起前台服务，由服务发全屏通知并拉起抢购台。 */
class PrewarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val store = ConfigStore(context)
        val cfg = store.load()
        val now = Instant.now()

        // 只有“最近 10 分钟内的目标”才真拉起，避免迟到闹钟乱开页面
        val trigger = SaleCalendar.upcoming(cfg, now).firstOrNull() ?: return
        val key = intent?.getStringExtra(AlarmScheduler.EXTRA_EVENT_KEY) ?: trigger.key
        if (now.isAfter(trigger.at.plusSeconds(600))) return
        if (store.hasFired(key)) return

        val svc = Intent(context, SniperService::class.java)
            .setAction(SniperService.ACTION_PREWARM)
            .putExtra(SniperService.EXTRA_EVENT_KEY, key)
            .putExtra(SniperService.EXTRA_FIRE_AT, trigger.fireAt.toEpochMilli())
            .putExtra(SniperService.EXTRA_TARGET_AT, trigger.at.toEpochMilli())
        try {
            ContextCompat.startForegroundService(context, svc)
        } catch (e: Exception) {
            // 前台服务被限制时至少把页面拉起来
            try {
                context.startActivity(
                    Intent(context, com.lcs.jdsniper.ui.SniperActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(SniperService.EXTRA_FIRE_AT, trigger.fireAt.toEpochMilli())
                        .putExtra(SniperService.EXTRA_EVENT_KEY, key)
                )
            } catch (ignored: Exception) {
                // 无悬浮窗权限时系统会拦，用户手动打开即可
            }
        }
    }
}
