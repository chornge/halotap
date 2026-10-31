package com.halotap.app.settings

import platform.Foundation.NSUserDefaults

/**
 * The stand-down penalty in NSUserDefaults.
 *
 * Both values are stored as Doubles, and neither through `setInteger`. `NSInteger` is 32-bit
 * on arm64_32 — which every Apple Watch since Series 4 is — and 64-bit on the simulator and
 * on newer device slices, so its Kotlin type is not the same on every target this module
 * compiles for. A Double is 64-bit everywhere, represents these integers exactly, and is what
 * [UserDefaultsArmedMarkerStore] already uses for the same reason.
 *
 * Not the Keychain, for the same reason the Android side is not the Keystore: these are two
 * counters rather than a secret, and they only have to outlive the process.
 */
class UserDefaultsStandDownPenaltyStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : StandDownPenaltyStore {

    override fun loadFailures(): Int = defaults.doubleForKey(KEY_FAILURES).toInt()

    override fun loadReadyAtMillis(): Long = defaults.doubleForKey(KEY_READY_AT).toLong()

    override fun save(failures: Int, readyAtMillis: Long) {
        defaults.setDouble(failures.toDouble(), KEY_FAILURES)
        defaults.setDouble(readyAtMillis.toDouble(), KEY_READY_AT)
    }

    private companion object {
        const val KEY_FAILURES = "halotap.standDown.failures"
        const val KEY_READY_AT = "halotap.standDown.readyAt"
    }
}
