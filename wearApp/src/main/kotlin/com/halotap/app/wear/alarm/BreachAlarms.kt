package com.halotap.app.wear.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.halotap.app.BreachReason

/**
 * Schedules the breach so it happens whether or not the app is still alive.
 *
 * Until this existed, escalation ran inside the composition: an attacker who powered the watch
 * off, or simply a phone call that tore the activity down mid-challenge, meant no alert was
 * ever sent. An alarm app whose alarm dies with the app is not an alarm.
 *
 * `ELAPSED_REALTIME_WAKEUP` matches the monotonic clock the session already uses, so the
 * deadline cannot be moved by changing the device time, and `setExactAndAllowWhileIdle` is
 * what gets it through Doze. Ten seconds is far too short for WorkManager, whose minimum
 * latency is measured in minutes; WorkManager does the sending afterwards, where retries and
 * surviving process death are what matter.
 */
class BreachAlarms(private val context: Context) {

    fun schedule(deadlineElapsedRealtime: Long, reason: BreachReason) {
        alarmManager()?.let { manager ->
            val intent = pendingIntent(reason, deadlineElapsedRealtime)
            if (canScheduleExact(manager)) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    deadlineElapsedRealtime,
                    intent,
                )
            } else {
                // Exact alarms can be refused by the user on API 31+. Inexact still fires,
                // just late, and a late alarm beats none at all. The app surfaces this in
                // setup rather than failing silently here.
                manager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    deadlineElapsedRealtime,
                    intent,
                )
            }
        }
    }

    /** Called the moment a session stops being able to breach, which must be every such path. */
    fun cancel() {
        alarmManager()?.cancel(pendingIntent(BreachReason.DEADLINE_PASSED, token = 0L))
    }

    fun canScheduleExact(): Boolean = alarmManager()?.let(::canScheduleExact) ?: false

    private fun canScheduleExact(manager: AlarmManager): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true

    private fun alarmManager(): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    // The request code is constant so that cancel() addresses the same alarm schedule()
    // created; FLAG_UPDATE_CURRENT then replaces rather than stacking a second one.
    private fun pendingIntent(reason: BreachReason, token: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, BreachReceiver::class.java)
                .setAction(BreachReceiver.ACTION_BREACH)
                .putExtra(BreachReceiver.EXTRA_REASON, reason.name)
                .putExtra(BreachReceiver.EXTRA_TOKEN, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val REQUEST_CODE = 0x4D57 // "MW"
    }
}
