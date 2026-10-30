package com.halotap.app

import com.halotap.app.escalation.BreachMessages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandbySessionTest {

    private fun session(
        pin: String = "1234",
        maxAttempts: Int = StandbySession.DEFAULT_MAX_ATTEMPTS,
        graceMillis: Long = 10_000,
    ) = StandbySession(DisarmSecret.ofPin(pin), maxAttempts, graceMillis)

    @Test
    fun holdThenCorrectPinDisarms() {
        val session = session()
        assertEquals(StandbyState.Armed(heldSinceMillis = 0), session.hold(nowMillis = 0))
        assertEquals(
            StandbyState.Challenge(attemptsRemaining = 2, deadlineMillis = 15_000),
            session.release(nowMillis = 5_000),
        )
        assertEquals(StandbyState.Disarmed, session.submit("1234", nowMillis = 5_001))
    }

    @Test
    fun twoTriesIsTheDefaultAndTheSecondWrongOneBreaches() {
        assertEquals(2, StandbySession.DEFAULT_MAX_ATTEMPTS)

        val session = session()
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)

        val afterFirst = session.submit("0000", nowMillis = 1)
        assertEquals(1, (afterFirst as StandbyState.Challenge).attemptsRemaining)
        assertEquals(StandbyState.Breached(BreachReason.WRONG_SECRET), session.submit("0000", nowMillis = 2))
    }

    @Test
    fun aWrongEntryRestartsTheDeadline() {
        val session = session(graceMillis = 10_000)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)

        // Eight seconds gone, so the original window had two left. The retry gets a full ten.
        val retry = session.submit("0000", nowMillis = 8_000) as StandbyState.Challenge
        assertEquals(18_000, retry.deadlineMillis)

        // Proof the extension is real: this instant would have breached the old deadline.
        assertTrue(session.tick(nowMillis = 10_001) is StandbyState.Challenge)
        assertEquals(StandbyState.Disarmed, session.submit("1234", nowMillis = 17_999))
    }

    @Test
    fun theRestartedDeadlineStillExpires() {
        val session = session(graceMillis = 10_000)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)
        session.submit("0000", nowMillis = 1_000)

        assertEquals(StandbyState.Breached(BreachReason.DEADLINE_PASSED), session.tick(nowMillis = 11_000))
    }

    @Test
    fun silenceUntilTheDeadlineBreaches() {
        val session = session(graceMillis = 10_000)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)

        assertTrue(session.tick(nowMillis = 9_999) is StandbyState.Challenge)
        assertEquals(StandbyState.Breached(BreachReason.DEADLINE_PASSED), session.tick(nowMillis = 10_000))
    }

    @Test
    fun aCorrectPinAfterTheDeadlineIsRefused() {
        val session = session(graceMillis = 1_000)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)

        // Already escalated by this point, so accepting it would un-ring a bell.
        assertEquals(
            StandbyState.Breached(BreachReason.DEADLINE_PASSED),
            session.submit("1234", nowMillis = 1_000),
        )
    }

    @Test
    fun submittingOutsideAChallengeDoesNothing() {
        val session = session()
        assertEquals(StandbyState.Idle, session.submit("1234", nowMillis = 0))

        session.hold(nowMillis = 0)
        assertEquals(StandbyState.Armed(heldSinceMillis = 0), session.submit("1234", nowMillis = 1))
    }

    @Test
    fun breachedIsTerminalUntilReset() {
        val session = session(maxAttempts = 1)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)
        assertEquals(StandbyState.Breached(BreachReason.WRONG_SECRET), session.submit("9999", nowMillis = 1))

        val breached = StandbyState.Breached(BreachReason.WRONG_SECRET)
        assertEquals(breached, session.hold(nowMillis = 2))
        assertEquals(breached, session.submit("1234", nowMillis = 3))
        assertEquals(StandbyState.Idle, session.reset())
    }

    @Test
    fun heldDurationIsOnlyMeaningfulWhileArmed() {
        val session = session()
        assertNull(session.heldDurationMillis(nowMillis = 100))

        session.hold(nowMillis = 100)
        assertEquals(400, session.heldDurationMillis(nowMillis = 500))

        session.release(nowMillis = 500)
        assertNull(session.heldDurationMillis(nowMillis = 600))
    }

    @Test
    fun patternsAndPinsAreInterchangeable() {
        val secret = DisarmSecret.ofPattern(listOf(0, 3, 6, 7))
        val session = StandbySession(secret)
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)

        val entry = DisarmSecret.encodePatternEntry(listOf(0, 3, 6, 7))
        assertEquals(StandbyState.Disarmed, session.submit(entry, nowMillis = 1))
    }

    @Test
    fun secretsRejectUnusableInput() {
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofPin("123") }
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofPin("12a4") }
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofPattern(listOf(0, 1)) }
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofPattern(listOf(0, 0, 1, 2)) }
    }

    @Test
    fun matchingIsExact() {
        val secret = DisarmSecret.ofPin("1234")
        assertTrue(secret.matches("1234"))
        assertFalse(secret.matches("12345"))
        assertFalse(secret.matches("123"))
        assertFalse(secret.matches(""))
    }

    @Test
    fun sessionsRejectUnusableConfiguration() {
        val secret = DisarmSecret.ofPin("1234")
        assertFailsWith<IllegalArgumentException> { StandbySession(secret, maxAttempts = 0) }
        assertFailsWith<IllegalArgumentException> { StandbySession(secret, graceMillis = 0) }
    }
}

