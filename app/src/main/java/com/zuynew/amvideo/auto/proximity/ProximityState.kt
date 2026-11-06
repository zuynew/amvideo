package com.zuynew.amvideo.auto.proximity

import com.zuynew.amvideo.auto.data.NearbyDoor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The latest proximity reading, shared between the service that produces it and
 * the car screen that draws it. Empty means either nothing is nearby or nothing
 * is watching — both of which the car screen shows the same way, as the full list
 * of doors.
 */
class ProximityState {

    private val _nearby = MutableStateFlow<List<NearbyDoor>>(emptyList())
    val nearby: StateFlow<List<NearbyDoor>> = _nearby.asStateFlow()

    fun set(nearby: List<NearbyDoor>) {
        _nearby.value = nearby
    }

    fun clear() = set(emptyList())
}
