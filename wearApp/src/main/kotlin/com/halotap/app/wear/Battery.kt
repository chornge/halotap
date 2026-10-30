package com.halotap.app.wear

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * How much charge is left, for warning about it.
 *
 * Deliberately only a warning. Refusing to arm on a low battery would be the wrong call: a
 * person walking to their car at eight percent needs this app more than someone at eighty,
 * and an app that locks them out exactly when they reach for it is worse than one that is
 * honest about its odds. So the user is told and left to decide.
 */
fun batteryPercent(context: Context): Int? {
    // The sticky ACTION_BATTERY_CHANGED broadcast, not BATTERY_PROPERTY_CAPACITY. The
    // property reads the hardware layer, which `adb shell dumpsys battery set level` does
    // not override -- so the warning could never be exercised on an emulator, and a warning
    // that cannot be tested is one nobody finds out is broken.
    val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    if (level >= 0 && scale > 0) return level * PERCENT / scale

    return context.getSystemService(BatteryManager::class.java)
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        ?.takeIf { it in 0..PERCENT }
}

private const val PERCENT = 100

/**
 * Below this, the walk may outlast the watch.
 *
 * Fifteen percent is roughly an hour of screen-on time on current Wear hardware, and the
 * screen is held on for the whole hold. Far more than any walk to a car, which is the point:
 * the warning should be rare enough to mean something.
 */
const val LOW_BATTERY_PERCENT: Int = 15
