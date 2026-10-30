package com.halotap.app.wear

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.halotap.app.settings.AndroidArmedMarkerStore
import com.halotap.app.settings.ArmedMarkerStore
import com.halotap.app.settings.KeystoreSettingsStore
import com.halotap.app.settings.SettingsStore
import com.halotap.app.wear.alarm.BreachCoordinator
import com.halotap.app.wear.sync.SettingsCatchUp

class MainActivity : ComponentActivity() {

    private val requestSms =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    private lateinit var store: SettingsStore
    private lateinit var armedMarkers: ArmedMarkerStore
    private lateinit var breaches: BreachCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // The screen must not sleep mid-hold: a blanked screen would drop the touch and fire
        // the challenge while the user is still walking.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        store = KeystoreSettingsStore(applicationContext)
        armedMarkers = AndroidArmedMarkerStore(applicationContext)
        breaches = BreachCoordinator(applicationContext)

        // Asked for up front, not at the moment of a breach. A permission dialog appearing
        // during an emergency would be both useless and dangerous.
        requestSms.launch(Manifest.permission.SEND_SMS)

        setContent {
            var settings by remember { mutableStateOf(store.load()) }

            // Covers everything the listener service cannot: the app installed after the
            // contacts were chosen, or a watch that was off when they changed.
            LaunchedEffect(Unit) {
                SettingsCatchUp(applicationContext).refresh(store) { settings = store.load() }
            }

            val secret = settings.secret
            if (secret == null) {
                SetupScreen(
                    onSecretsChosen = { chosen, duress ->
                        store.saveSecret(chosen)
                        store.saveDuressSecret(duress)
                        settings = store.load()
                    },
                )
            } else {
                StandbyScreen(
                    secret = secret,
                    duressSecret = settings.duressSecret,
                    breaches = breaches,
                    armedMarkers = armedMarkers,
                )
            }
        }
    }

}
