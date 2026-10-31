package com.halotap.app.wear.alarm

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.halotap.app.PowerLoss
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The token lifecycle a session drives, end to end through the real preference store.
 *
 * `PowerLossTest` in commonTest proves the rule that turns two tokens into "the watch died
 * mid-walk". This proves the coordinator actually leaves the tokens in the states that rule is
 * written against -- which is what decides whether a user is told their watch stopped watching
 * or told nothing at all.
 *
 * The alarm side is deliberately not asserted on: `AlarmManager` has no readable schedule, so
 * anything claimed about it here would be a restatement of the call, not a test of it.
 */
class BreachCoordinatorTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tokens() = BreachTokens(context)

    @BeforeTest
    @AfterTest
    fun clearTokens() {
        context.getSharedPreferences(BreachTokens.PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun coordinator() = BreachCoordinator(context)

    private fun interruptedSessionFound(armedSinceMillis: Long?, nowMillis: Long = 60_000) =
        PowerLoss.interruptedSessionFound(
            armedSinceMillis = armedSinceMillis,
            nowMillis = nowMillis,
            openBreachToken = tokens().openToken(),
            handledBreachToken = tokens().handledToken(),
        )

    @Test
    fun openingAChallengeRecordsItsDeadlineAsTheBreachIdentity() {
        // The deadline doubles as the token, so the app path and the alarm path agree on which
        // breach they are reporting without anything having to be generated.
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)

        assertEquals(40_000L, tokens().openToken())
        assertEquals(0L, tokens().handledToken())
    }

    @Test
    fun anOpenChallengeThatLostPowerIsReportedAsInterrupted() {
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)

        assertTrue(
            interruptedSessionFound(armedSinceMillis = 30_000),
            "a watch that died mid-challenge has to admit it stopped watching",
        )
    }

    @Test
    fun anOrdinaryDisarmLeavesNothingToAnnounceOnTheNextBoot() {
        // Without the clear in standDown, the next boot would read an open token with nothing
        // handled and tell the user it had stopped watching after a perfectly normal walk.
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)
        coordinator().standDown()

        assertEquals(0L, tokens().openToken())
        assertFalse(interruptedSessionFound(armedSinceMillis = null))
    }

    @Test
    fun clearingASentAlertAlsoStopsTheNextBootCryingPowerLoss() {
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)
        tokens().markHandled(token = 40_000)

        coordinator().alertCleared()

        assertEquals(0L, tokens().openToken())
        assertFalse(interruptedSessionFound(armedSinceMillis = null))
    }

    @Test
    fun anAlertAlreadyHandledIsNotRereportedAsAPowerLoss() {
        // The alert went out, so the next boot owes the user nothing: they were covered.
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)
        tokens().markHandled(token = 40_000)

        assertFalse(interruptedSessionFound(armedSinceMillis = 30_000))
    }

    @Test
    fun armingAgainReplacesTheOpenTokenAndKeepsTheHandledOne() {
        // The case that made challengeOpened stop cancelling the escalation work: a send that
        // failed on no signal is still retrying here, and must not be thrown away just
        // because the user started another walk.
        coordinator().challengeOpened(deadlineElapsedRealtime = 40_000)
        tokens().markHandled(token = 40_000)

        coordinator().challengeOpened(deadlineElapsedRealtime = 90_000)

        assertEquals(90_000L, tokens().openToken())
        assertEquals(40_000L, tokens().handledToken())
    }
}
