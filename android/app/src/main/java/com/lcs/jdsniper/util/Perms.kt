package com.lcs.jdsniper.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** 权限探针 + 跳转。国产 ROM 上这四项缺一个，闹钟就未必唤得起。 */
object Perms {

    fun exactAlarm(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        return try {
            am.canScheduleExactAlarms()
        } catch (e: Exception) {
            false
        }
    }

    fun battery(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        false
    }

    fun overlay(ctx: Context): Boolean = try {
        Settings.canDrawOverlays(ctx)
    } catch (e: Exception) {
        false
    }

    fun notifications(ctx: Context): Boolean = try {
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    } catch (e: Exception) {
        false
    }

    fun allOk(ctx: Context): Boolean =
        exactAlarm(ctx) && battery(ctx) && overlay(ctx) && notifications(ctx)

    fun openExact(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (launch(ctx, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri(ctx)))) return
        }
        launch(ctx, Intent(Settings.ACTION_SETTINGS))
    }

    fun openBattery(ctx: Context) {
        if (launch(ctx, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri(ctx)))) return
        if (launch(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetail(ctx)
    }

    fun openOverlay(ctx: Context) {
        if (launch(ctx, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri(ctx)))) return
        openAppDetail(ctx)
    }

    fun openNotif(ctx: Context) {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        if (launch(ctx, i)) return
        openAppDetail(ctx)
    }

    fun openAppDetail(ctx: Context) {
        launch(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri(ctx))
        )
    }

    private fun pkgUri(ctx: Context): Uri = Uri.parse("package:${ctx.packageName}")

    private fun launch(ctx: Context, i: Intent): Boolean = try {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        false
    }
}
