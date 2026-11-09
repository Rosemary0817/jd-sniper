package com.lcs.jdsniper.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.lcs.jdsniper.R
import com.lcs.jdsniper.ui.SniperActivity

object Notifier {

    const val CHANNEL_ID = "jd_sniper_high"
    const val CHANNEL_NAME = "抢购提醒"
    const val FGS_ID = 2001
    const val SUCCESS_ID = 2002

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        ch.description = "到点唤醒抢购台，抢到后提醒付款"
        ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        ch.enableVibration(true)
        ch.setShowBadge(true)
        mgr.createNotificationChannel(ch)
    }

    private fun openIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx,
        3001,
        Intent(ctx, SniperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun prewarm(ctx: Context, eventKey: String, fireAt: Long): Notification {
        val seconds = ((fireAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("抢购台已布防")
            .setContentText("$eventKey · 约 ${seconds}秒后开火")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openIntent(ctx))
            .setFullScreenIntent(openIntent(ctx), true)
            .build()
    }

    fun success(ctx: Context, text: String): Notification {
        val pi = openIntent(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("订单已提交，去付款")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
    }

    fun notifySuccess(ctx: Context, text: String) {
        ensureChannel(ctx)
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            mgr.notify(SUCCESS_ID, success(ctx, text))
        } catch (e: Exception) {
            // 权限被拒时静默
        }
    }

    fun vibrate(ctx: Context) {
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180, 120, 400), -1))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(longArrayOf(0, 180, 120, 180, 120, 400), -1)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}
