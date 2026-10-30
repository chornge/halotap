package com.halotap.app.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import com.halotap.app.settings.KeystoreSettingsStore
import com.halotap.app.settings.SettingsSync

/**
 * Receives settings the phone pushed and writes them into the watch's own store.
 *
 * A service rather than anything tied to the UI: the watch app is usually closed when the
 * user is editing contacts on their phone, and the Data Layer starts this process to deliver.
 * Arriving settings are written straight to the same encrypted store the alarm reads, so a
 * breach ten seconds later already has somewhere to go.
 */
class SettingsSyncService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        events.use { buffer ->
            buffer.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach
                if (event.dataItem.uri.path != SettingsSync.PATH) return@forEach

                val values = DataMapItem.fromDataItem(event.dataItem).dataMap.toValues()
                val applied = SettingsSync.apply(values, KeystoreSettingsStore(applicationContext))

                // Counts only: who the contacts are never goes near a log.
                Log.i(
                    TAG,
                    if (applied) {
                        "settings applied from the phone, " +
                            "${values.contactCount()} contact(s)"
                    } else {
                        "settings rejected: unusable payload"
                    },
                )
            }
        }
    }

    private companion object {
        const val TAG = "HalotapSync"
    }
}

/** DataMap to the plain map [SettingsSync] understands, so the parsing stays testable. */
internal fun com.google.android.gms.wearable.DataMap.toValues(): Map<String, Any?> =
    keySet().associateWith { key -> get<Any?>(key) }

private fun Map<String, Any?>.contactCount(): Int =
    (this[SettingsSync.KEY_CONTACT_NAMES] as? Array<*>)?.size ?: 0
