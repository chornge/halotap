package com.halotap.app.wear.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.halotap.app.PowerLoss
import com.halotap.app.settings.AndroidArmedMarkerStore
import com.halotap.app.wear.MainActivity
import com.halotap.app.wear.R

/**
 * Tells the user when the watch died with a session still open.
 *
 * A watch running out of battery mid-walk is the most ordinary failure this app has, and it
 * used to be completely silent: no alert went out, and on charging the app came back to Idle
 * with nothing to say it had stopped watching. Someone could believe they were covered for an
 * entire walk and never learn otherwise.
 *
 * It deliberately does **not** escalate. A boot can happen hours later, and texting somebody's
 * emergency contacts about a walk that ended last night is how an app teaches people to ignore
 * it. Scheduled alarms do not survive a reboot either, so there is nothing left to resume —
 * only something to admit.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val markers = AndroidArmedMarkerStore(context.applicationContext)
        val tokens = BreachTokens(context.applicationContext)

        val interrupted = PowerLoss.interruptedSessionFound(
            armedSinceMillis = markers.load(),
            nowMillis = SystemClock.elapsedRealtime(),
            openBreachToken = tokens.openToken(),
            handledBreachToken = tokens.handledToken(),
        )

        if (interrupted) {
            Log.i(TAG, "a session was open when the watch lost power")
            notify(context.applicationContext)
        }

        // Cleared either way, so the notice is shown once rather than at every boot. The
        // marker would be rejected as pre-reboot anyway; this just stops it lingering.
        markers.save(sinceMillis = null)
        tokens.clearOpen()
    }

    private fun notify(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Watch interrupted", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Shown when the watch lost power while on watch"
                },
        )

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Halotap stopped watching")
                .setContentText("The watch lost power while on watch. No alert was sent.")
                .setStyle(
                    Notification.BigTextStyle().bigText(
                        "The watch lost power while you were on watch, so no alert was sent. " +
                            "If you still need to be covered, open Halotap and hold again.",
                    ),
                )
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
    }

    private companion object {
        const val TAG = "HalotapBoot"
        const val CHANNEL = "halotap.interrupted"
        const val NOTIFICATION_ID = 0x4854 // "HT"
    }
}
