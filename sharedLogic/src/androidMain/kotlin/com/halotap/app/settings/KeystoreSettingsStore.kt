package com.halotap.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.halotap.app.DisarmSecret
import com.halotap.app.escalation.EmergencyContact
import com.halotap.app.escalation.HttpEscalationConfig
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Settings held in SharedPreferences, with every value encrypted under a key that never leaves
 * the Android Keystore.
 *
 * Written against the platform's own crypto rather than a library, for the same reason the rest
 * of :sharedLogic has no dependencies — and because `androidx.security:security-crypto` has sat
 * in alpha for years, which is not what an emergency path should rest on.
 *
 * The key is non-exportable and generated on first use. Clearing app data or restoring to a new
 * device destroys it, so the settings become unreadable and the app returns to unconfigured.
 * That is the right failure: better to re-run setup than to silently arm with a PIN the user
 * cannot remember setting.
 *
 * The secret is stored encrypted, not hashed. Hashing would be stronger, but it needs a real
 * KDF, and hand-rolling one is worse than relying on hardware-backed encryption at rest.
 * Moving to Keystore-held PBKDF2 is tracked as hardening work.
 */
class KeystoreSettingsStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES,
) : SettingsStore {

    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    override fun load(): HalotapSettings = HalotapSettings(
        secret = read(KEY_SECRET)?.let(DisarmSecret::ofEncoded),
        duressSecret = read(KEY_DURESS)?.let(DisarmSecret::ofEncoded),
        contacts = loadContacts(),
        http = loadHttpConfig(),
    )

    override fun saveSecret(secret: DisarmSecret?) = write(KEY_SECRET, secret?.encoded)

    override fun saveDuressSecret(secret: DisarmSecret?) = write(KEY_DURESS, secret?.encoded)

    override fun saveContacts(contacts: List<EmergencyContact>) {
        // Rewrite wholesale: a shorter list must not leave the tail of a longer one behind.
        clearIndexed(PREFIX_CONTACT)
        preferences.edit().putInt(KEY_CONTACT_COUNT, contacts.size).apply()
        contacts.forEachIndexed { index, contact ->
            write("$PREFIX_CONTACT$index.name", contact.name)
            write("$PREFIX_CONTACT$index.phone", contact.phoneNumber)
        }
    }

    override fun saveHttpConfig(config: HttpEscalationConfig?) {
        clearIndexed(PREFIX_HTTP)
        when (config) {
            null -> Unit
            is HttpEscalationConfig.Webhook -> {
                write("${PREFIX_HTTP}kind", KIND_WEBHOOK)
                write("${PREFIX_HTTP}url", config.url)
            }
            is HttpEscalationConfig.Twilio -> {
                write("${PREFIX_HTTP}kind", KIND_TWILIO)
                write("${PREFIX_HTTP}sid", config.accountSid)
                write("${PREFIX_HTTP}token", config.authToken)
                write("${PREFIX_HTTP}from", config.fromNumber)
            }
        }
    }

    override fun loadLastAlert(): AlertRecord? = read(KEY_ALERT)?.let(AlertRecords::decode)

    override fun saveLastAlert(record: AlertRecord) =
        write(KEY_ALERT, AlertRecords.encode(record))

    private fun loadContacts(): List<EmergencyContact> {
        val count = preferences.getInt(KEY_CONTACT_COUNT, 0)
        return (0 until count).mapNotNull { index ->
            val name = read("$PREFIX_CONTACT$index.name")
            val phone = read("$PREFIX_CONTACT$index.phone")
            if (name != null && phone != null) EmergencyContact(name, phone) else null
        }
    }

    private fun loadHttpConfig(): HttpEscalationConfig? =
        when (read("${PREFIX_HTTP}kind")) {
            KIND_WEBHOOK -> read("${PREFIX_HTTP}url")?.let(HttpEscalationConfig::Webhook)
            KIND_TWILIO -> {
                val sid = read("${PREFIX_HTTP}sid")
                val token = read("${PREFIX_HTTP}token")
                val from = read("${PREFIX_HTTP}from")
                if (sid != null && token != null && from != null) {
                    HttpEscalationConfig.Twilio(sid, token, from)
                } else {
                    null
                }
            }
            else -> null
        }

    private fun clearIndexed(prefix: String) {
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        editor.apply()
    }

    private fun write(key: String, value: String?) {
        val editor = preferences.edit()
        if (value == null) editor.remove(key) else editor.putString(key, encrypt(value))
        editor.apply()
    }

    private fun read(key: String): String? =
        preferences.getString(key, null)?.let { stored ->
            // Unreadable ciphertext means the Keystore key is gone (app data cleared, device
            // restored). Treat it as absent rather than crashing the app open.
            runCatching { decrypt(stored) }.getOrNull()
        }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + ciphertext, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, bytes, 0, IV_LENGTH_BYTES),
        )
        return String(
            cipher.doFinal(bytes, IV_LENGTH_BYTES, bytes.size - IV_LENGTH_BYTES),
            Charsets.UTF_8,
        )
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not setUserAuthenticationRequired: the watch must be able to
                // read the secret to check it, including while locked on the wrist.
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val DEFAULT_PREFERENCES = "halotap.settings"
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "halotap.settings.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128

        const val KEY_SECRET = "secret"
        const val KEY_DURESS = "duress"
        const val KEY_CONTACT_COUNT = "contact.count"
        const val PREFIX_CONTACT = "contact."
        const val PREFIX_HTTP = "http."
        const val KEY_ALERT = "alert.last"
        const val KIND_WEBHOOK = "webhook"
        const val KIND_TWILIO = "twilio"
    }
}
