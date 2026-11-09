package com.lcs.jdsniper.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lcs.jdsniper.data.ConfigStore
import java.time.Instant

/** 开机 / 更新 / 改时间 / 改时区后重排闹钟，避免“重启后就不响了”。 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val store = ConfigStore(context)
        val cfg = store.load()
        val action = intent?.action ?: ""

        if (action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            store.saveOffset(0L, 0L)
        }
        AlarmScheduler.scheduleNext(context, cfg, Instant.now())
    }
}
