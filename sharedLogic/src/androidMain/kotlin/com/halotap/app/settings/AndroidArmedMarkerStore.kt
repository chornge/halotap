package com.halotap.app.settings

import android.content.Context

/**
 * The armed marker in plain SharedPreferences.
 *
 * Unencrypted on purpose, unlike [KeystoreSettingsStore]. This is written the instant a user
 * presses and holds, so it must not wait on a Keystore round trip, and the value is a
 * monotonic clock reading rather than anything about the user. Encrypting it would buy nothing
 * and cost latency on the one path that has to feel immediate.
 */
class AndroidArmedMarkerStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES,
) : ArmedMarkerStore {

    private val preferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun save(sinceMillis: Long?) {
        val editor = preferences.edit()
        if (sinceMillis == null) editor.remove(KEY) else editor.putLong(KEY, sinceMillis)
        // commit, not apply: an incoming call can tear the activity down immediately after
        // this, and an apply() still sitting in the queue would be the exact write that gets
        // lost -- which is the bug this class exists to fix.
        editor.commit()
    }

    override fun load(): Long? =
        if (preferences.contains(KEY)) preferences.getLong(KEY, 0) else null

    private companion object {
        const val DEFAULT_PREFERENCES = "halotap.session"
        const val KEY = "armedSince"
    }
}
