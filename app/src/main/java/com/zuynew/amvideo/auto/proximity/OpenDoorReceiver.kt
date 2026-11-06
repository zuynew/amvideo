package com.zuynew.amvideo.auto.proximity

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.zuynew.amvideo.auto.OpenerApp
import com.zuynew.amvideo.auto.data.Door
import com.zuynew.amvideo.auto.ui.resolve
import com.zuynew.amvideo.auto.ui.toNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Handles Открыть from the arrival notification, so the driver never has to open
 * the app at all. The same [com.zuynew.amvideo.auto.data.DoorRepository] runs the
 * request, which means the cooldown and the session renewal apply here too.
 */
class OpenDoorReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val door = intent.toDoorOrNull() ?: return

        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A broadcast has roughly ten seconds before the system loses
                // patience, and an open can wait on the cooldown lock, a request
                // and a session renewal. Better a missed answer than an ANR.
                val notice = withTimeout(DEADLINE_MILLIS) {
                    OpenerApp.repositoryOf(appContext).open(door).toNotice()
                }
                ProximityNotifier(appContext).showResult(door, notice.resolve(appContext))
            } catch (e: Throwable) {
                // Nothing here is worth taking the process down for.
                Log.w(TAG, "Could not open ${door.address} from the notification", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "OpenDoorReceiver"
        private const val ACTION_OPEN = "com.zuynew.amvideo.auto.OPEN_DOOR"
        private const val EXTRA_DOOR_ID = "door_id"
        private const val EXTRA_RELAY = "relay"
        private const val EXTRA_ADDRESS = "address"

        /** Comfortably inside the foreground broadcast limit. */
        private const val DEADLINE_MILLIS = 8_000L

        fun pendingIntent(context: Context, door: Door): PendingIntent {
            val intent = Intent(context, OpenDoorReceiver::class.java).apply {
                action = ACTION_OPEN
                putExtra(EXTRA_DOOR_ID, door.id)
                putExtra(EXTRA_RELAY, door.relay)
                putExtra(EXTRA_ADDRESS, door.address)
            }
            return PendingIntent.getBroadcast(
                context,
                ProximityNotifier.notificationId(door.key),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun Intent.toDoorOrNull(): Door? {
            if (action != ACTION_OPEN) return null
            val id = getStringExtra(EXTRA_DOOR_ID) ?: return null
            val relay = getStringExtra(EXTRA_RELAY) ?: return null
            return Door(id = id, address = getStringExtra(EXTRA_ADDRESS).orEmpty(), relay = relay)
        }
    }
}
