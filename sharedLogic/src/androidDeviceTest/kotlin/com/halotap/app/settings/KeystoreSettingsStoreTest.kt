package com.halotap.app.settings

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs on a device because there is no Android Keystore on the JVM, and the encryption is the
 * part worth proving. Until these existed, the code holding the user's PIN and their emergency
 * contacts had no test at all.
 */
class KeystoreSettingsStoreTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferencesName = "halotap.settings.test"

    private fun store() = KeystoreSettingsStore(context, preferencesName)

    @BeforeTest
    @AfterTest
    fun clearPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun aFreshStoreIsUnconfigured() {
        val settings = store().load()
        assertNull(settings.secret)
        assertFalse(settings.isConfigured)
        assertTrue(settings.contacts.isEmpty())
    }

    @Test
    fun theSecretRoundTrips() {
        store().saveSecret(DisarmSecret.ofPin("4821"))

        val loaded = store().load().secret
        assertTrue(loaded!!.matches("4821"))
        assertFalse(loaded.matches("1234"))
    }

    @Test
    fun theSecretIsNotStoredInPlaintext() {
        // The whole reason this class exists. A regression that dropped the encryption would
        // otherwise pass every other test here.
        store().saveSecret(DisarmSecret.ofPin("4821"))

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString("secret", null)
        assertTrue(raw != null && raw.isNotBlank())
        assertFalse(raw.contains("4821"))
    }

    @Test
    fun theSameValueEncryptsDifferentlyEachTime() {
        // AES-GCM with a random IV. Identical ciphertext for identical input would mean the
        // IV was reused, which breaks GCM badly.
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

        store().saveSecret(DisarmSecret.ofPin("4821"))
        val first = preferences.getString("secret", null)
        store().saveSecret(DisarmSecret.ofPin("4821"))
        val second = preferences.getString("secret", null)

        assertFalse(first == second)
    }

    @Test
    fun theDuressCodeIsStoredSeparatelyFromTheSecret() {
        val store = store()
        store.saveSecret(DisarmSecret.ofPin("4821"))
        store.saveDuressSecret(DisarmSecret.ofPin("9876"))

        val loaded = store().load()
        assertTrue(loaded.secret!!.matches("4821"))
        assertTrue(loaded.duressSecret!!.matches("9876"))
        assertFalse(loaded.duressSecret!!.matches("4821"))
    }

    @Test
    fun contactsRoundTripInOrder() {
        store().saveContacts(
            listOf(
                EmergencyContact("Ada", "+15550001"),
                EmergencyContact("Grace", "+15550002"),
            ),
        )

        val loaded = store().load().contacts
        assertEquals(listOf("Ada", "Grace"), loaded.map { it.name })
        assertEquals("+15550002", loaded[1].phoneNumber)
    }

    @Test
    fun aShorterContactListDoesNotLeaveTheTailBehind() {
        // Indexed keys would otherwise keep contact.1.* from the longer list, silently
        // alerting someone the user had removed.
        val store = store()
        store.saveContacts(listOf(EmergencyContact("Ada", "+1"), EmergencyContact("Grace", "+2")))
        store.saveContacts(listOf(EmergencyContact("Ada", "+1")))

        assertEquals(listOf("Ada"), store().load().contacts.map { it.name })
    }

    @Test
    fun contactDetailsAreNotStoredInPlaintext() {
        store().saveContacts(listOf(EmergencyContact("Ada Lovelace", "+15550001")))

        val all = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString(" ") { it.toString() }
        assertFalse(all.contains("Ada Lovelace"))
        assertFalse(all.contains("+15550001"))
    }

    @Test
    fun twilioCredentialsRoundTrip() {
        store().saveHttpConfig(HttpEscalationConfig.Twilio("AC123", "secret-token", "+15559999"))

        val loaded = store().load().http as HttpEscalationConfig.Twilio
        assertEquals("AC123", loaded.accountSid)
        assertEquals("secret-token", loaded.authToken)
    }

    @Test
    fun theAuthTokenIsNotStoredInPlaintext() {
        store().saveHttpConfig(HttpEscalationConfig.Twilio("AC123", "secret-token", "+1"))

        val all = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString(" ") { it.toString() }
        assertFalse(all.contains("secret-token"))
    }

    @Test
    fun switchingEndpointKindDoesNotLeaveTheOldOneBehind() {
        val store = store()
        store.saveHttpConfig(HttpEscalationConfig.Twilio("AC123", "token", "+1"))
        store.saveHttpConfig(HttpEscalationConfig.Webhook("https://example.test/hook"))

        val loaded = store().load().http
        assertTrue(loaded is HttpEscalationConfig.Webhook)
    }

    @Test
    fun clearingTheSecretReturnsTheAppToSetup() {
        val store = store()
        store.saveSecret(DisarmSecret.ofPin("4821"))
        store.saveSecret(null)

        assertFalse(store().load().isConfigured)
    }

    @Test
    fun unreadableCiphertextIsTreatedAsAbsentRatherThanCrashing() {
        // What a restored backup or a cleared Keystore looks like. Returning to unconfigured
        // is the right failure; throwing here would mean the app cannot open at all.
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit().putString("secret", "not-base64-at-all!!").commit()

        assertNull(store().load().secret)
    }
}
