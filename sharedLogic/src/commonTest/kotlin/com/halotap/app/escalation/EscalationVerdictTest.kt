package com.halotap.app.escalation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The retry and deduplication policy behind both watches' escalation.
 *
 * This logic used to live inside the Wear escalation worker's `doWork`, where nothing tested
 * it, and both of the things it decides are listed in SECURITY.md as reportable bugs: an
 * alert that never goes out, and a contact alerted twice.
 */
class EscalationVerdictTest {

    private fun attempt(id: String, outcome: EscalationOutcome) = EscalationAttempt(id, outcome)

    private fun verdict(
        vararg outcomes: EscalationOutcome,
        runAttemptCount: Int = 0,
        maxRetries: Int = EscalationVerdicts.DEFAULT_MAX_RETRIES,
    ) = EscalationVerdicts.verdictFor(
        attempts = outcomes.mapIndexed { index, outcome -> attempt("channel$index", outcome) },
        runAttemptCount = runAttemptCount,
        maxRetries = maxRetries,
    )

    // MARK: - Deduplication

    @Test
    fun aBreachIsNotEscalatedTwice() {
        // The case the token exists for: the running app and a fired alarm both reach the
        // worker for one breach, and the second must be dropped rather than re-texting.
        assertTrue(EscalationVerdicts.isAlreadyHandled(token = 5_000, handledToken = 5_000))
    }

    @Test
    fun aFreshBreachIsNotMistakenForTheLastOne() {
        assertFalse(EscalationVerdicts.isAlreadyHandled(token = 6_000, handledToken = 5_000))
    }

    @Test
    fun noOpenBreachCanNeverCountAsHandled() {
        // Zero means "nothing is open". Treating it as handled would make a breach carrying
        // no token silently skip its own escalation.
        assertFalse(EscalationVerdicts.isAlreadyHandled(token = 0, handledToken = 0))
    }

    // MARK: - When to stop

    @Test
    fun aConfirmedDeliveryIsDone() {
        assertEquals(EscalationVerdict.DONE, verdict(EscalationOutcome.Delivered))
    }

    @Test
    fun oneDeliveryIsEnoughEvenAlongsideAFailure() {
        assertEquals(
            EscalationVerdict.DONE,
            verdict(EscalationOutcome.Failed("no service"), EscalationOutcome.Delivered),
        )
    }

    @Test
    fun anUnconfirmedHandOffIsNotRetried() {
        // The important one. Unconfirmed means a channel gave the alert to the system and
        // the system said nothing back -- a text queued behind no signal, or a watchOS call
        // placed through openSystemURL, which reports nothing by design. Retrying would not
        // rescue a send that is already on its way; it would queue a second text to the same
        // person and dial them again.
        assertEquals(
            EscalationVerdict.DONE,
            verdict(EscalationOutcome.Unconfirmed("handed to the radio")),
        )
    }

    @Test
    fun nothingConfiguredIsNotWorthRetrying() {
        // A user who has not finished setup, not a transport failure. Retrying forever would
        // drain the battery for nobody's benefit.
        assertEquals(
            EscalationVerdict.DONE,
            verdict(EscalationOutcome.NotConfigured, EscalationOutcome.NotConfigured),
        )
    }

    @Test
    fun aBreachWithNoChannelsAtAllIsDone() {
        assertEquals(EscalationVerdict.DONE, verdict())
    }

    // MARK: - The ladder

    @Test
    fun aRealFailureIsRetried() {
        // Usually no signal, which is exactly what WorkManager's backoff is for.
        assertEquals(EscalationVerdict.RETRY, verdict(EscalationOutcome.Failed("no service")))
    }

    @Test
    fun aFailureAlongsideAnUnconfiguredChannelIsStillRetried() {
        assertEquals(
            EscalationVerdict.RETRY,
            verdict(EscalationOutcome.NotConfigured, EscalationOutcome.Failed("no service")),
        )
    }

    @Test
    fun theLadderGivesUpOnceTheRetriesAreSpent() {
        assertEquals(
            EscalationVerdict.GIVE_UP,
            verdict(EscalationOutcome.Failed("no service"), runAttemptCount = 5, maxRetries = 5),
        )
    }

    @Test
    fun theLastRetryIsStillTaken() {
        // Off-by-one guard: runAttemptCount is zero on the first run, so the final retry
        // happens at maxRetries - 1 and not before it.
        assertEquals(
            EscalationVerdict.RETRY,
            verdict(EscalationOutcome.Failed("no service"), runAttemptCount = 4, maxRetries = 5),
        )
    }

    @Test
    fun aDeliveryIsNeverOverriddenByAnExhaustedLadder() {
        // Running out of retries must not turn a delivered alert into a reported failure.
        assertEquals(
            EscalationVerdict.DONE,
            verdict(EscalationOutcome.Delivered, runAttemptCount = 99, maxRetries = 5),
        )
    }
}
