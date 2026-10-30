package com.halotap.app

import kotlin.test.Test
import kotlin.test.assertEquals

class MissedBreachTest {

    private fun action(
        open: Long = 0,
        handled: Long = 0,
        now: Long = 100_000,
    ) = MissedBreach.actionFor(open, handled, now)

    @Test
    fun nothingOpenMeansNothingToDo() {
        assertEquals(MissedBreachAction.NOTHING, action())
    }

    @Test
    fun anAlreadyHandledBreachIsNotSentTwice() {
        assertEquals(MissedBreachAction.NOTHING, action(open = 50_000, handled = 50_000))
    }

    @Test
    fun aDeadlineStillInTheFutureIsLeftAlone() {
        // The running app owns it; relaunching mid-challenge must not short-circuit it.
        assertEquals(MissedBreachAction.NOTHING, action(open = 150_000, now = 100_000))
    }

    @Test
    fun aRecentlyMissedBreachEscalates() {
        // The case this exists for: the app was relaunched seconds after the deadline.
        assertEquals(MissedBreachAction.ESCALATE, action(open = 99_000, now = 100_000))
    }

    @Test
    fun aBreachMissedLongAgoOnlyNotifies() {
        // Alerting contacts about a walk that ended hours ago is how an app teaches people
        // to ignore it.
        val longAgo = 100_000L - MissedBreach.MAX_LATE_ESCALATION_MILLIS - 1
        assertEquals(MissedBreachAction.NOTIFY_ONLY, action(open = longAgo, now = 100_000))
    }

    @Test
    fun theBoundaryItselfStillEscalates() {
        val exactly = 100_000L - MissedBreach.MAX_LATE_ESCALATION_MILLIS
        assertEquals(MissedBreachAction.ESCALATE, action(open = exactly, now = 100_000))
    }

    @Test
    fun aClockResetIsNotTreatedAsRecent() {
        // After a reboot the monotonic clock restarts, so a stored deadline is suddenly
        // ahead of now. Nothing can be inferred about how long ago it passed.
        assertEquals(MissedBreachAction.NOTHING, action(open = 900_000, now = 1_000))
    }

    @Test
    fun aDifferentHandledTokenDoesNotSuppressANewBreach() {
        assertEquals(MissedBreachAction.ESCALATE, action(open = 99_000, handled = 42, now = 100_000))
    }
}
