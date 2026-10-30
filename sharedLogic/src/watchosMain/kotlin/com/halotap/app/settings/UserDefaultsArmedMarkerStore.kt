package com.halotap.app.settings

import platform.Foundation.NSUserDefaults

/**
 * The armed marker in NSUserDefaults.
 *
 * Stored as a Double rather than an integer on purpose. `NSInteger` is 32-bit on arm64_32,
 * which every Apple Watch since Series 4 is, and a monotonic millisecond clock overflows that
 * in about 24 days of uptime. A Double is 64-bit everywhere and represents integers exactly
 * well past any plausible uptime.
 *
 * Not the Keychain, for the same reason the Android side is not the Keystore: this is written
 * the instant a user presses and holds, and it is a clock reading rather than a secret.
 */
class UserDefaultsArmedMarkerStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : ArmedMarkerStore {

    override fun save(sinceMillis: Long?) {
        if (sinceMillis == null) {
            defaults.removeObjectForKey(KEY)
        } else {
            defaults.setDouble(sinceMillis.toDouble(), KEY)
        }
    }

    // objectForKey first: doubleForKey returns 0.0 for a missing key, which is
    // indistinguishable from a genuine marker written in the first millisecond after boot.
    override fun load(): Long? =
        if (defaults.objectForKey(KEY) == null) null else defaults.doubleForKey(KEY).toLong()

    private companion object {
        const val KEY = "halotap.armedSince"
    }
}
