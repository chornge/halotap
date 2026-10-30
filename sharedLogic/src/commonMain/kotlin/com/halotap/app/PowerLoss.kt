package com.halotap.app

/**
 * Deciding whether a session was still open when the watch lost power.
 *
 * A watch running out of battery mid-walk is the most ordinary failure this app has, and
 * until now it was a silent one: no alert was sent, and on charging the app came back to
 * Idle with nothing to say it had stopped watching. The user believed they were covered and
 * were not.
 *
 * Deliberately **not** a trigger for escalation. A boot can happen hours later, and alerting
 * somebody's emergency contacts about a walk that ended last night is how an app teaches
 * people to ignore it. Telling the user is the honest response; deciding they are in danger
 * is not something a reboot can know.
 */
object PowerLoss {

    /**
     * True when the watch died with a session open.
     *
     * Two ways that happens, and both need catching:
     *
     * - **Armed.** An armed marker written before the reboot. Monotonic clocks reset on boot,
     *   so a marker still ahead of [nowMillis] can only have come from the previous life.
     * - **Challenging.** A challenge was opened and no breach was ever recorded as handled.
     *   The alarm that would have fired did not survive the reboot, so nothing resolved it.
     *
     * @param armedSinceMillis the persisted armed marker, or null if there was none
     * @param nowMillis monotonic time since this boot
     * @param openBreachToken the last challenge deadline recorded, or 0 if none is open
     * @param handledBreachToken the last breach the escalation worker completed, or 0
     */
    fun interruptedSessionFound(
        armedSinceMillis: Long?,
        nowMillis: Long,
        openBreachToken: Long,
        handledBreachToken: Long,
    ): Boolean {
        val armedBeforeReboot = armedSinceMillis != null && armedSinceMillis > nowMillis
        val challengeNeverResolved = openBreachToken != 0L && openBreachToken != handledBreachToken
        return armedBeforeReboot || challengeNeverResolved
    }
}
