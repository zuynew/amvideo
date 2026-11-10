package com.zuynew.amvideo.auto.proximity

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.IBinder
import android.util.Log
import androidx.car.app.connection.CarConnection
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.location.LocationListenerCompat
import androidx.lifecycle.LifecycleService
import com.zuynew.amvideo.auto.OpenerApp
import com.zuynew.amvideo.auto.data.Fix
import com.zuynew.amvideo.auto.data.GeoPoint
import com.zuynew.amvideo.auto.data.ProximityTracker

/**
 * Watches where the car is while connected to a head unit, and announces a КПУ
 * as the driver arrives at it.
 *
 * Armed at boot and kept alive, but it only asks for a location while
 * [CarConnection] reports a car — so there is no GPS drain when the phone is in a
 * pocket. Watching the connection rather than this app's own car session matters:
 * a templated app's session only exists once the driver opens it on the car
 * screen, and the whole point of the arrival prompt is not having to.
 *
 * Location comes from the platform providers rather than Play Services, so this
 * keeps working on head units and phones without Google services.
 *
 * **Nothing in here may take the app down.** Arriving at a gate is a convenience
 * on top of a list of buttons that works perfectly well without it, so every
 * entry point from the system is wrapped: a failure here costs the driver the
 * prompt, never the ability to open the gate by hand.
 */
class DoorProximityService : LifecycleService() {

    private val tracker = ProximityTracker()
    private var notifier: ProximityNotifier? = null

    /** Doors currently showing an arrival notification, so they can be withdrawn. */
    private val posted = mutableSetOf<String>()

    private var lastUsedFix: Location? = null

    /** True while a head unit is attached, so location is worth asking for. */
    private var inCar = false

    // LocationListenerCompat, not a SAM lambda: before API 30 the provider
    // callbacks are abstract, and a lambda that does not implement them throws
    // AbstractMethodError the moment the driver toggles GPS.
    private val listener = object : LocationListenerCompat {
        override fun onLocationChanged(location: Location) {
            runCatching { onFix(location) }
                .onFailure { Log.w(TAG, "Dropped a location fix", it) }
        }

        override fun onProviderEnabled(provider: String) {
            Log.i(TAG, "Provider enabled: $provider")
        }

        override fun onProviderDisabled(provider: String) {
            Log.i(TAG, "Provider disabled: $provider")
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { startWatching() }.onFailure {
            Log.w(TAG, "Could not start the proximity watcher", it)
            stopSelf()
        }
    }

    // Sticky: being armed is the whole job, so come back if the system reclaims us.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        runCatching { getSystemService<LocationManager>()?.removeUpdates(listener) }
        runCatching { posted.forEach { notifier?.cancel(it) } }
        runCatching { OpenerApp.proximityOf(this).clear() }
        super.onDestroy()
    }

