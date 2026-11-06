package com.zuynew.amvideo.auto.car

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.zuynew.amvideo.auto.R

/**
 * Entry point Android Auto binds to. The IOT category it is registered under in
 * the manifest is the one Google documents for exactly this job — letting a
 * driver work a gate or garage door from the car.
 */
class DoorCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // The desktop head unit is not in the signed allowlist, so debug
            // builds have to accept any host to be testable at all.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts_allowlist)
                .build()
        }

    override fun onCreateSession(): Session = DoorSession()
}
