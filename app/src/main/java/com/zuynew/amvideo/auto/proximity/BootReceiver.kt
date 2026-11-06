package com.zuynew.amvideo.auto.proximity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Re-arms the arrival watcher after a restart.
 *
 * Without this the watcher would only exist once the driver had opened the phone
 * app again, which is exactly the "remember to do something first" problem the
 * feature is meant to remove.
 *
 * Launching a `location` foreground service from here is allowed: Android 15's
 * restriction covers camera, dataSync, mediaPlayback, mediaProjection, microphone
 * and phoneCall, and not location.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        runCatching { DoorProximityService.start(context.applicationContext) }
            .onFailure { Log.w(TAG, "Could not re-arm the watcher after boot", it) }
    }

    private companion object {
        const val TAG = "BootReceiver"
        val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            // Some head units and older firmware only ever send this one.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
