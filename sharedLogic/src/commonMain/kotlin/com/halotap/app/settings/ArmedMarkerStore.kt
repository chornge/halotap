package com.halotap.app.settings

/**
 * Remembers that a hold was in progress, so it survives the process.
 *
 * Deliberately separate from [SettingsStore]: that holds what the user chose and is encrypted
 * under a hardware key, whereas this is one volatile timestamp written on every arm. Arming
 * must not wait on a Keystore round trip, and a monotonic timestamp is not a secret — it says
 * nothing a person with filesystem access could not already infer from the app being installed.
 *
 * The value comes from the clock described by [com.halotap.app.StandbySession.CLOCK_CONTRACT],
 * not the wall clock, so that changing the device time cannot make a marker look fresh — and
 * so that a watch which spent an hour asleep is not told the marker is a minute old.
 */
interface ArmedMarkerStore {
    /** Pass null when the hold ends, however it ends. */
    fun save(sinceMillis: Long?)

    fun load(): Long?
}

/** For tests and previews. */
class InMemoryArmedMarkerStore(private var value: Long? = null) : ArmedMarkerStore {
    override fun save(sinceMillis: Long?) {
        value = sinceMillis
    }

    override fun load(): Long? = value
}
