package com.zuynew.amvideo.auto.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.zuynew.amvideo.auto.OpenerApp
import com.zuynew.amvideo.auto.proximity.DoorProximityService

/**
 * The watcher is armed at boot and follows the car connection itself, so this
 * only nudges it back to life — covering the case where the system has reclaimed
 * the service since boot. Stopping it here would be wrong: the session ends
 * whenever the driver closes our app, and the arrival prompt has to outlive that.
 */
class DoorSession : Session(), DefaultLifecycleObserver {

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreateScreen(intent: Intent): Screen =
        DoorScreen(carContext, OpenerApp.repositoryOf(carContext), OpenerApp.proximityOf(carContext))

    override fun onCreate(owner: LifecycleOwner) {
        DoorProximityService.start(carContext)
    }
}
