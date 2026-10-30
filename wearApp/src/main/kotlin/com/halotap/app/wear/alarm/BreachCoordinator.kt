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

    /** A challenge has opened. Arrange for the breach to happen even if this app does not. */
    fun challengeOpened(deadlineElapsedRealtime: Long) {
        EscalationWorker.reset(context)
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

    /** What the worker reported, for the breach screen. Null while it is still running. */
    fun summaries(): Flow<String?> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(EscalationWorker.UNIQUE_NAME)
            .map { infos ->
                val info = infos.lastOrNull() ?: return@map null
                when (info.state) {
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED ->
                        info.outputData.getString(EscalationWorker.KEY_SUMMARY)
                    else -> null
                }
            }
}
