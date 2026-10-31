package com.halotap.app.settings

/**
 * How many stand-down codes have been got wrong, and when another will be looked at.
 *
 * Separate from [SettingsStore] for the same reason [ArmedMarkerStore] is: this is two
 * counters written on a wrong code, not a secret, and it must not wait on a Keystore round
 * trip. It is separate from [ArmedMarkerStore] because the two have opposite lifetimes — the
 * armed marker is dropped the moment a hold ends, and this has to outlive the whole session.
 *
 * It exists at all because the penalty used to live in fields on [com.halotap.app.StandbySession],
 * which made it worth nothing: force-quitting the app rebuilt the session with the counter
 * back at zero, so the doubling delay guarding the one record that an alert went out could be
 * reset as fast as the app could be relaunched.
 *
 * [readyAtMillis] is a monotonic clock reading, like every other deadline in this app, so
 * changing the device time cannot shorten the wait. The cost is that it means nothing after a
 * reboot; [com.halotap.app.StandbySession.standDownReadyAt] is what notices that.
 */
interface StandDownPenaltyStore {

    /** How many stand-down codes have been got wrong in a row. Zero when none have. */
    fun loadFailures(): Int

    /** The monotonic deadline before which no code is looked at. Zero when one will be. */
    fun loadReadyAtMillis(): Long

    /** Written together, because a count without its deadline would under- or over-punish. */
    fun save(failures: Int, readyAtMillis: Long)
}

/** For tests and previews. Holds nothing across a restart, which is the point. */
class InMemoryStandDownPenaltyStore(
    private var failures: Int = 0,
    private var readyAtMillis: Long = 0L,
) : StandDownPenaltyStore {

    override fun loadFailures(): Int = failures

    override fun loadReadyAtMillis(): Long = readyAtMillis

    override fun save(failures: Int, readyAtMillis: Long) {
        this.failures = failures
        this.readyAtMillis = readyAtMillis
    }
}
