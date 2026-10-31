package com.halotap.app.settings

import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsSyncTest {

    private val phoneSettings = HalotapSettings(
        secret = DisarmSecret.ofPin("1234"),
        duressSecret = DisarmSecret.ofPin("9876"),
        contacts = listOf(
            EmergencyContact("Ada", "+15550001"),
            EmergencyContact("Grace", "+15550002"),
        ),
        http = HttpEscalationConfig.Twilio("AC123", "token", "+15559999"),
    )

    private fun roundTrip(settings: HalotapSettings = phoneSettings): HalotapSettings {
        val watch = InMemorySettingsStore()
        assertTrue(SettingsSync.apply(SettingsSync.encode(settings, stampMillis = 1), watch))
        return watch.load()
    }

    @Test
    fun contactsReachTheWatch() {
        // The entire point. Until this existed the watch had nobody to alert, which made the
        // whole alarm path moot.
        val watch = roundTrip()
        assertEquals(listOf("Ada", "Grace"), watch.contacts.map { it.name })
        assertEquals("+15550002", watch.contacts[1].phoneNumber)
        assertTrue(watch.canEscalate)
    }

    @Test
    fun theEndpointReachesTheWatch() {
        val watch = roundTrip()
        assertEquals("AC123", (watch.http as HttpEscalationConfig.Twilio).accountSid)
    }

    @Test
    fun theDisarmPinDoesNotTravel() {
        // The watch sets its own on first run. It is the one secret that should not move
        // between devices, and the one a user can always re-set if the phone is lost.
        val watch = InMemorySettingsStore()
        watch.saveSecret(DisarmSecret.ofPin("4821"))
        SettingsSync.apply(SettingsSync.encode(phoneSettings, stampMillis = 1), watch)

        assertTrue(watch.load().secret!!.matches("4821"))
    }

    @Test
    fun aWebhookRoundTrips() {
        val watch = roundTrip(
            phoneSettings.copy(http = HttpEscalationConfig.Webhook("https://example.test/hook")),
        )
        assertEquals("https://example.test/hook", (watch.http as HttpEscalationConfig.Webhook).url)
    }

    @Test
    fun removingEverythingOnThePhoneRemovesWhatThePhoneOwns() {
        val watch = InMemorySettingsStore()
        SettingsSync.apply(SettingsSync.encode(phoneSettings, stampMillis = 1), watch)
        SettingsSync.apply(SettingsSync.encode(HalotapSettings(), stampMillis = 2), watch)

        val loaded = watch.load()
        assertTrue(loaded.contacts.isEmpty())
        assertNull(loaded.http)
    }

    @Test
    fun aNewerPayloadVersionIsRefusedRatherThanGuessedAt() {
        val watch = InMemorySettingsStore()
        val future = SettingsSync.encode(phoneSettings, stampMillis = 1)
            .toMutableMap()
            .apply { put(SettingsSync.KEY_VERSION, SettingsSync.VERSION + 1) }

        assertFalse(SettingsSync.apply(future, watch))
        assertTrue(watch.load().contacts.isEmpty())
    }

    @Test
    fun mismatchedNamesAndNumbersAreRejectedWholesale() {
        // A half-applied contact list would mean texting the wrong number in an emergency,
        // which is worse than not updating at all.
        val watch = InMemorySettingsStore()
        watch.saveContacts(listOf(EmergencyContact("Ada", "+15550001")))

        val corrupt = SettingsSync.encode(phoneSettings, stampMillis = 1)
            .toMutableMap()
            .apply { put(SettingsSync.KEY_CONTACT_PHONES, arrayOf("+15550001")) }

        assertFalse(SettingsSync.apply(corrupt, watch))
        assertEquals(listOf("Ada"), watch.load().contacts.map { it.name })
    }

    @Test
    fun aBlankContactRowIsDroppedWithoutLosingTheRest() {
        val watch = InMemorySettingsStore()
        val withBlank = SettingsSync.encode(phoneSettings, stampMillis = 1)
            .toMutableMap()
            .apply {
                put(SettingsSync.KEY_CONTACT_NAMES, arrayOf("Ada", "   "))
                put(SettingsSync.KEY_CONTACT_PHONES, arrayOf("+15550001", "+15550002"))
            }

        assertTrue(SettingsSync.apply(withBlank, watch))
        assertEquals(listOf("Ada"), watch.load().contacts.map { it.name })
    }

    @Test
    fun anInsecureWebhookIsRefused() {
        val watch = InMemorySettingsStore()
        val insecure = SettingsSync.encode(phoneSettings, stampMillis = 1)
            .toMutableMap()
            .apply {
                put(SettingsSync.KEY_HTTP_KIND, "webhook")
                put(SettingsSync.KEY_HTTP_URL, "http://insecure.test/hook")
            }

        assertTrue(SettingsSync.apply(insecure, watch))
        assertNull(watch.load().http)
    }

    @Test
    fun theStampChangesSoARemovalIsNotDiscardedAsUnchanged() {
        // putDataItem drops an item identical to the current one, so a payload that differs
        // only by a removal would never be delivered without this.
        val first = SettingsSync.encode(HalotapSettings(), stampMillis = 1)
        val second = SettingsSync.encode(HalotapSettings(), stampMillis = 2)
        assertFalse(first[SettingsSync.KEY_STAMP] == second[SettingsSync.KEY_STAMP])
    }

    @Test
    fun aDuressCodeSetOnTheWatchSurvivesEveryPhonePush() {
        // The bug this pins down. The duress code used to travel, so every push rewrote the
        // field from the phone's copy -- and the phone's copy is usually nothing. Setting a
        // duress code on the watch and then adding a contact on the phone silently deleted it,
        // leaving the user believing they had a code that would quietly raise the alarm.
        val watch = InMemorySettingsStore()
        watch.saveDuressSecret(DisarmSecret.ofPin("5150"))

        // A phone with no duress code of its own, pushing twice, as adding a contact does.
        val phone = phoneSettings.copy(duressSecret = null)
        assertTrue(SettingsSync.apply(SettingsSync.encode(phone, stampMillis = 1), watch))
        assertTrue(SettingsSync.apply(SettingsSync.encode(phone, stampMillis = 2), watch))

        val kept = assertNotNull(
            watch.load().duressSecret,
            "the watch owns the duress code; a phone push must not be able to clear it",
        )
        assertTrue(kept.matches("5150"))
    }

    @Test
    fun aPhoneThatStillSendsADuressCodeCannotOverwriteTheWatchs() {
        // During a staged update the phone may be a version ahead or behind. An older phone
        // keeps putting the key on the wire; a watch on this version must ignore it rather
        // than take it, or the two writers come straight back for the length of the rollout.
        val watch = InMemorySettingsStore()
        watch.saveDuressSecret(DisarmSecret.ofPin("5150"))

        val fromOldPhone = SettingsSync.encode(phoneSettings, stampMillis = 1)
            .toMutableMap()
            .apply { put("duress", DisarmSecret.ofPin("9876").encoded) }

        assertTrue(SettingsSync.apply(fromOldPhone, watch))
        val kept = assertNotNull(watch.load().duressSecret, "the watch's own code must stand")
        assertTrue(kept.matches("5150"), "the phone's code must not have been taken")
    }

    @Test
    fun noSecretOfEitherKindIsPutOnTheWire() {
        // Structural, so re-adding either one has to fail here first. Neither code is checked
        // anywhere but on the watch, so neither has any business leaving the phone.
        val encoded = SettingsSync.encode(phoneSettings, stampMillis = 1)
        val values = encoded.values.filterIsInstance<String>()

        assertFalse(encoded.containsKey("duress"), "the duress code must not travel")
        assertFalse(values.contains("9876"), "the duress code must not travel under any key")
        assertFalse(values.contains("1234"), "the disarm PIN must not travel under any key")
    }
}