class ArmedRestoreTest {

    private fun session() = StandbySession(DisarmSecret.ofPin("1234"))

    @Test
    fun restoringPutsTheSessionBackOnWatch() {
        val session = session()
        assertEquals(StandbyState.Armed(heldSinceMillis = 5_000), session.restoreArmed(5_000))
        assertEquals(1_000, session.heldDurationMillis(nowMillis = 6_000))
    }

    @Test
    fun aStaleMarkerMustNotDragASessionBackwards() {
        // The user already released and is answering the challenge; a marker arriving late
        // must not yank them back to armed and lose the attempt they are part-way through.
        val session = session()
        session.hold(nowMillis = 0)
        val challenge = session.release(nowMillis = 0)

        assertEquals(challenge, session.restoreArmed(0))
    }

    @Test
    fun aMarkerFromBeforeARebootIsNotUsable() {
        // elapsedRealtime resets on reboot, so a saved value ahead of the current one can
        // only mean the device restarted. Cheapest possible reboot detection.
        assertFalse(StandbySession.isArmedMarkerUsable(savedSinceMillis = 900_000, nowMillis = 1_000))
    }

    @Test
    fun aMarkerOlderThanTheWindowIsNotUsable() {
        val hour = StandbySession.MAX_RESTORE_AGE_MILLIS
        assertTrue(StandbySession.isArmedMarkerUsable(0, nowMillis = hour))
        assertFalse(StandbySession.isArmedMarkerUsable(0, nowMillis = hour + 1))
    }

    @Test
    fun aFreshMarkerIsUsable() {
        // The case this exists for: a call lasting a few minutes.
        assertTrue(StandbySession.isArmedMarkerUsable(savedSinceMillis = 1_000, nowMillis = 181_000))
    }
}

class DuressCodeTest {

    private fun session(
        pin: String = "1234",
        duress: String? = "9876",
    ) = StandbySession(
        secret = DisarmSecret.ofPin(pin),
        duressSecret = duress?.let(DisarmSecret::ofPin),
    )

    private fun StandbySession.challenge() {
        hold(nowMillis = 0)
        release(nowMillis = 0)
    }

    @Test
    fun theDuressCodeBreachesWithItsOwnReason() {
        val session = session()
        session.challenge()

        assertEquals(
            StandbyState.Breached(BreachReason.DURESS),
            session.submit("9876", nowMillis = 1),
        )
    }

