package com.halotap.app.wear.alarm

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.halotap.app.BreachReason
import com.halotap.app.escalation.AndroidHttpPoster
import com.halotap.app.escalation.BreachContext
import com.halotap.app.escalation.BreachMessages
import com.halotap.app.escalation.EscalationOutcome
import com.halotap.app.escalation.EscalationVerdict
import com.halotap.app.escalation.EscalationVerdicts
import com.halotap.app.escalation.Escalator
import com.halotap.app.escalation.HttpEscalationChannel
import com.halotap.app.escalation.SmsEscalationChannel
import com.halotap.app.settings.AlertRecord
import com.halotap.app.settings.AndroidArmedMarkerStore
import com.halotap.app.settings.ArmedMarkerStore
import com.halotap.app.settings.KeystoreSettingsStore
import com.halotap.app.settings.SettingsStore

/**
 * Sends the alert, out of the UI's lifetime entirely.
 *
 * This is the only path that escalates. Whether the breach was noticed by the running app or
 * by an alarm firing into a dead process, both routes end here, enqueued under one unique
 * name with [ExistingWorkPolicy.KEEP] so a breach can never double-text a contact.
 */
class EscalationWorker internal constructor(
    context: Context,
    parameters: WorkerParameters,
    private val settings: SettingsStore,
    private val tokens: BreachTokens,
    private val markers: ArmedMarkerStore,
) : CoroutineWorker(context, parameters) {

    /**
     * The constructor WorkManager uses. It resolves workers by reflecting for exactly this
     * signature, so it has to exist as its own constructor rather than as default arguments.
     *
     * The stores are injectable above only so a test can point this at its own rather than at
     * the ones holding the user's real PIN and contacts -- running the device suite on a watch
     * somebody actually wears must not wipe their setup.
     */
    constructor(context: Context, parameters: WorkerParameters) : this(
        context = context,
        parameters = parameters,
        settings = KeystoreSettingsStore(context.applicationContext),
        tokens = BreachTokens(context.applicationContext),
        markers = AndroidArmedMarkerStore(context.applicationContext),
    )

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> BreachReason.entries.firstOrNull { it.name == name } }
            ?: BreachReason.DEADLINE_PASSED

        // KEEP deduplicates against pending work only, not completed work, so the app path
        // and the alarm path could both send for one breach -- and did, before this. Every
        // breach carries its challenge deadline as a token, and that token is recorded here
        // once it has been handled. The rule itself is shared and tested in :sharedLogic.
        val token = inputData.getLong(KEY_TOKEN, 0L)
        if (EscalationVerdicts.isAlreadyHandled(token, tokens.handledToken())) {
            Log.i(TAG, "escalation skipped: breach $token was already handled")
            return Result.success()
        }

        val configured = settings.load()
        // Wall clock, not elapsed realtime: this one is reported rather than compared.
        // See BreachContext.occurredAtMillis.
        val occurredAtMillis = System.currentTimeMillis()

        // The hold is over however this ended, so the marker must not survive to re-arm the
        // app on its next launch.
        markers.save(sinceMillis = null)

        val escalator = Escalator(
            listOf(
                SmsEscalationChannel(applicationContext),
                HttpEscalationChannel(poster = AndroidHttpPoster(), config = configured.http),
            ),
        )

        val attempts = escalator.escalate(
            BreachContext(
                reason = reason,
                occurredAtMillis = occurredAtMillis,
                contacts = configured.contacts,
                message = BreachMessages.default(reason),
            ),
        )

        val verdict = EscalationVerdicts.verdictFor(attempts, runAttemptCount)

        // Marked only once nothing further is coming. Marking it before a retry -- which is
        // what this did -- made the retry skip itself on the check above and return success
        // without sending anything, so the whole ladder below was unreachable and a breach
        // that failed on no signal was never actually tried again.
        if (token != 0L && verdict != EscalationVerdict.RETRY) tokens.markHandled(token)

        val summary = attempts.joinToString(", ") { "${it.channelId}=${it.outcome.label()}" }

        // Written whatever the outcome, including "nothing configured": afterwards the user
        // needs to know an alert was raised and how it went, and standing down must not be
        // able to erase that. A retry overwrites this with its own, later, result.
        settings.saveLastAlert(
            AlertRecord(occurredAtMillis = occurredAtMillis, reason = reason, detail = summary),
        )

        // Channel ids and outcomes only: no phone numbers, no message body, nothing about
        // who was contacted. Enough to tell whether an alert went out, and no more.
        Log.i(TAG, "escalation finished: reason=$reason, $summary")
        // The token travels back out so the breach screen can tell this run's result
        // from a previous breach's, which shares the unique work name.
        val output = workDataOf(
            KEY_SUMMARY to summary,
            KEY_DELIVERED to escalator.succeeded(attempts),
            KEY_TOKEN to token,
        )

        return when (verdict) {
            EscalationVerdict.DONE -> Result.success(output)
            // Usually no signal. Retrying with WorkManager's backoff is the entire reason the
            // send lives in a worker rather than in the receiver.
            EscalationVerdict.RETRY -> Result.retry()
            EscalationVerdict.GIVE_UP -> Result.failure(output)
        }
    }

    private fun EscalationOutcome.label(): String = when (this) {
        is EscalationOutcome.Delivered -> "delivered"
        is EscalationOutcome.Unconfirmed -> "unconfirmed"
        is EscalationOutcome.NotConfigured -> "not-configured"
        is EscalationOutcome.Failed -> "failed"
    }

    companion object {
        const val TAG = "HalotapEscalation"
        const val UNIQUE_NAME = "halotap-escalation"
        const val KEY_REASON = "reason"
        const val KEY_TOKEN = "token"
        const val KEY_SUMMARY = "summary"
        const val KEY_DELIVERED = "delivered"

        /**
         * KEEP, not REPLACE: the running app and a fired alarm may both arrive here for the
         * same breach, and the second one must be dropped rather than sending a second text.
         */
        fun enqueue(context: Context, reason: BreachReason, token: Long) {
            val request = OneTimeWorkRequestBuilder<EscalationWorker>()
                .setInputData(
                    Data.Builder()
                        .putString(KEY_REASON, reason.name)
                        .putLong(KEY_TOKEN, token)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Clears a completed run so the next breach is not deduplicated against it. */
        fun reset(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
