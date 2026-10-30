package com.halotap.app

/**
 * What the screen shows. Deliberately coarser than [StandbyState].
 *
 * This exists because the two are not the same question, and treating them as one shipped a
 * bug that defeated the duress code entirely: entering it escalated correctly but drew a red
 * "Alert", so anyone standing over the user saw precisely what the duress code exists to
 * hide. The state machine must distinguish a duress breach; the screen must not.
 */
enum class StandbyAppearance {
    HOLD,
    ARMED,
    CHALLENGE,

    /** A real disarm, **or** a duress breach. From the outside these must be identical. */
    DISARMED,

    ALERT,
}

/**
 * How a state must look.
 *
 * [BreachReason.DURESS] maps to [StandbyAppearance.DISARMED] on purpose. Anything that lets
 * an onlooker tell the two apart — a different screen, a different colour, a different delay
 * before it clears — makes the duress code worse than useless, because the user believes it
 * is protecting them.
 */
fun StandbyState.appearance(): StandbyAppearance = when (this) {
    is StandbyState.Idle -> StandbyAppearance.HOLD
    is StandbyState.Armed -> StandbyAppearance.ARMED
    is StandbyState.Challenge -> StandbyAppearance.CHALLENGE
    is StandbyState.Disarmed -> StandbyAppearance.DISARMED
    is StandbyState.Breached ->
        if (reason == BreachReason.DURESS) StandbyAppearance.DISARMED else StandbyAppearance.ALERT
}

/**
 * Whether the alarm should be called off.
 *
 * True only for a genuine disarm. A duress breach looks the same on screen and must not
 * cancel the escalation it just started — that would make the code a no-op with a convincing
 * animation.
 */
fun StandbyState.shouldStandDown(): Boolean = this is StandbyState.Disarmed
