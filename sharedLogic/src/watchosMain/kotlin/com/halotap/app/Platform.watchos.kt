package com.halotap.app

import platform.WatchKit.WKInterfaceDevice

/**
 * watchOS has no UIDevice, so the iOS actual cannot be reused here. WatchKit is the
 * equivalent, which is the whole reason watchOS needs a source set of its own.
 */
class WatchOSPlatform : Platform {
    override val name: String =
        WKInterfaceDevice.currentDevice().systemName + " " + WKInterfaceDevice.currentDevice().systemVersion
}

actual fun getPlatform(): Platform = WatchOSPlatform()
