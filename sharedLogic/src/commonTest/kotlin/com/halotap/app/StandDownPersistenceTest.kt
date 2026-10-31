package com.halotap.app

import com.halotap.app.settings.InMemoryStandDownPenaltyStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * That the stand-down delay outlives the process it was earned in.
 *
 * The penalty used to be two fields on [StandbySession], which made it worth nothing: the
 * delay guarding the one record that an alert went out could be reset by force-quitting the
 * app, because the next launch built a fresh session with the counter at zero. Four digits is
 * ten thousand guesses, so the doubling has to actually hold.
 */
class StandDownPersistenceTest {

    private val secret = DisarmSecret.ofPin("1234")

    private fun breached(penalties: InMemoryStandDownPenaltyStore): StandbySession =
        StandbySession(secret = secret, penalties = penalties).also { session ->
            session.hold(nowMillis = 0)
            session.release(nowMillis = 100)
            // Two wrong codes inside the window, which is what breaches on the secret.
            session.submit(entry = "9999", nowMillis = 200)
            session.submit(entry = "9999", nowMillis = 300)
            assertIs<StandbyState.Breached>(session.state)
        }

    @Test
    fun aWrongStandDownCodeSurvivesTheSessionThatEarnedIt() {
        val penalties = InMemoryStandDownPenaltyStore()
        val first = breached(penalties)

        first.clearBreach(entry = "0000", nowMillis = 1_000)
        val delayBefore = first.standDownDelayMillis(nowMillis = 1_000)
        assertEquals(StandbySession.FIRST_STAND_DOWN_PENALTY_MILLIS, delayBefore)

        // The app is killed and reopened: a brand new session over the same store.
        val reopened = breached(penalties)
        assertEquals(
            delayBefore,
            reopened.standDownDelayMillis(nowMillis = 1_000),
            "killing the app must not hand back a fresh attempt",
        )
    }

    @Test
    fun theLadderKeepsClimbingAcrossRelaunches() {
        val penalties = InMemoryStandDownPenaltyStore()

        breached(penalties).clearBreach(entry = "0000", nowMillis = 1_000)
        val afterOne = breached(penalties).standDownDelayMillis(nowMillis = 1_000)

        // A second wrong code, in a session that never saw the first one.
        breached(penalties).clearBreach(entry = "0000", nowMillis = 10_000)
        val afterTwo = breached(penalties).standDownDelayMillis(nowMillis = 10_000)

        assertEquals(StandbySession.FIRST_STAND_DOWN_PENALTY_MILLIS, afterOne)
        assertEquals(
            StandbySession.FIRST_STAND_DOWN_PENALTY_MILLIS * 2,
            afterTwo,
            "the doubling has to count codes tried before the app was killed",
        )
    }

    @Test
    fun theRightCodeClearsThePenaltyForGood() {
        val penalties = InMemoryStandDownPenaltyStore()
        val session = breached(penalties)

        session.clearBreach(entry = "0000", nowMillis = 1_000)
        // Past the delay it just earned, then the real code.
        val past = 1_000 + StandbySession.FIRST_STAND_DOWN_PENALTY_MILLIS
        assertIs<StandbyState.Idle>(session.clearBreach(entry = "1234", nowMillis = past))

        assertEquals(0, penalties.loadFailures())
        assertEquals(0L, penalties.loadReadyAtMillis())
        assertEquals(0L, breached(penalties).standDownDelayMillis(nowMillis = past))
    }

    // MARK: - Reboot

    @Test
    fun aDeadlineFromBeforeARebootIsNotHonoured() {
        // The stored deadline is a monotonic reading, which restarts near zero on reboot. Read
        // literally, last boot's value sits minutes in the future and would lock the user out
        // of their own alert screen.
        val stale = 9_000_000L
        assertEquals(
            0L,
            StandbySession.standDownReadyAt(storedReadyAtMillis = stale, nowMillis = 1_200),
        )
    }

    @Test
    fun aDeadlineInsideThisBootIsHonoured() {
        val readyAt = 100_000L + StandbySession.MAX_STAND_DOWN_PENALTY_MILLIS
        assertEquals(
            readyAt,
            StandbySession.standDownReadyAt(storedReadyAtMillis = readyAt, nowMillis = 100_000),
            "a deadline at the ceiling is the longest real one the ladder can issue",
        )
    }

    @Test
    fun aRebootBuysOneAttemptButNotAFreshLadder() {
        val penalties = InMemoryStandDownPenaltyStore()
        repeat(times = 3) { breached(penalties).clearBreach(entry = "0000", nowMillis = 500_000) }

        // Reboot: the clock is back near zero, so the stored deadline is unusable.
        val afterReboot = breached(penalties)
        assertEquals(0L, afterReboot.standDownDelayMillis(nowMillis = 1_000))

        // The count survived, so the next wrong code pays what the ladder had reached rather
        // than starting over at two seconds.
        afterReboot.clearBreach(entry = "0000", nowMillis = 1_000)
        assertTrue(
            afterReboot.standDownDelayMillis(nowMillis = 1_000) >
                StandbySession.FIRST_STAND_DOWN_PENALTY_MILLIS,
            "rebooting must not reset the doubling",
        )
    }
}
