package com.halotap.app.settings

import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig

/**
 * What the phone sends to the watch, and how the watch applies it.
 *
 * The phone is the source of truth for the things a watch cannot collect: emergency contacts,
 * because neither Wear OS nor watchOS has a contact picker, and the alert endpoint, because
 * typing a URL or a Twilio token on a watch is not reasonable. Without this the watch has
 * nobody to alert, which made the whole alarm path moot.
 *
 * The **disarm PIN is deliberately not synced**. The watch asks for its own on first run, and
 * that is the one secret that should not travel between devices — it is also the one the user
 * can always re-set on the watch itself if the phone is lost. The duress code does travel,
 * because there is nowhere on the watch to set one and it is useless if only the phone knows it.
 *
 * Kept as a flat map of primitives so it can go straight into a Wearable `DataMap` without a
 * serialization library, and so this is testable with no Google Play services present.
 */
object SettingsSync {

    const val PATH: String = "/halotap/settings"

    const val KEY_CONTACT_NAMES: String = "contactNames"
    const val KEY_CONTACT_PHONES: String = "contactPhones"
    const val KEY_DURESS: String = "duress"
    const val KEY_HTTP_KIND: String = "httpKind"
    const val KEY_HTTP_URL: String = "httpUrl"
    const val KEY_HTTP_SID: String = "httpSid"
    const val KEY_HTTP_TOKEN: String = "httpToken"
    const val KEY_HTTP_FROM: String = "httpFrom"

    /** Bumped whenever the payload shape changes, so an old watch can refuse a new phone. */
    const val KEY_VERSION: String = "version"
    const val VERSION: Int = 1

    /**
     * A monotonically increasing stamp the sender sets.
     *
     * The Data Layer redelivers the current item whenever the devices reconnect, so without
     * this the watch would re-apply the same settings repeatedly. It also makes the payload
     * differ when only a removal happened, which `putDataItem` would otherwise discard as an
     * unchanged item.
     */
    const val KEY_STAMP: String = "stamp"

    fun encode(settings: HalotapSettings, stampMillis: Long): Map<String, Any?> = mapOf(
        KEY_VERSION to VERSION,
        KEY_STAMP to stampMillis,
        KEY_CONTACT_NAMES to settings.contacts.map { it.name }.toTypedArray(),
        KEY_CONTACT_PHONES to settings.contacts.map { it.phoneNumber }.toTypedArray(),
        KEY_DURESS to settings.duressSecret?.encoded,
        KEY_HTTP_KIND to settings.http.kind(),
        KEY_HTTP_URL to (settings.http as? HttpEscalationConfig.Webhook)?.url,
        KEY_HTTP_SID to (settings.http as? HttpEscalationConfig.Twilio)?.accountSid,
        KEY_HTTP_TOKEN to (settings.http as? HttpEscalationConfig.Twilio)?.authToken,
        KEY_HTTP_FROM to (settings.http as? HttpEscalationConfig.Twilio)?.fromNumber,
    )

    /**
     * Applies a received payload to [store].
     *
     * Returns false and writes nothing when the payload cannot be trusted — a newer version,
     * or contact names and numbers that do not line up. A half-applied contact list is worse
     * than an unchanged one: it would mean texting the wrong number in an emergency.
     */
    fun apply(values: Map<String, Any?>, store: SettingsStore): Boolean {
        val version = values[KEY_VERSION] as? Int ?: return false
        if (version > VERSION) return false

        val contacts = values.decodeContacts() ?: return false

        store.saveContacts(contacts)
        store.saveDuressSecret((values[KEY_DURESS] as? String)?.takeIf { it.isNotBlank() }
            ?.let(DisarmSecret::ofEncoded))
        store.saveHttpConfig(values.decodeHttp())
        return true
    }

    /**
     * Null when the two arrays disagree, which must reject the whole payload rather than
     * applying half of it: a mismatched list would mean texting the wrong number.
     */
    private fun Map<String, Any?>.decodeContacts(): List<EmergencyContact>? {
        val names = this[KEY_CONTACT_NAMES].asStrings() ?: return null
        val phones = this[KEY_CONTACT_PHONES].asStrings() ?: return null
        if (names.size != phones.size) return null

        return names.zip(phones)
            // Blank entries cannot make a valid contact and would throw; drop them rather
            // than rejecting the whole payload over one bad row.
            .filter { (name, phone) -> name.isNotBlank() && phone.isNotBlank() }
            .map { (name, phone) -> EmergencyContact(name = name, phoneNumber = phone) }
    }

    private fun HttpEscalationConfig?.kind(): String? = when (this) {
        null -> null
        is HttpEscalationConfig.Webhook -> "webhook"
        is HttpEscalationConfig.Twilio -> "twilio"
    }

    private fun Map<String, Any?>.decodeHttp(): HttpEscalationConfig? =
        when (this[KEY_HTTP_KIND] as? String) {
            "webhook" -> (this[KEY_HTTP_URL] as? String)
                ?.takeIf { it.startsWith("https://") }
                ?.let(HttpEscalationConfig::Webhook)

            "twilio" -> {
                val sid = this[KEY_HTTP_SID] as? String
                val token = this[KEY_HTTP_TOKEN] as? String
                val from = this[KEY_HTTP_FROM] as? String
                if (!sid.isNullOrBlank() && !token.isNullOrBlank() && !from.isNullOrBlank()) {
                    HttpEscalationConfig.Twilio(sid, token, from)
                } else {
                    null
                }
            }

            else -> null
        }

    private fun Any?.asStrings(): List<String>? = when (this) {
        is Array<*> -> mapNotNull { it as? String }
        is List<*> -> mapNotNull { it as? String }
        else -> null
    }
}
