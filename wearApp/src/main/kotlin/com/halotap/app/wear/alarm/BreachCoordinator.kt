package com.halotap.app.wear.alarm

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.halotap.app.BreachReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The single place the UI touches escalation.
 *
 * The screen no longer sends anything itself. It says when a challenge opens, when it is
 * answered, and when it is lost; everything else happens in a worker that outlives it. That
 * is the whole point: the previous design sent the alert from inside the composition, so
 * powering the watch off during the challenge meant no alert at all.
 */
class BreachCoordinator(private val context: Context) {

    private val alarms = BreachAlarms(context)

    // The deadline doubles as the breach's identity, so the app path and the alarm path
    // agree on which breach they are reporting without generating anything.
    private val tokens = BreachTokens(context)

    /**
     * A challenge has opened. Arrange for the breach to happen even if this app does not.
     *
     * Deliberately does **not** cancel the escalation work, which it used to. A send that
     * failed on no signal is retried with WorkManager's backoff, so work for the *previous*
     * breach can still be pending here -- and arming again within that window would have
     * thrown away a retry for an alert that never got out. The work is enqueued under KEEP,
     * which already ignores finished work, so nothing had to be cleared for the next breach
     * to enqueue; [summaries] is what keeps a finished run from being read as this one's.
     */
    fun challengeOpened(deadlineElapsedRealtime: Long) {
        tokens.open(deadlineElapsedRealtime)
        alarms.schedule(deadlineElapsedRealtime, BreachReason.DEADLINE_PASSED)
    }

    /** Disarmed, or never challenged. Nothing should fire. */
    fun standDown() {
        alarms.cancel()
        EscalationWorker.reset(context)
        // Must clear, or the next boot would see an open token with nothing handled and
        // announce a power loss after an ordinary disarm.
        tokens.clearOpen()
    }

    /**
     * The user cleared an alert that had already been sent.
     *
     * Only the token is cleared, so the next boot does not read an open breach as a power
     * loss. The worker is deliberately left running: it may still be waiting on the radio
     * to confirm a text, and cancelling it would abandon a message mid-send.
     */
    fun alertCleared() {
        tokens.clearOpen()
    }

    /**
     * Breached while the app happened to be alive — a wrong code, or the duress code.
     *
     * Goes through the same worker as the alarm would, under the same unique name, so the
     * two routes cannot both send.
     */
    fun breachNow(reason: BreachReason) {
        alarms.cancel()
        EscalationWorker.enqueue(context, reason, tokens.openToken())
    }

    /** True when the system will honour the ten-second deadline exactly. */
    fun canScheduleExact(): Boolean = alarms.canScheduleExact()

    /**
     * What the worker reported, for the breach screen. Null while it is still running.
     *
     * Matched on the token rather than taking whatever finished last. The unique work name is
     * reused by every breach, and finished runs survive until WorkManager prunes them, so
     * without this the alert screen could show the previous breach's result as though it were
     * this one's -- telling a user their contacts had been reached when the current attempt
     * was still in flight.
     */
    fun summaries(): Flow<String?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(EscalationWorker.UNIQUE_NAME)
            .map { infos ->
                val open = tokens.openToken()
                infos.firstNotNullOfOrNull { info ->
                    when {
                        info.state != WorkInfo.State.SUCCEEDED &&
                            info.state != WorkInfo.State.FAILED -> null

                        info.outputData.getLong(EscalationWorker.KEY_TOKEN, 0L) != open -> null

                        else -> info.outputData.getString(EscalationWorker.KEY_SUMMARY)
                    }
                }
            }
}
