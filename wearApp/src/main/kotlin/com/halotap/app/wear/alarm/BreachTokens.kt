package com.halotap.app.wear.alarm

import android.content.Context

/**
 * Which breach is open, and which one has been dealt with.
 *
 * Shared by the coordinator (which opens one), the worker (which marks it handled) and the
 * boot receiver (which asks, after power was lost, whether one was left unresolved). Keeping
 * all three on one class is the only way they can be relied on to agree.
 *
 * The token is the challenge deadline: unique per challenge and monotonic, so nothing has to
 * be generated.
 */
class BreachTokens(context: Context) {

    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun openToken(): Long = preferences.getLong(KEY_OPEN, 0L)

    fun handledToken(): Long = preferences.getLong(KEY_HANDLED, 0L)

    // commit(), not apply(): the point of these is to be readable after the process dies,
    // and a queued write is exactly what a power cut loses.
    fun open(token: Long) {
        preferences.edit().putLong(KEY_OPEN, token).commit()
    }

    /** Disarmed. Without this a normal disarm would look unresolved on the next boot. */
    fun clearOpen() {
        preferences.edit().remove(KEY_OPEN).commit()
    }

    fun markHandled(token: Long) {
        preferences.edit().putLong(KEY_HANDLED, token).commit()
    }

    private companion object {
        const val PREFERENCES = "halotap.breaches"
        const val KEY_OPEN = "current"
        const val KEY_HANDLED = "lastHandled"
    }
}
