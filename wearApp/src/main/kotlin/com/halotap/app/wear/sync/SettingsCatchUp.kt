package com.halotap.app.wear.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.halotap.app.settings.SettingsStore
import com.halotap.app.settings.SettingsSync

/**
 * Reads whatever the phone last published, on launch.
 *
 * The listener service covers changes made while the watch is reachable. This covers the rest:
 * the app installed after the contacts were chosen, the watch that was off, the delivery that
 * was dropped. Without it a user could set everything up on the phone, open the watch, and
 * find it had no contacts with nothing to indicate why.
 */
class SettingsCatchUp(private val context: Context) {

    fun refresh(store: SettingsStore, onApplied: () -> Unit) {
        val uri = Uri.Builder().scheme("wear").path(SettingsSync.PATH).build()

        Wearable.getDataClient(context)
            .getDataItems(uri)
            .addOnSuccessListener { buffer ->
                val applied = buffer.use { items ->
                    items.any { item ->
                        SettingsSync.apply(
                            DataMapItem.fromDataItem(item).dataMap.toValues(),
                            store,
                        )
                    }
                }
                if (applied) {
                    Log.i(TAG, "settings caught up from the phone")
                    onApplied()
                }
            }
            .addOnFailureListener { failure ->
                // Not fatal: the watch keeps whatever it already had, which may be nothing.
                // The setup screen is what tells the user they have no contacts, not this.
                Log.w(TAG, "could not reach the phone for settings", failure)
            }
    }

    private companion object {
        const val TAG = "HalotapSync"
    }
}
