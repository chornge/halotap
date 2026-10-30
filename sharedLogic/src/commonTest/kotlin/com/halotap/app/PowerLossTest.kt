package com.halotap.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PowerLossTest {

    private fun found(
        armedSince: Long? = null,
        now: Long = 5_000,
        open: Long = 0,
        handled: Long = 0,
    ) = PowerLoss.interruptedSessionFound(armedSince, now, open, handled)

    @Test
    fun aQuietBootReportsNothing() {
        assertFalse(found())
    }

    @Test
    fun dyingWhileArmedIsFound() {
        // The marker is ahead of the post-boot clock, which can only mean it predates the
        // reboot. This is the battery-ran-out-mid-walk case.
        assertTrue(found(armedSince = 900_000, now = 5_000))
    }

    @Test
    fun dyingDuringTheChallengeIsFound() {
        // The alarm that would have escalated did not survive the reboot, and no breach was
        // ever recorded as handled.
        assertTrue(found(open = 900_000, handled = 0))
    }

    @Test
    fun aChallengeThatEscalatedNormallyIsNotReported() {
        assertFalse(found(open = 900_000, handled = 900_000))
    }

    @Test
    fun aMarkerFromThisBootIsNotAPowerLoss() {
        // Still armed, app merely restarted. The marker restore handles that; it is not a
        // power loss and must not be announced as one.
        assertFalse(found(armedSince = 1_000, now = 5_000))
    }

    @Test
    fun aStaleTokenFromAResolvedSessionIsNotReported() {
        // Disarming clears the open token, so a cleared one must never look unresolved --
        // otherwise every normal disarm would announce a power loss on the next boot.
        assertFalse(found(open = 0, handled = 900_000))
    }

    @Test
    fun eitherSignalAloneIsEnough() {
        assertTrue(found(armedSince = 900_000, now = 1, open = 0, handled = 0))
        assertTrue(found(armedSince = null, now = 1, open = 42, handled = 7))
    }
}
