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
import com.halotap.app.escalation.Escalator
import com.halotap.app.escalation.HttpEscalationChannel
import com.halotap.app.escalation.SmsEscalationChannel
import com.halotap.app.settings.AndroidArmedMarkerStore
import com.halotap.app.settings.AlertRecord
import com.halotap.app.settings.KeystoreSettingsStore

/**
 * Sends the alert, out of the UI's lifetime entirely.
 *
 * This is the only path that escalates. Whether the breach was noticed by the running app or
 * by an alarm firing into a dead process, both routes end here, enqueued under one unique
 * name with [ExistingWorkPolicy.KEEP] so a breach can never double-text a contact.
 */
class EscalationWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString(KEY_REASON)
            ?.let { name -> BreachReason.entries.firstOrNull { it.name == name } }
            ?: BreachReason.DEADLINE_PASSED

        // KEEP deduplicates against pending work only, not completed work, so the app path
        // and the alarm path could both send for one breach -- and did, before this. Every
        // breach carries its challenge deadline as a token, and that token is recorded here
        // once it has been handled.
        val token = inputData.getLong(KEY_TOKEN, 0L)
        val tokens = BreachTokens(applicationContext)
        if (token != 0L && tokens.handledToken() == token) {
            Log.i(TAG, "escalation skipped: breach $token was already handled")
            return Result.success()
        }

        val store = KeystoreSettingsStore(applicationContext)
        val settings = store.load()
        val occurredAtMillis = System.currentTimeMillis()

        // The hold is over however this ended, so the marker must not survive to re-arm the
        // app on its next launch.
        AndroidArmedMarkerStore(applicationContext).save(sinceMillis = null)

        val escalator = Escalator(
            listOf(
                SmsEscalationChannel(applicationContext),
                HttpEscalationChannel(poster = AndroidHttpPoster(), config = settings.http),
            ),
        )

        val attempts = escalator.escalate(
            BreachContext(
                reason = reason,
                occurredAtMillis = occurredAtMillis,
                contacts = settings.contacts,
                message = BreachMessages.default(reason),
            ),
        )

        if (token != 0L) tokens.markHandled(token)

        val summary = attempts.joinToString(", ") { "${it.channelId}=${it.outcome.label()}" }

        // Written whatever the outcome, including "nothing configured": afterwards the user
        // needs to know an alert was raised and how it went, and standing down must not be
        // able to erase that. A retry overwrites this with its own, later, result.
        store.saveLastAlert(
            AlertRecord(occurredAtMillis = occurredAtMillis, reason = reason, detail = summary),
        )

        // Channel ids and outcomes only: no phone numbers, no message body, nothing about
        // who was contacted. Enough to tell whether an alert went out, and no more.
        Log.i(TAG, "escalation finished: reason=$reason, $summary")
        val output = workDataOf(KEY_SUMMARY to summary, KEY_DELIVERED to escalator.succeeded(attempts))

        return when {
            escalator.succeeded(attempts) -> Result.success(output)
            // Nothing configured is not a failure to retry; it is a user who has not finished
            // setup, and retrying forever would drain the battery for no one's benefit.
            attempts.all { it.outcome is EscalationOutcome.NotConfigured } -> Result.success(output)
            // A failure here is usually no signal. Retrying with WorkManager's backoff is the
            // entire reason the send lives in a worker rather than in the receiver.
            runAttemptCount < MAX_ATTEMPTS -> Result.retry()
            else -> Result.failure(output)
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
        private const val MAX_ATTEMPTS = 5

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
