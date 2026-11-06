package com.zuynew.amvideo.auto.data

import java.io.IOException

class FakeStore : AccountStore {
    override val deviceKey: String = "device-1"
    override var sid: String? = null
    override var phone: String? = null
    override var password: String? = null
    override var doors: List<Door> = emptyList()
    override var arrivalEnabled: Boolean = false

    var cleared = false

    override fun clear() {
        cleared = true
        sid = null
        phone = null
        password = null
        doors = emptyList()
    }
}

class FakeApi : AmVideoApi {

    /** Set to make every call fail as if the network were down. */
    var offline: Boolean = false

    /** Set to make the server answer badly rather than be unreachable. */
    var serverError: String? = null

    var loginResponse: LoginResponse = LoginResponse(result = true, sid = "sid-renewed")

    /** Queued answers; once empty, a fixed success is returned instead. */
    val panelsAnswers = ArrayDeque<PanelsResponse>()
    val openAnswers = ArrayDeque<OpenResponse>()

    var loginCount = 0
    var openCount = 0
    var lastOpenSid: String? = null
    var lastPin: String? = null

    override suspend fun login(
        phone: String,
        password: String,
        deviceKey: String,
        pin: String,
    ): LoginResponse {
        failIfOffline()
        loginCount++
        lastPin = pin
        return loginResponse
    }

    var pinResponse: PinResponse = PinResponse(result = true, str = "ПИН выслан")
    var pinCount = 0
    val pinRepeats = mutableListOf<Boolean>()

    override suspend fun requestPin(phone: String, repeat: Boolean): PinResponse {
        failIfOffline()
        pinCount++
        pinRepeats += repeat
        return pinResponse
    }

    override suspend fun panels(sid: String): PanelsResponse {
        failIfOffline()
        return panelsAnswers.removeFirstOrNull() ?: PanelsResponse(result = true, data = emptyList())
    }

    override suspend fun open(sid: String, doorId: String, relay: String): OpenResponse {
        failIfOffline()
        openCount++
        lastOpenSid = sid
        return openAnswers.removeFirstOrNull() ?: OpenResponse(result = true, str = "открыто")
    }

    private fun failIfOffline() {
        serverError?.let { throw ApiException(it) }
        if (offline) throw IOException("offline")
    }
}
