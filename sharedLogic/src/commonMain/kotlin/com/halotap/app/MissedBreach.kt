package com.halotap.app

/** What to do when the app comes back and finds a challenge it never resolved. */
enum class MissedBreachAction {
    /** Recent enough that the alert still means something. Send it. */
    ESCALATE,

    /**
     * The deadline passed, but too long ago, or the clock reset so how long ago is unknowable.
     * Tell the user it was missed rather than alerting contacts about a walk that is over.
     */
    NOTIFY_ONLY,

    NOTHING,
}

/**
 * Deciding what a relaunch owes a challenge that was left open.
 *
 * watchOS has no AlarmManager. The closest thing is a scheduled extended runtime session,
 * which relaunches the app — but only if the user has made this app the default responder
 * for its session type. So the app must also cope with simply being opened again later, by
 * the user or by a notification tap, and work out what it missed.
 *
 * Time must come from the clock described by [StandbySession.CLOCK_CONTRACT]. Both halves of
 * that matter here. If it were the wall clock, changing the watch's time could move a
 * deadline; if it did not count time the device spent asleep, this function would be handed a
 * reading from before the watch slept and would report a long-passed deadline as still live —
 * which is exactly how a breach went unescalated on Apple Watch.
 */
object MissedBreach {

    /**
     * How late an escalation may be and still be worth sending.
     *
     * Two minutes covers a relaunch, a notification tap, a user opening the app after a
     * buzz. Beyond that the walk is over, and texting somebody's emergency contacts about
     * it teaches them to ignore the next one — the same reason a reboot does not escalate.
     */
    const val MAX_LATE_ESCALATION_MILLIS: Long = 2 * 60 * 1000

    fun actionFor(
        openDeadlineMillis: Long,
        handledDeadlineMillis: Long,
        nowMillis: Long,
        maxLateMillis: Long = MAX_LATE_ESCALATION_MILLIS,
    ): MissedBreachAction = when {
        // Nothing was open, or it was already dealt with.
        openDeadlineMillis == 0L || openDeadlineMillis == handledDeadlineMillis ->
            MissedBreachAction.NOTHING

        // A deadline in the future means the challenge is still live and whatever is running
        // will handle it. It also covers a clock that reset, where the stored value is
        // suddenly ahead of now and nothing can be inferred about elapsed time.
        openDeadlineMillis > nowMillis -> MissedBreachAction.NOTHING

        nowMillis - openDeadlineMillis <= maxLateMillis -> MissedBreachAction.ESCALATE

        else -> MissedBreachAction.NOTIFY_ONLY
    }
}
