package com.halotap.app.wear.sync

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.wearable.PutDataMapRequest
import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import com.halotap.app.settings.KeystoreSettingsStore
import com.halotap.app.settings.HalotapSettings
import com.halotap.app.settings.SettingsSync
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sync payload through a real Wearable `DataMap`, not a hand-made map.
 *
 * `SettingsSyncTest` in commonTest proves the encoding; this proves it survives the container
 * it actually travels in. A `putStringArray` that comes back as something other than an
 * `Array<String>` would pass every unit test and still leave the watch with no contacts.
 */
class SettingsSyncDataMapTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val preferencesName = "halotap.settings.synctest"

    private fun store() = KeystoreSettingsStore(context, preferencesName)

    @BeforeTest
    @AfterTest
    fun clearPreferences() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Mirrors exactly what SettingsPublisher does on the phone. */
    private fun publish(settings: HalotapSettings, stamp: Long = 1L): Map<String, Any?> {
        val request = PutDataMapRequest.create(SettingsSync.PATH)
        val map = request.dataMap
        SettingsSync.encode(settings, stamp).forEach { (key, value) ->
            when (value) {
                null -> map.remove(key)
                is Int -> map.putInt(key, value)
                is Long -> map.putLong(key, value)
                is String -> map.putString(key, value)
                is Array<*> -> map.putStringArray(key, value.map { it.toString() }.toTypedArray())
                else -> error("unsupported: $key")
            }
        }
        return map.toValues()
    }

    @Test
    fun contactsSurviveTheDataMap() {
        val phone = HalotapSettings(
            contacts = listOf(
                EmergencyContact("Ada", "+15550001"),
                EmergencyContact("Grace", "+15550002"),
            ),
        )

        assertTrue(SettingsSync.apply(publish(phone), store()))

        val watch = store().load()
        assertEquals(listOf("Ada", "Grace"), watch.contacts.map { it.name })
        assertEquals("+15550002", watch.contacts[1].phoneNumber)
    }

    @Test
    fun aWatchThatReceivesContactsCanEscalate() {
        // The claim the whole feature exists to make true. Before sync, this was false on
        // every watch: nothing could put contacts there, so a breach reached nobody.
        assertFalse(store().load().canEscalate)

        SettingsSync.apply(
            publish(HalotapSettings(contacts = listOf(EmergencyContact("Ada", "+15550001")))),
            store(),
        )

        assertTrue(store().load().canEscalate)
    }

    @Test
    fun theEndpointSurvivesTheDataMap() {
        val phone = HalotapSettings(
            http = HttpEscalationConfig.Twilio("AC123", "token", "+15559999"),
        )

        SettingsSync.apply(publish(phone), store())

        assertEquals(
            "AC123",
            (store().load().http as HttpEscalationConfig.Twilio).accountSid,
        )
    }

    @Test
    fun aDuressCodeOnTheWatchSurvivesARealDataMapRoundTrip() {
        // The commonTest suite proves the encoding leaves the duress code alone. This proves
        // it through the container it actually travels in -- including that a `DataMap` with no
        // duress key cannot arrive as one that clears the field, which is how the watch used
        // to lose a code the user had set on it.
        store().saveDuressSecret(DisarmSecret.ofPin("5150"))

        SettingsSync.apply(
            publish(
                HalotapSettings(
                    contacts = listOf(EmergencyContact("Ada", "+15550001")),
                ),
            ),
            store(),
        )

        assertTrue(store().load().duressSecret!!.matches("5150"))
    }

    @Test
    fun theWatchesOwnPinIsNotOverwritten() {
        store().saveSecret(DisarmSecret.ofPin("4821"))

        SettingsSync.apply(publish(HalotapSettings(secret = DisarmSecret.ofPin("1111"))), store())

        assertTrue(store().load().secret!!.matches("4821"))
    }

    @Test
    fun clearingContactsOnThePhoneClearsThemOnTheWatch() {
        SettingsSync.apply(
            publish(HalotapSettings(contacts = listOf(EmergencyContact("Ada", "+1")))),
            store(),
        )
        assertTrue(store().load().canEscalate)

        SettingsSync.apply(publish(HalotapSettings(), stamp = 2L), store())
        assertFalse(store().load().canEscalate)
    }

    @Test
    fun syncedContactsAreStillEncryptedAtRest() {
        SettingsSync.apply(
            publish(HalotapSettings(contacts = listOf(EmergencyContact("Ada Lovelace", "+15550001")))),
            store(),
        )

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .all.values.joinToString(" ") { it.toString() }
        assertFalse(raw.contains("Ada Lovelace"))
        assertFalse(raw.contains("+15550001"))
    }
}
