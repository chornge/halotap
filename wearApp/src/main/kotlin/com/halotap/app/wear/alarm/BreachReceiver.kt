package com.halotap.app.wear.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.halotap.app.BreachReason

/**
 * Receives the breach alarm and hands the sending to WorkManager.
 *
 * It does not escalate here. A receiver gets about ten seconds before the system may kill the
 * process, which is not enough for an SMS to several contacts plus an HTTPS request, and
 * nothing in a receiver survives being killed part-way. WorkManager owns the send precisely
 * because it retries and outlives the process.
 */
class BreachReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BREACH) return

        val reason = intent.getStringExtra(EXTRA_REASON)
            ?.let { name -> BreachReason.entries.firstOrNull { it.name == name } }
            ?: BreachReason.DEADLINE_PASSED

        EscalationWorker.enqueue(context, reason, intent.getLongExtra(EXTRA_TOKEN, 0L))
    }

    companion object {
        const val ACTION_BREACH = "com.halotap.app.wear.BREACH"
        const val EXTRA_REASON = "reason"
        const val EXTRA_TOKEN = "token"
    }
}
