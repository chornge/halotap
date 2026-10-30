package com.halotap.app.settings

import platform.Foundation.NSUserDefaults

/**
 * Which breach is open, and which one has been dealt with.
 *
 * The watchOS counterpart of the Wear app's BreachTokens, and the reason a relaunch can work
 * out what it missed. The token is the challenge deadline: unique per challenge and
 * monotonic, so nothing has to be generated.
 *
 * Stored as Doubles for the same reason the armed marker is: `NSInteger` is 32-bit on
 * arm64_32, which every Apple Watch since Series 4 is, and a millisecond clock overflows that
 * in about 24 days of uptime.
 */
class UserDefaultsBreachTokens(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {

    fun openToken(): Long = defaults.doubleForKey(KEY_OPEN).toLong()

    fun handledToken(): Long = defaults.doubleForKey(KEY_HANDLED).toLong()

    fun open(token: Long) = defaults.setDouble(token.toDouble(), KEY_OPEN)

    /** Disarmed. Without this a normal disarm would look unresolved on the next launch. */
    fun clearOpen() = defaults.removeObjectForKey(KEY_OPEN)

    fun markHandled(token: Long) = defaults.setDouble(token.toDouble(), KEY_HANDLED)

    private companion object {
        const val KEY_OPEN = "halotap.breach.open"
        const val KEY_HANDLED = "halotap.breach.handled"
    }
}
