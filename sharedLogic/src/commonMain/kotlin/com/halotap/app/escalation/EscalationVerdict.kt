package com.halotap.app.escalation

/** What a caller that is able to try again should do with a finished set of attempts. */
enum class EscalationVerdict {

    /** Nothing more to do: something got out, or there was nothing configured to send. */
    DONE,

    /** Nothing got out, and it is worth trying again. */
    RETRY,

    /** Nothing got out and the retries are spent. Report it and stop. */
    GIVE_UP,
}

/**
 * Whether a breach has already been dealt with, and whether a failed send is worth repeating.
 *
 * Pulled out of the Wear OS escalation worker so it can be tested without an Android runtime.
 * It lived inside `doWork`, where it was the least-covered and most consequential code in the
 * app: a wrong answer here is either an alert that never goes out or a contact texted twice,
 * both of which SECURITY.md calls a reportable bug. watchOS answers the same two questions on
 * its own relaunch path, so the policy belongs in common code rather than in one platform.
 */
object EscalationVerdicts {

    /**
     * How many times a failed escalation is retried after its first run.
     *
     * The count is deliberately the number of *retries*, not of total runs: the Android
     * `runAttemptCount` this is compared against is zero on the first run, so naming it
     * anything else would make the comparison read as off by one.
     */
    const val DEFAULT_MAX_RETRIES: Int = 5

    /**
     * True when this breach has already been escalated and must not be escalated again.
     *
     * Both watches can notice one breach twice — the running app sees its own deadline pass
     * while a scheduled alarm fires into the same moment, and on watchOS a relaunch can
     * arrive alongside the view that reports the breach. Deduplicating on a pending job is
     * not enough, because by then the first one may have finished, so the breach carries its
     * own identity and that identity is recorded once it is handled.
     *
     * A [token] of zero means "no breach is open", which can never have been handled.
     */
    fun isAlreadyHandled(token: Long, handledToken: Long): Boolean =
        token != 0L && token == handledToken

    /**
     * What to do once every channel has reported.
     *
     * [EscalationOutcome.Unconfirmed] counts as done, not as a failure to retry. It means a
     * channel handed the alert to the system and the system said nothing back — a text sitting
     * in the radio queue with no signal yet, or a watchOS call placed through `openSystemURL`,
     * which reports nothing by design. The send is very likely to complete on its own, so
     * retrying would not rescue anything; it would queue a second text to the same person and
     * dial them again, and alerting someone twice is its own harm in an app people are asked
     * to trust. [EscalationOutcome.Failed] is the outcome worth repeating: that is the channel
     * saying the attempt is over and got nowhere.
     *
     * [runAttemptCount] is zero on the first run, matching Android's `ListenableWorker`.
     */
    fun verdictFor(
        attempts: List<EscalationAttempt>,
        runAttemptCount: Int,
        maxRetries: Int = DEFAULT_MAX_RETRIES,
    ): EscalationVerdict = when {
        attempts.any {
            it.outcome is EscalationOutcome.Delivered || it.outcome is EscalationOutcome.Unconfirmed
        } -> EscalationVerdict.DONE

        // Nothing configured is not a failure to retry; it is a user who has not finished
        // setup, and retrying forever would drain the battery for no one's benefit. An empty
        // list satisfies this too, which is the right answer for a breach with no channels.
        attempts.all { it.outcome is EscalationOutcome.NotConfigured } -> EscalationVerdict.DONE

        runAttemptCount < maxRetries -> EscalationVerdict.RETRY

        else -> EscalationVerdict.GIVE_UP
    }
}
