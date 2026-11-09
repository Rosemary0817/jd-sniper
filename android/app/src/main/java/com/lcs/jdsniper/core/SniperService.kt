package com.lcs.jdsniper.core

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.lcs.jdsniper.ui.SniperActivity

/**
 * 只在“该醒了”这一刻短暂存在：拿 WakeLock、发全屏通知、把抢购台拉到前台。
 * 真正的抢购逻辑在 SniperActivity + autopilot.js，Service 不碰 WebView。
 */
class SniperService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wake: PowerManager.WakeLock? = null
    private var stopRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }

        // 暂停布防时，迟到的闹钟不再拉起界面
        if (com.lcs.jdsniper.data.ConfigStore(this).paused) {
            shutdown()
            return START_NOT_STICKY
        }

        val key = intent?.getStringExtra(EXTRA_EVENT_KEY) ?: "manual"
        val fireAt = intent?.getLongExtra(EXTRA_FIRE_AT, 0L) ?: 0L
        val targetAt = intent?.getLongExtra(EXTRA_TARGET_AT, fireAt) ?: fireAt

        val notif = Notifier.prewarm(this, key, fireAt)
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    Notifier.FGS_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(Notifier.FGS_ID, notif)
            }
        } catch (e: Exception) {
            // 前台服务被限制时降级为普通通知 + 直接拉页面
            Notifier.notifySuccess(this, "抢购台已布防：$key")
        }

        acquireWake()
        launchSniper(key, fireAt, targetAt)
        scheduleShutdown(maxOf(targetAt, fireAt))
        return START_STICKY
    }

    private fun launchSniper(key: String, fireAt: Long, targetAt: Long) {
        val i = Intent(this, SniperActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_EVENT_KEY, key)
            .putExtra(EXTRA_FIRE_AT, fireAt)
            .putExtra(EXTRA_TARGET_AT, targetAt)
        try {
            startActivity(i)
        } catch (e: Exception) {
            // 后台启动 Activity 被拦时，全屏通知就是兜底
        }
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jd_sniper:fire")
            wl.setReferenceCounted(false)
            wl.acquire(25 * 60 * 1000L)
            wake = wl
        } catch (e: Exception) {
            wake = null
        }
    }

    private fun scheduleShutdown(targetAt: Long) {
        stopRunnable?.let { handler.removeCallbacks(it) }
        val delay = maxOf(120_000L, targetAt + 5 * 60 * 1000L - System.currentTimeMillis())
        val r = Runnable { shutdown() }
        stopRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun shutdown() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        stopRunnable = null
        try {
            wake?.release()
        } catch (e: Exception) {
            // ignore
        }
        wake = null
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // ignore
        }
        stopSelf()
    }

    override fun onDestroy() {
        stopRunnable?.let { handler.removeCallbacks(it) }
        try {
            wake?.release()
        } catch (e: Exception) {
            // ignore
        }
        wake = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_PREWARM = "com.lcs.jdsniper.PREWARM"
        const val ACTION_STOP = "com.lcs.jdsniper.STOP"
        const val EXTRA_EVENT_KEY = "event_key"
        const val EXTRA_FIRE_AT = "fire_at"
        const val EXTRA_TARGET_AT = "target_at"
    }
}
