package com.halotap.app.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.halotap.app.settings.HalotapSettings
import com.halotap.app.settings.SettingsSync

/**
 * Pushes the phone's settings to the watch.
 *
 * The watch cannot gather emergency contacts itself — Wear OS has no contact picker — so
 * without this the alarm has nobody to reach. That made every other part of the escalation
 * path moot, which is why this is not a convenience feature.
 *
 * The Data Layer keeps the item and redelivers it whenever the devices reconnect, so a watch
 * that was off, unpaired or not yet installed still receives the current settings when it
 * comes back. There is no queue to manage and no retry to write.
 */
class SettingsPublisher(private val context: Context) {

    fun publish(settings: HalotapSettings, stampMillis: Long) {
        val request = PutDataMapRequest.create(SettingsSync.PATH).apply {
            val map = dataMap
            SettingsSync.encode(settings, stampMillis).forEach { (key, value) ->
                when (value) {
                    null -> map.remove(key)
                    is Int -> map.putInt(key, value)
                    is Long -> map.putLong(key, value)
                    is String -> map.putString(key, value)
                    is Array<*> -> map.putStringArray(key, value.map { it.toString() }.toTypedArray())
                    else -> error("Unsupported sync value for $key: ${value::class}")
                }
            }
        }

        // Urgent because the alternative is the system batching it for minutes, and a user
        // who just added an emergency contact reasonably expects the watch to know.
        Wearable.getDataClient(context)
            .putDataItem(request.asPutDataRequest().setUrgent())
            .addOnSuccessListener { Log.i(TAG, "settings pushed to the watch") }
            .addOnFailureListener { failure -> Log.w(TAG, "settings push failed", failure) }
    }

    private companion object {
        const val TAG = "HalotapSync"
    }
}
