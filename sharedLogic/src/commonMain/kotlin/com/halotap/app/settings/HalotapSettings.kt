package com.halotap.app.settings

import com.halotap.app.DisarmSecret
import com.halotap.app.StandbySession
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig

/**
 * Everything the user chose during onboarding.
 *
 * [secret] being null is the meaningful case: it means the app has never been set up, and both
 * watches route to their setup flow instead of arming. There is no default PIN, deliberately —
 * a compiled-in fallback is exactly the thing that ends up shipping.
 */
data class HalotapSettings(
    val secret: DisarmSecret? = null,
    /** Optional second code that fakes a disarm and alerts instead. See [StandbySession]. */
    val duressSecret: DisarmSecret? = null,
    val contacts: List<EmergencyContact> = emptyList(),
    val http: HttpEscalationConfig? = null,
    val maxAttempts: Int = StandbySession.DEFAULT_MAX_ATTEMPTS,
    val graceMillis: Long = StandbySession.DEFAULT_GRACE_MILLIS,
) {
    /** True once a secret exists. Contacts are optional: arming with no one to call is valid. */
    val isConfigured: Boolean get() = secret != null

    /** True when a breach has somewhere to go. Worth warning about during onboarding. */
    val canEscalate: Boolean get() = contacts.isNotEmpty() || http != null
}

/**
 * Persistence for [HalotapSettings].
 *
 * Intentionally field-by-field rather than one blob: each platform then stores it with its own
 * key-value API — hardware-backed on both — and no JSON parser has to be hand-written or pulled
 * in. It also means a future phone-to-watch sync can push one field at a time.
 */
interface SettingsStore {
    fun load(): HalotapSettings

    /** Pass null to clear the secret, which returns the app to its unconfigured state. */
    fun saveSecret(secret: DisarmSecret?)

    /** Pass null to remove the duress code. Optional: most users will not set one. */
    fun saveDuressSecret(secret: DisarmSecret?)

    fun saveContacts(contacts: List<EmergencyContact>)

    fun saveHttpConfig(config: HttpEscalationConfig?)
}

/** For tests and previews. Holds nothing across a restart, which is the point. */
class InMemorySettingsStore(
    private var settings: HalotapSettings = HalotapSettings(),
) : SettingsStore {
    override fun load(): HalotapSettings = settings

    override fun saveSecret(secret: DisarmSecret?) {
        settings = settings.copy(secret = secret)
    }

    override fun saveDuressSecret(secret: DisarmSecret?) {
        settings = settings.copy(duressSecret = secret)
    }

    override fun saveContacts(contacts: List<EmergencyContact>) {
        settings = settings.copy(contacts = contacts)
    }

    override fun saveHttpConfig(config: HttpEscalationConfig?) {
        settings = settings.copy(http = config)
    }
}
