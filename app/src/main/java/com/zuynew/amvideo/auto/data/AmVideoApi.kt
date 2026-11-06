package com.zuynew.amvideo.auto.data

import java.io.IOException

/**
 * The server answered, but not with anything the app can use: a bad HTTP status,
 * or a body that is not the JSON the client expects — a PHP warning, say.
 *
 * Kept apart from a plain [IOException] so the UI can show what actually went
 * wrong instead of blaming the network for a problem at the other end.
 */
class ApiException(message: String) : IOException(message)

/**
 * The AM Video back end: one PHP endpoint where a `type` field selects the
 * operation. Declared as an interface so [DoorRepository] can be exercised
 * without a network.
 *
 * Every method either returns a parsed answer or throws [java.io.IOException];
 * a refusal by the server is a normal return value, not an exception.
 */
interface AmVideoApi {

    /** @param pin the SMS code, empty unless the server asked for one. */
    suspend fun login(
        phone: String,
        password: String,
        deviceKey: String,
        pin: String = "",
    ): LoginResponse

    /**
     * Asks the server to deliver a PIN to [phone], which is the only way to get
     * one — a login that answers `change_device` does not send it by itself.
     *
     * @param repeat the first delivery may come as a voice call; a repeat asks
     *   for an SMS instead.
     */
    suspend fun requestPin(phone: String, repeat: Boolean): PinResponse

    /** Lists the КПУ attached to the session. Doubles as a session check. */
    suspend fun panels(sid: String): PanelsResponse

    suspend fun open(sid: String, doorId: String, relay: String): OpenResponse
}
