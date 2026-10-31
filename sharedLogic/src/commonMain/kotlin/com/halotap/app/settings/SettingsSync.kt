package com.halotap.app.settings

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
 * **Neither code travels.** The watch asks for its own PIN on first run and sets its own duress
 * code from its settings screen, and those are the secrets that should not move between
 * devices — they are also the ones a user can always re-set on the watch itself if the phone
 * is lost.
 *
 * The duress code used to travel, on the reasoning that there was nowhere on the watch to set
 * one. That stopped being true when both watches grew a settings screen, and it was the wrong
 * reasoning regardless: the duress code is *checked* on the watch and nowhere else, so the
 * watch is the only device that has any use for it. Syncing it gave one field two writers with
 * no way to order them, and the watch always lost — [apply] rewrote the field from the phone's
 * copy on every push, so a duress code set on the watch was silently deleted the next time the
 * user added a contact on the phone. A safety feature that disappears without saying so is
 * worse than one that was never offered.
 *
 * Kept as a flat map of primitives so it can go straight into a Wearable `DataMap` without a
 * serialization library, and so this is testable with no Google Play services present.
 */
object SettingsSync {

    const val PATH: String = "/halotap/settings"

    const val KEY_CONTACT_NAMES: String = "contactNames"
    const val KEY_CONTACT_PHONES: String = "contactPhones"
    const val KEY_HTTP_KIND: String = "httpKind"
    const val KEY_HTTP_URL: String = "httpUrl"
    const val KEY_HTTP_SID: String = "httpSid"
    const val KEY_HTTP_TOKEN: String = "httpToken"
    const val KEY_HTTP_FROM: String = "httpFrom"

    /** Bumped whenever the payload shape changes, so an old watch can refuse a new phone. */
    const val KEY_VERSION: String = "version"

    /**
     * 2 since the duress code stopped travelling.
     *
     * Bumped rather than left alone precisely so an old watch *refuses* this payload. An old
     * [apply] reads a missing duress key as "the user cleared it" and would delete the code the
     * watch holds — the very bug this version removes. Refusing means contacts stop updating
     * until the watch is updated too, which is the better of the two failures: stale contacts
     * are visible on the watch's own settings screen, a deleted duress code is not.
     */
    const val VERSION: Int = 2

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
        // No secret of either kind. Neither the PIN nor the duress code belongs on the wire:
        // the watch is the only device that checks them.
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
        store.saveHttpConfig(values.decodeHttp())
        // Nothing touches the secrets, and nothing may be added here that does. A payload from
        // an older phone still carries a duress code; it is ignored rather than applied, so a
        // watch on this version cannot have its own code overwritten during a staged update.
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
