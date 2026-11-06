package com.zuynew.amvideo.auto

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.zuynew.amvideo.auto.data.DoorRepository
import com.zuynew.amvideo.auto.data.HttpAmVideoApi
import com.zuynew.amvideo.auto.data.KeystoreStore
import com.zuynew.amvideo.auto.diag.InstallSource
import com.zuynew.amvideo.auto.proximity.ProximityState

/**
 * Owns the single [DoorRepository] that the phone UI and the car screen share.
 *
 * One shared instance is the point: it is what makes the open cooldown and the
 * silent session renewal hold no matter which screen the driver used last.
 * Wiring three objects by hand is less machinery than an injection framework
 * would be at this size.
 */
class OpenerApp : Application() {

    val repository: DoorRepository by lazy {
        DoorRepository(
            api = HttpAmVideoApi(),
            store = KeystoreStore(this),
            // Monotonic, so the cooldown survives the clock being corrected.
            clock = SystemClock::elapsedRealtime,
        )
    }

    override fun onCreate() {
        super.onCreate()
        // Says, in one log line, whether this install can appear on the car
        // screen at all. Cheap, and it saves a drive to find out.
        InstallSource.log(this)
    }

    /** Written by the location service, read by the car screen. */
    val proximity = ProximityState()

    companion object {
        fun repositoryOf(context: Context): DoorRepository =
            (context.applicationContext as OpenerApp).repository

        fun proximityOf(context: Context): ProximityState =
            (context.applicationContext as OpenerApp).proximity
    }
}
