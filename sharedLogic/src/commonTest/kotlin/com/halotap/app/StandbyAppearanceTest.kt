package com.halotap.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandbyAppearanceTest {

    @Test
    fun duressLooksExactlyLikeARealDisarm() {
        // The bug this guards against shipped: duress escalated correctly but drew a red
        // "Alert", so an onlooker saw exactly what it exists to hide.
        assertEquals(
            StandbyState.Disarmed.appearance(),
            StandbyState.Breached(BreachReason.DURESS).appearance(),
        )
        assertEquals(
            StandbyAppearance.DISARMED,
            StandbyState.Breached(BreachReason.DURESS).appearance(),
        )
    }

    @Test
    fun aGenuineBreachStillLooksLikeAnAlert() {
        val wrong = StandbyState.Breached(BreachReason.WRONG_SECRET)
        val late = StandbyState.Breached(BreachReason.DEADLINE_PASSED)
        assertEquals(StandbyAppearance.ALERT, wrong.appearance())
        assertEquals(StandbyAppearance.ALERT, late.appearance())
    }

    @Test
    fun everyOtherStateMapsToItself() {
        assertEquals(StandbyAppearance.HOLD, StandbyState.Idle.appearance())
        assertEquals(StandbyAppearance.ARMED, StandbyState.Armed(heldSinceMillis = 0).appearance())
        assertEquals(
            StandbyAppearance.CHALLENGE,
            StandbyState.Challenge(attemptsRemaining = 2, deadlineMillis = 1).appearance(),
        )
    }

    @Test
    fun onlyARealDisarmCallsOffTheAlarm() {
        // A duress breach looks identical but must not cancel the escalation it just
        // started, or the code is a no-op with a convincing animation.
        assertTrue(StandbyState.Disarmed.shouldStandDown())
        assertFalse(StandbyState.Breached(BreachReason.DURESS).shouldStandDown())
        assertFalse(StandbyState.Breached(BreachReason.WRONG_SECRET).shouldStandDown())
    }
}