    /**
     * The foreground notification goes up before anything else can fail: a service
     * started with [Context.startForegroundService] that stops without it is
     * killed with "did not call startForeground".
     */
    // The type constant is API 29, but it is a compile-time int and ServiceCompat
    // ignores it below that level, so no runtime branch is needed.
    @SuppressLint("InlinedApi")
    private fun startWatching() {
        val notifier = ProximityNotifier(this).also { this.notifier = it }
        notifier.createChannels()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notifier.watchingNotification(connected = false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        if (!shouldWatch(this)) {
            Log.i(TAG, "Not watching: the feature is off or its permission is gone")
            stopSelf()
            return
        }

        CarConnection(this).type.observe(this) { type ->
            runCatching { onCarConnectionChanged(type) }
                .onFailure { Log.w(TAG, "Could not react to the car connection", it) }
        }
    }

    /**
     * Location is requested on connection and dropped on disconnection, so the
     * service can stay armed indefinitely without touching the GPS.
     */
    private fun onCarConnectionChanged(type: Int) {
        val connected = type == CarConnection.CONNECTION_TYPE_PROJECTION ||
            type == CarConnection.CONNECTION_TYPE_NATIVE
        if (connected == inCar) return
        inCar = connected

        notifier?.let { startForeground(NOTIFICATION_ID, it.watchingNotification(connected)) }
        if (connected) {
            Log.i(TAG, "Car connected, watching for arrival")
            requestUpdates()
        } else {
            Log.i(TAG, "Car disconnected, releasing location")
            getSystemService<LocationManager>()?.removeUpdates(listener)
            tracker.forget()
            lastUsedFix = null
            OpenerApp.proximityOf(this).clear()
        }
    }

    /**
     * Subscribes to every provider the device has, enabled or not. A provider that
     * comes up later then starts delivering on its own — with GPS switched off at
     * ignition, filtering by [LocationManager.isProviderEnabled] here would leave
     * the watcher deaf for the whole drive.
     */
    private fun requestUpdates() {
        val manager = getSystemService<LocationManager>() ?: return
        val providers = manager.allProviders.filter { it in WANTED_PROVIDERS }
        if (providers.isEmpty()) {
            Log.w(TAG, "This device exposes no usable location provider")
            return
        }
        providers.forEach { provider ->
            runCatching {
                @Suppress("MissingPermission")
                manager.requestLocationUpdates(
                    provider,
                    MIN_INTERVAL_MILLIS,
                    MIN_DISTANCE_METERS,
                    listener,
                )
            }.onFailure { Log.w(TAG, "Could not listen to $provider", it) }
        }
    }

    private fun onFix(location: Location) {
        // Read once per fix rather than held: the driver can change the radius on
        // the phone mid-drive, and the next fix should already use it.
        val radiusMeters = radiusMeters()
        if (!shouldUse(location, radiusMeters)) return
        lastUsedFix = location

        val doors = OpenerApp.repositoryOf(this).cachedDoors()
        if (doors.isEmpty()) return

        val update = tracker.update(
            doors = doors,
            fix = Fix(
                point = GeoPoint(location.latitude, location.longitude),
                bearingDegrees = location.bearing.toDouble().takeIf { location.hasBearing() },
                speedMetersPerSecond = location.speed.toDouble().takeIf { location.hasSpeed() },
            ),
            radiusMeters = radiusMeters,
        )

        OpenerApp.proximityOf(this).set(update.inRange)
        withdrawDepartedNotifications(update.inRange.map { it.door.key }.toSet())

        // Only the best match is announced; a second notification for the gate next
        // to it would be noise at exactly the wrong moment. The others stay
        // unannounced, so they can still prompt once this one is behind us.
        update.candidates.firstOrNull()?.let { nearest ->
            notifier?.announce(nearest)
            tracker.markAnnounced(nearest.door)
            posted += nearest.door.key
        }
    }

    private fun radiusMeters(): Double =
        OpenerApp.repositoryOf(this).arrivalRadiusMeters.toDouble()

    /** A gate left behind must not leave a live Открыть button in the car. */
    private fun withdrawDepartedNotifications(stillInRange: Set<String>) {
        val departed = posted - stillInRange
        departed.forEach { key -> notifier?.cancel(key) }
        posted -= departed
    }

    /**
     * Network fixes can be hundreds of metres out and carry no heading. Letting one
     * overrule a fresh GPS fix would flip a door in and out of range, re-announcing
     * it and yanking the car screen about.
     */
    private fun shouldUse(location: Location, radiusMeters: Double): Boolean {
        if (location.hasAccuracy() && location.accuracy > radiusMeters) return false

        val previous = lastUsedFix ?: return true
        if (location.provider == LocationManager.GPS_PROVIDER) return true
        if (previous.provider != LocationManager.GPS_PROVIDER) return true

        val sinceGpsMillis =
            (location.elapsedRealtimeNanos - previous.elapsedRealtimeNanos) / NANOS_PER_MILLI
        return sinceGpsMillis > GPS_PREFERENCE_MILLIS
    }

    companion object {
        private const val TAG = "DoorProximity"
        private const val NOTIFICATION_ID = 1
        private const val MIN_INTERVAL_MILLIS = 2_000L
        private const val MIN_DISTANCE_METERS = 5f
        private const val NANOS_PER_MILLI = 1_000_000L

        /** How long a GPS fix keeps precedence over a coarse network one. */
        private const val GPS_PREFERENCE_MILLIS = 30_000L

        private val WANTED_PROVIDERS =
            setOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        /** True when the driver has turned the feature on and granted what it needs. */
        fun shouldWatch(context: Context): Boolean =
            OpenerApp.repositoryOf(context).arrivalEnabled && hasLocationPermission(context)

        fun start(context: Context) {
            if (!shouldWatch(context)) {
                Log.i(TAG, "Not watching: the feature is off or its permission is missing")
                return
            }
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, DoorProximityService::class.java),
                )
            }.onFailure { Log.w(TAG, "Could not start the proximity watcher", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, DoorProximityService::class.java)) }
                .onFailure { Log.w(TAG, "Could not stop the proximity watcher", it) }
        }
    }
}
