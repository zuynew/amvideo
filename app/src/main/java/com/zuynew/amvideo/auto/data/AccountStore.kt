package com.zuynew.amvideo.auto.data

/**
 * Where the account and the last known doors live between launches.
 *
 * The password is kept on purpose: the car app cannot show a sign-in form while
 * driving, so renewing a dead session silently is the only way the grid still
 * works after the phone has been away from the app for a while.
 *
 * The door list sits here too so the car screen can paint before the network
 * answers, which makes a door tappable the moment Android Auto connects.
 */
interface AccountStore {

    /**
     * Stable identifier for this install, generated once. The server ties the
     * account to it and demands a fresh SMS PIN whenever it changes, so it must
     * survive sign-out.
     */
    val deviceKey: String

    var sid: String?
    var phone: String?
    var password: String?
    var doors: List<Door>

    /**
     * Whether to watch for arrival at a КПУ. Off by default: it costs a standing
     * notification, so it is the driver's to turn on, not ours to assume.
     */
    var arrivalEnabled: Boolean

    /** Forgets the session, the credentials and the doors, but keeps [deviceKey]. */
    fun clear()
}
