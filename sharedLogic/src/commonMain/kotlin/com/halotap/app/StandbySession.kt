package com.halotap.app

/** Why a session breached. The two read very differently to whoever gets the alert. */
enum class BreachReason {
    /** The secret was entered wrongly until no attempts remained. */
    WRONG_SECRET,

    /** Nothing was entered before the deadline. */
    DEADLINE_PASSED,

    /**
     * The duress code was entered: the user is being made to disarm.
     *
     * The most serious of the three, and the only one the user chose to send. Whoever
     * receives it should treat it as a person under coercion right now.
     */
    DURESS,
}

/** Where a standby session currently is. */
sealed interface StandbyState {

    /** Open but not held. Nothing is being watched yet. */
    data object Idle : StandbyState

    /** The user is holding the screen. This is the state they walk to the car in. */
    data class Armed(val heldSinceMillis: Long) : StandbyState

    /**
     * The hold ended and the secret is owed.
     *
     * [deadlineMillis] is when silence alone counts as a breach, so that dropping the watch
     * or having it taken escalates without the taker needing to guess wrong.
     */
    data class Challenge(val attemptsRemaining: Int, val deadlineMillis: Long) : StandbyState

    /** Correct secret. The caller should close the app. */
    data object Disarmed : StandbyState

    /** Wrong secret too many times, or the deadline passed. The caller should escalate. */
    data class Breached(val reason: BreachReason) : StandbyState
}

/**
 * The hold-to-arm, release-to-challenge state machine, shared by the Wear OS and watchOS apps.
 *
 * Time is passed in rather than read, so this stays free of platform clocks and is testable
 * without waiting. The session holds no I/O: deciding what a breach actually *does* —
 * notifying contacts, sounding an alarm — is the caller's job, because that policy is still
 * undecided and differs per platform.
 */
class StandbySession(
    private val secret: DisarmSecret,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val graceMillis: Long = DEFAULT_GRACE_MILLIS,
    /**
     * An optional second code that looks like a disarm and is not one.
     *
     * The threat this app is built for is someone standing next to the user. A PIN they can
     * be made to hand over protects nobody, so entering this one shows the same "Disarmed"
     * screen and escalates silently instead. Nothing in the UI may ever distinguish the two.
     */
    private val duressSecret: DisarmSecret? = null,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive, was $maxAttempts" }
        require(graceMillis > 0) { "graceMillis must be positive, was $graceMillis" }
        require(duressSecret == null || !duressSecret.matches(secret.encoded)) {
            "The duress code must differ from the disarm code, or it can never be entered"
        }
    }

    var state: StandbyState = StandbyState.Idle
        private set

    /** Press and hold. Only meaningful from [StandbyState.Idle]; otherwise a no-op. */
    fun hold(nowMillis: Long): StandbyState {
        if (state is StandbyState.Idle) {
            state = StandbyState.Armed(heldSinceMillis = nowMillis)
        }
        return state
    }

    /** Let go. Only meaningful while [StandbyState.Armed]; otherwise a no-op. */
    fun release(nowMillis: Long): StandbyState {
        if (state is StandbyState.Armed) {
            state = StandbyState.Challenge(
                attemptsRemaining = maxAttempts,
                deadlineMillis = nowMillis + graceMillis,
            )
        }
        return state
    }

    /**
     * Offer a PIN, or a pattern encoded with [DisarmSecret.encodePatternEntry].
     *
     * A late entry is refused even when correct, because by then the session has already
     * escalated and accepting it would undo an alert that may have gone out. A wrong entry
     * that still leaves an attempt restarts the deadline.
     */
    fun submit(entry: String, nowMillis: Long): StandbyState {
        val challenge = state as? StandbyState.Challenge ?: return state
        if (nowMillis >= challenge.deadlineMillis) {
            state = StandbyState.Breached(BreachReason.DEADLINE_PASSED)
            return state
        }
        state = when {
            secret.matches(entry) -> StandbyState.Disarmed
            // Checked before the attempt is counted, and before any wrong-code handling, so
            // that a duress entry behaves identically to a correct one from the outside.
            duressSecret?.matches(entry) == true -> StandbyState.Breached(BreachReason.DURESS)
            challenge.attemptsRemaining <= 1 -> StandbyState.Breached(BreachReason.WRONG_SECRET)
            // A wrong entry buys a fresh window. Fumbling the first attempt is exactly what
            // someone rattled is likely to do, and carrying the original deadline over would
            // leave them only the remainder of it to get the retry right.
            else -> StandbyState.Challenge(
                attemptsRemaining = challenge.attemptsRemaining - 1,
                deadlineMillis = nowMillis + graceMillis,
            )
        }
        return state
    }

    /** Call on a timer while challenging, so that an unanswered challenge still escalates. */
    fun tick(nowMillis: Long): StandbyState {
        val challenge = state as? StandbyState.Challenge ?: return state
        if (nowMillis >= challenge.deadlineMillis) {
            state = StandbyState.Breached(BreachReason.DEADLINE_PASSED)
        }
        return state
    }

    /** How long the hold has lasted, or null when not holding. */
    fun heldDurationMillis(nowMillis: Long): Long? =
        (state as? StandbyState.Armed)?.let { nowMillis - it.heldSinceMillis }

    /**
     * Re-enter [StandbyState.Armed] from a marker that outlived the process.
     *
     * An incoming call recreates the activity on Wear OS, which would otherwise drop the user
     * out of standby silently — no false alarm, but no protection either, and nothing telling
     * them. Restoring keeps the hold they started.
     *
     * Only meaningful from [StandbyState.Idle]: a session that has moved on must not be
     * dragged backwards by a stale marker.
     */
    fun restoreArmed(heldSinceMillis: Long): StandbyState {
        if (state is StandbyState.Idle) {
            state = StandbyState.Armed(heldSinceMillis = heldSinceMillis)
        }
        return state
    }

    /** Back to [StandbyState.Idle] to begin again. */
    fun reset(): StandbyState {
        state = StandbyState.Idle
        return state
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS: Int = 2
        const val DEFAULT_GRACE_MILLIS: Long = 10_000

        /**
         * How long a persisted armed marker stays usable.
         *
         * Bounded on purpose. An indefinitely armed app is its own false-alarm risk: open it
         * a week later, tap, and it challenges for a walk that ended long ago. An hour is
         * longer than any call or pocket detour and shorter than "I forgot about this".
         */
        const val MAX_RESTORE_AGE_MILLIS: Long = 60 * 60 * 1000

        /**
         * Whether a persisted armed marker should be trusted.
         *
         * [nowMillis] and [savedSinceMillis] must both come from a monotonic clock that
         * resets on reboot — `SystemClock.elapsedRealtime()` on Android. A saved value in
         * the future therefore means the device rebooted, which both invalidates the marker
         * and is the cheapest possible way to detect that.
         */
        fun isArmedMarkerUsable(
            savedSinceMillis: Long,
            nowMillis: Long,
            maxAgeMillis: Long = MAX_RESTORE_AGE_MILLIS,
        ): Boolean = when {
            savedSinceMillis > nowMillis -> false
            nowMillis - savedSinceMillis > maxAgeMillis -> false
            else -> true
        }
    }
}
