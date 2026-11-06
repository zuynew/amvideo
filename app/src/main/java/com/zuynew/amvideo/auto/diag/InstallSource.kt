package com.zuynew.amvideo.auto.diag

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Reports how this copy of the app was installed.
 *
 * Android Auto will not show a Car App Library app unless the install looks like
 * it came from Play, and it reads *two* fields to decide: the installing package
 * and the initiating one. `adb` can set the first (`install -i`) but never the
 * second, which the system fills in from the real caller — `com.android.shell`
 * for a sideload, whatever the first field says.
 *
 * Neither field is readable from a shell: `dumpsys package` prints only the
 * installing package and `cmd package` has no install-source subcommand. So the
 * app has to report it, or the only way to find out whether an install will be
 * accepted is to drive to the car and look at the screen.
 */
object InstallSource {

    const val TAG = "AmVideoInstall"

    private const val PLAY = "com.android.vending"
    private const val SHELL = "com.android.shell"

    data class Provenance(
        val initiating: String?,
        val installing: String?,
        val wouldBeAccepted: Boolean,
    )

    fun read(context: Context): Provenance {
        val packages = context.packageManager
        val self = context.packageName
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Before API 30 there is no initiator to read, and the host had only
            // the installing package to go on.
            @Suppress("DEPRECATION")
            val installing = runCatching { packages.getInstallerPackageName(self) }.getOrNull()
            return Provenance(null, installing, installing == PLAY)
        }
        val source = runCatching { packages.getInstallSourceInfo(self) }.getOrNull()
        val initiating = source?.initiatingPackageName
        val installing = source?.installingPackageName
        return Provenance(initiating, installing, wouldBeAccepted(initiating, installing))
    }

    /**
     * The test the host applies from API 30 on: the installing package must be
     * Play, and the initiator must be Play or the system package installer. The
     * adb shell is rejected outright, which is why `adb install -i` alone is not
     * enough however the installing package reads.
     */
    fun wouldBeAccepted(initiating: String?, installing: String?): Boolean {
        if (initiating == null || installing == null) return false
        if (initiating == SHELL) return false
        return installing == PLAY &&
            (initiating == PLAY || initiating.contains("packageinstaller"))
    }

    /** Best effort: a diagnostic must never be why the app fails to start. */
    fun log(context: Context) {
        runCatching {
            val provenance = read(context)
            Log.i(
                TAG,
                "initiating=${provenance.initiating} " +
                    "installing=${provenance.installing} " +
                    "androidAutoWouldAccept=${provenance.wouldBeAccepted}",
            )
        }
    }
}
