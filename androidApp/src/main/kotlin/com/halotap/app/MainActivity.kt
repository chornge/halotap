package com.halotap.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.halotap.app.settings.KeystoreSettingsStore
import com.halotap.app.setup.SetupScreen
import com.halotap.app.sync.SettingsPublisher
import com.halotap.app.setup.pickContactIntent
import com.halotap.app.setup.resolvePickedContact

/**
 * The phone companion, which exists to be the setup surface.
 *
 * Neither watch can pick contacts — Wear OS has no picker and watchOS ships no ContactsUI —
 * so this is where that happens.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val store = KeystoreSettingsStore(applicationContext)
        val publisher = SettingsPublisher(applicationContext)

        // The phone used to own the duress code and no longer does: each watch sets its own,
        // and the sync stopped carrying it. Any copy left behind here is a secret sitting at
        // rest on the one device that has no use for it, so it is dropped. Idempotent -- after
        // the first launch there is nothing to remove.
        store.saveDuressSecret(secret = null)

        setContent {
            val context = LocalContext.current
            var settings by remember { mutableStateOf(store.load()) }

            // Every change goes to the watch immediately. Nothing in the UI asks the user to
            // sync: a safety app that needed a manual push would be one tap away from having
            // no contacts in an emergency.
            fun reload() {
                settings = store.load()
                publisher.publish(settings, stampMillis = System.currentTimeMillis())
            }

            val pickContact = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
                context.resolvePickedContact(result.data)?.let { contact ->
                    // Same number twice would text the person twice, so collapse duplicates.
                    val existing = settings.contacts.none { it.phoneNumber == contact.phoneNumber }
                    if (existing) {
                        store.saveContacts(settings.contacts + contact)
                        reload()
                    }
                }
            }

            MaterialTheme {
                Surface {
                    SetupScreen(
                        store = store,
                        settings = settings,
                        onPickContact = { pickContact.launch(pickContactIntent()) },
                        onSettingsChanged = ::reload,
                    )
                }
            }
        }
    }
}
