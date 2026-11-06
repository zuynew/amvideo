package com.zuynew.amvideo.auto.proximity

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import com.zuynew.amvideo.auto.R
import com.zuynew.amvideo.auto.data.Door
import com.zuynew.amvideo.auto.data.NearbyDoor
import kotlin.math.roundToInt

/**
 * Puts the door on the car screen the moment the driver arrives.
 *
 * Android Auto will not let an app push itself to the front, so a notification
 * carrying the Открыть action is the only way to offer the button without the
 * driver going looking for the app.
 */
class ProximityNotifier(private val context: Context) {

    private val cars = CarNotificationManager.from(context)

    fun createChannels() {
        cars.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ARRIVAL, NotificationManager.IMPORTANCE_HIGH)
                .setName(context.getString(R.string.proximity_arrival_channel))
                .build(),
        )
        cars.createNotificationChannel(
            // Silent: this one only exists because a location service must show something.
            NotificationChannelCompat.Builder(CHANNEL_WATCHING, NotificationManager.IMPORTANCE_LOW)
                .setName(context.getString(R.string.proximity_watching_channel))
                .build(),
        )
    }

    /** The quiet notification that keeps the watcher alive. */
    fun watchingNotification(connected: Boolean): Notification =
        NotificationCompat.Builder(context, CHANNEL_WATCHING)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle(
                context.getString(
                    if (connected) R.string.proximity_watching else R.string.proximity_armed,
                ),
            )
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    fun announce(nearby: NearbyDoor) {
        val distance = context.getString(
            R.string.proximity_arrived,
            nearby.distanceMeters.roundToInt(),
        )
        notify(
            door = nearby.door,
            text = distance,
            // A backstop in case the car is switched off inside the zone and the
            // service never gets to withdraw this: a live Открыть button must not
            // outlive the visit, or a stray tap days later opens the gate.
            timeoutMillis = ARRIVAL_TIMEOUT_MILLIS,
        ) { extender ->
            extender
                .setImportance(NotificationManager.IMPORTANCE_HIGH)
                .addAction(
                    R.drawable.ic_gate,
                    context.getString(R.string.open_action),
                    OpenDoorReceiver.pendingIntent(context, nearby.door),
                )
        }
    }

    /** Replaces the arrival notification with whatever the gate answered. */
    fun showResult(door: Door, message: String) {
        notify(door = door, text = message, timeoutMillis = RESULT_TIMEOUT_MILLIS)
    }

    /** Withdraws a door's notification once the car has left it behind. */
    fun cancel(doorKey: String) {
        cars.cancel(notificationId(doorKey))
    }

    private fun notify(
        door: Door,
        text: String,
        timeoutMillis: Long,
        configure: (CarAppExtender.Builder) -> CarAppExtender.Builder = { it },
    ) {
        val extender = CarAppExtender.Builder()
            .setContentTitle(door.address)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_gate)
        val builder = NotificationCompat.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_gate)
            .setContentTitle(door.address)
            .setContentText(text)
            // No category on purpose. CATEGORY_NAVIGATION marks a notification as
            // turn-by-turn, and the host then drops it unless we are the active
            // navigation app — which a gate opener never is. That silently cost us
            // the car-screen banner while the phone still showed it.
            .setAutoCancel(true)
            .setTimeoutAfter(timeoutMillis)
            .extend(configure(extender).build())
        cars.notify(notificationId(door.key), builder)
    }

    companion object {
        internal const val CHANNEL_WATCHING = "proximity_watching"
        internal const val CHANNEL_ARRIVAL = "proximity_arrival"

        /** Long enough to park and walk back, short enough not to linger. */
        private const val ARRIVAL_TIMEOUT_MILLIS = 10 * 60 * 1_000L

        /** The gate's answer is only interesting for a moment. */
        private const val RESULT_TIMEOUT_MILLIS = 15_000L

        internal fun notificationId(doorKey: String): Int = doorKey.hashCode()
    }
}
