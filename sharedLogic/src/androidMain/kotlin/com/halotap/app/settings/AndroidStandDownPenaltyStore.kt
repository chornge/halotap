package com.halotap.app.settings

import android.content.Context

/**
 * The stand-down penalty in plain SharedPreferences.
 *
 * Unencrypted for the same reason [AndroidArmedMarkerStore] is: these are two counters, not a
 * secret, and nothing here tells a reader anything they could not infer from the app being
 * installed. What matters is only that they survive the process.
 */
class AndroidStandDownPenaltyStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES,
) : StandDownPenaltyStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun loadFailures(): Int = preferences.getInt(KEY_FAILURES, 0)

    override fun loadReadyAtMillis(): Long = preferences.getLong(KEY_READY_AT, 0L)

    // commit(), not apply(): the entire purpose of this store is to be readable after the
    // process dies, and force-quitting the app right after a wrong code is exactly the thing
    // it defends against. A queued write is the one that would be lost.
    override fun save(failures: Int, readyAtMillis: Long) {
        preferences.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_READY_AT, readyAtMillis)
            .commit()
    }

    internal companion object {
        internal const val DEFAULT_PREFERENCES = "halotap.standdown"
        private const val KEY_FAILURES = "failures"
        private const val KEY_READY_AT = "readyAt"
    }
}