    @Test
    fun theDuressCodeIsIndistinguishableFromTheOutside() {
        // The whole point: someone watching over the user's shoulder must not be able to tell
        // which code was entered. Both consume the challenge immediately and neither burns an
        // attempt, so there is no timing or counter difference to read either.
        val disarmed = session().apply { challenge() }.submit("1234", nowMillis = 1)
        val duressed = session().apply { challenge() }.submit("9876", nowMillis = 1)

        assertEquals(StandbyState.Disarmed, disarmed)
        assertEquals(StandbyState.Breached(BreachReason.DURESS), duressed)
    }

    @Test
    fun aDuressEntryDoesNotBurnAnAttempt() {
        // If it decremented, a user who entered it would see "Last try" and know it had been
        // read as wrong, which is the opposite of the intended behaviour.
        val session = session()
        session.challenge()
        session.submit("9876", nowMillis = 1)

        assertEquals(StandbyState.Breached(BreachReason.DURESS), session.state)
    }

    @Test
    fun aWrongCodeIsStillJustAWrongCode() {
        val session = session()
        session.challenge()

        val afterFirst = session.submit("0000", nowMillis = 1)
        assertEquals(1, (afterFirst as StandbyState.Challenge).attemptsRemaining)
    }

    @Test
    fun duressIsOptional() {
        val session = session(duress = null)
        session.challenge()

        // With none configured, a would-be duress code is simply wrong.
        assertTrue(session.submit("9876", nowMillis = 1) is StandbyState.Challenge)
    }

    @Test
    fun aDuressCodeEqualToTheDisarmCodeIsRefused() {
        // It could never be entered: the disarm branch is checked first, so configuring this
        // would silently give the user a duress code that does nothing.
        assertFailsWith<IllegalArgumentException> {
            StandbySession(
                secret = DisarmSecret.ofPin("1234"),
                duressSecret = DisarmSecret.ofPin("1234"),
            )
        }
    }

    @Test
    fun theDuressMessageSaysWhatIsHappening() {
        val message = BreachMessages.default(BreachReason.DURESS)
        assertTrue(message.contains("duress"))
        assertTrue(message.contains("forced"))
    }
}

class StandDownTest {

    private fun breached(): StandbySession {
        val session = StandbySession(
            secret = DisarmSecret.ofPin("1234"),
            duressSecret = DisarmSecret.ofPin("9876"),
        )
        session.hold(nowMillis = 0)
        session.release(nowMillis = 0)
        session.submit("0000", nowMillis = 1)
        session.submit("0000", nowMillis = 2)
        return session
    }

    @Test
    fun theCorrectSecretClearsABreach() {
        // Breached was terminal, so leaving the app and coming back left the alert screen
        // up with no way past it. On watchOS the Digital Crown suspends rather than quits,
        // which makes that the normal way to leave an app.
        val session = breached()
        assertIs<StandbyState.Breached>(session.state)

        assertEquals(StandbyState.Idle, session.clearBreach("1234"))
    }

    @Test
    fun aWrongCodeLeavesTheBreachStanding() {
        val session = breached()
        assertIs<StandbyState.Breached>(session.clearBreach("0000"))
    }

    @Test
    fun theDuressCodeDoesNotClearABreach() {
        // Its purpose is to fake a disarm, and there is nothing left to fake. Accepting it
        // would also let someone who extracted it tidy away the evidence.
        val session = breached()
        assertIs<StandbyState.Breached>(session.clearBreach("9876"))
    }

    @Test
    fun standingDownDoesNothingOutsideABreach() {
        val session = StandbySession(DisarmSecret.ofPin("1234"))
        assertEquals(StandbyState.Idle, session.clearBreach("1234"))

        session.hold(nowMillis = 0)
        assertEquals(StandbyState.Armed(heldSinceMillis = 0), session.clearBreach("1234"))
    }

    @Test
    fun aClearedSessionCanArmAgain() {
        val session = breached()
        session.clearBreach("1234")

        assertEquals(StandbyState.Armed(heldSinceMillis = 5), session.hold(nowMillis = 5))
    }
}
