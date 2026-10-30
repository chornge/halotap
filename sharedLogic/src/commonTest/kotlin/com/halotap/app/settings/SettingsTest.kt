package com.halotap.app.settings

import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HalotapSettingsTest {

    @Test
    fun aFreshInstallIsNotConfiguredAndHasNoDefaultPin() {
        val settings = HalotapSettings()
        assertFalse(settings.isConfigured)
        assertNull(settings.secret)
        assertFalse(settings.canEscalate)
    }

    @Test
    fun aSecretAloneIsEnoughToArm() {
        // Arming with nobody to call is a legitimate choice, so contacts must not gate setup.
        val settings = HalotapSettings(secret = DisarmSecret.ofPin("4821"))
        assertTrue(settings.isConfigured)
        assertFalse(settings.canEscalate)
    }

    @Test
    fun eitherContactsOrAnEndpointMakesEscalationPossible() {
        val withContacts = HalotapSettings(contacts = listOf(EmergencyContact("Ada", "+15550001")))
        val withEndpoint = HalotapSettings(http = HttpEscalationConfig.Webhook("https://a.test/h"))
        assertTrue(withContacts.canEscalate)
        assertTrue(withEndpoint.canEscalate)
    }

    @Test
    fun secretsSurviveAnEncodeDecodeRound() {
        val pin = DisarmSecret.ofPin("4821")
        assertTrue(DisarmSecret.ofEncoded(pin.encoded).matches("4821"))

        val pattern = DisarmSecret.ofPattern(listOf(0, 3, 6, 7))
        val restored = DisarmSecret.ofEncoded(pattern.encoded)
        assertTrue(restored.matches(DisarmSecret.encodePatternEntry(listOf(0, 3, 6, 7))))
        assertFalse(restored.matches(DisarmSecret.encodePatternEntry(listOf(0, 3, 6, 8))))
    }

    @Test
    fun aBlankStoredSecretIsRejectedRatherThanLoaded() {
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofEncoded("") }
        assertFailsWith<IllegalArgumentException> { DisarmSecret.ofEncoded("   ") }
    }
}

class SettingsStoreTest {

    @Test
    fun savingThenLoadingRoundTripsEveryField() {
        val store = InMemorySettingsStore()
        store.saveSecret(DisarmSecret.ofPin("4821"))
        store.saveContacts(listOf(EmergencyContact("Ada", "+15550001")))
        store.saveHttpConfig(HttpEscalationConfig.Twilio("AC1", "tok", "+15559999"))

        val loaded = store.load()
        assertTrue(loaded.isConfigured)
        assertTrue(loaded.secret!!.matches("4821"))
        assertEquals("Ada", loaded.contacts.single().name)
        assertEquals("AC1", (loaded.http as HttpEscalationConfig.Twilio).accountSid)
    }

    @Test
    fun clearingTheSecretReturnsTheAppToSetup() {
        val store = InMemorySettingsStore()
        store.saveSecret(DisarmSecret.ofPin("4821"))
        assertTrue(store.load().isConfigured)

        store.saveSecret(null)
        assertFalse(store.load().isConfigured)
    }

    @Test
    fun shorteningTheContactListDropsTheRemovedOnes() {
        val store = InMemorySettingsStore()
        store.saveContacts(listOf(EmergencyContact("Ada", "+1"), EmergencyContact("Grace", "+2")))
        store.saveContacts(listOf(EmergencyContact("Ada", "+1")))

        assertEquals(listOf("Ada"), store.load().contacts.map { it.name })
    }
}
