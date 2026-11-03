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

    // MARK: - What the clock has to be

    // [nowMillis] must come from a clock that counts time the device spent asleep. Both cases
    // below are what happens when it does not, and they are not hypothetical: on Darwin,
    // `mach_absolute_time` -- which `DispatchTime.now().uptimeNanoseconds` reads, and which the
    // watch app's `nowMillis()` is built on -- stops while the system is suspended. Measured on
    // one ordinary host it had lost 24 of the 25 hours since boot. Wear OS reads
    // `SystemClock.elapsedRealtime()`, which does count sleep, so only the watch is exposed.
    //
    // These pin the stakes next to the function whose contract depends on them, so that
    // anyone tempted to loosen the rules here instead of fixing the clock sees why.

    @Test
    fun aClockThatStoppedWhileAsleepReadsAMissedBreachAsStillLive() {
        // The walk ends badly: the user releases, the wrist drops, and the watch suspends two
        // seconds into the ten-second challenge. A hundred seconds of real time pass.
        //
        // A clock that stopped reports only those two seconds, so the deadline still looks to
        // be in the future and this returns NOTHING -- the app relaunches, decides there is
        // nothing to report, and nobody is alerted at all.
        assertEquals(
            MissedBreachAction.NOTHING,
            action(open = 10_000, now = 2_000),
        )

        // The same moment, read from a clock that counted the sleep.
        assertEquals(
            MissedBreachAction.ESCALATE,
            action(open = 10_000, now = 100_000),
        )
    }

    @Test
    fun aClockThatStoppedWhileAsleepAlsoMisreadsHowLateABreachIs() {
        // The other direction, and the reason it is not enough for the deadline merely to
        // pass. The window exists so that contacts are not alerted about a walk that is long
        // over, and it is measured in elapsed time too.
        //
        // Ten minutes of real time after the deadline, a stopped clock that caught only
        // thirty seconds of it reports a breach well inside the window.
        assertEquals(
            MissedBreachAction.ESCALATE,
            action(open = 10_000, now = 40_000),
        )

        // Read from a clock that counted the sleep, the same breach is correctly too old to
        // send and only worth mentioning.
        assertEquals(
            MissedBreachAction.NOTIFY_ONLY,
            action(open = 10_000, now = 10_000 + 10 * 60 * 1000),
        )
    }
}
