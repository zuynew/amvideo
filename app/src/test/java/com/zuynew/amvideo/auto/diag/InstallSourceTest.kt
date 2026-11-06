package com.zuynew.amvideo.auto.diag

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the host's acceptance rule, because getting it wrong means a wasted
 * drive: the app would claim an install is fine and the car would disagree.
 */
class InstallSourceTest {

    @Test
    fun `a real Play install is accepted`() {
        assertTrue(
            InstallSource.wouldBeAccepted(
                initiating = "com.android.vending",
                installing = "com.android.vending",
            ),
        )
    }

    @Test
    fun `the system package installer is accepted as initiator`() {
        assertTrue(
            InstallSource.wouldBeAccepted(
                initiating = "com.google.android.packageinstaller",
                installing = "com.android.vending",
            ),
        )
    }

    @Test
    fun `an adb sideload is rejected even when it claims Play installed it`() {
        // This is exactly what `adb install -i com.android.vending` produces,
        // and exactly why the icon stayed missing on a real head unit.
        assertFalse(
            InstallSource.wouldBeAccepted(
                initiating = "com.android.shell",
                installing = "com.android.vending",
            ),
        )
    }

    @Test
    fun `a plain sideload is rejected`() {
        assertFalse(
            InstallSource.wouldBeAccepted(
                initiating = "com.google.android.packageinstaller",
                installing = "com.google.android.packageinstaller",
            ),
        )
    }

    @Test
    fun `unknown provenance is rejected rather than assumed good`() {
        assertFalse(InstallSource.wouldBeAccepted(initiating = null, installing = null))
        assertFalse(
            InstallSource.wouldBeAccepted(initiating = null, installing = "com.android.vending"),
        )
    }
}
