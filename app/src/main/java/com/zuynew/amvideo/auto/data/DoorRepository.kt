package com.zuynew.amvideo.auto.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

sealed interface SignInResult {
    data object Success : SignInResult

    /** The server does not know this device and has sent an SMS code. */
    data class PinRequired(val message: String) : SignInResult

    /** The server refused, in its own words. */
    data class Rejected(val message: String) : SignInResult

    /** Nothing reached the server. */
    data object NetworkError : SignInResult

    /** The server answered, but not usefully; [detail] says how. */
    data class ServerError(val detail: String) : SignInResult
}

sealed interface PinRequestResult {
    /** The server took the request; [message] is its own wording. */
    data class Sent(val message: String) : PinRequestResult

    data class Cooldown(val secondsLeft: Int) : PinRequestResult
    data class Rejected(val message: String) : PinRequestResult
    /** Nothing reached the server. */
    data object NetworkError : PinRequestResult

    /** The server answered, but not usefully; [detail] says how. */
    data class ServerError(val detail: String) : PinRequestResult
}

sealed interface DoorsResult {
    data class Loaded(val doors: List<Door>) : DoorsResult
    data class Rejected(val message: String) : DoorsResult
    /** Nothing reached the server. */
    data object NetworkError : DoorsResult

    /** The server answered, but not usefully; [detail] says how. */
    data class ServerError(val detail: String) : DoorsResult

    data object SignInRequired : DoorsResult

    /**
     * The session is gone and the stored credentials can no longer take its
     * place, because the server has tied the account to a different device.
     * Signing in again needs a fresh PIN.
     */
    data class DeviceNotTrusted(val message: String) : DoorsResult
}

sealed interface OpenResult {
    /** The server accepted the request; [message] is its own wording. */
    data class Sent(val message: String) : OpenResult

    data class Cooldown(val secondsLeft: Int) : OpenResult
    data class Rejected(val message: String) : OpenResult
    /** Nothing reached the server. */
    data object NetworkError : OpenResult

    /** The server answered, but not usefully; [detail] says how. */
    data class ServerError(val detail: String) : OpenResult

    data object SignInRequired : OpenResult

    /**
     * The session is gone and the stored credentials can no longer take its
     * place, because the server has tied the account to a different device.
     * Signing in again needs a fresh PIN.
     */
    data class DeviceNotTrusted(val message: String) : OpenResult
}

/**
 * The one place that knows how to open a door.
 *
 * Both the phone and the car screen share a single instance, which is what makes
 * the cooldown and the session renewal hold whichever surface the driver used.
 * Results name what happened rather than carrying UI text, so each surface can
 * word things its own way — except messages the server already wrote in Russian,
 * which are passed through untouched.
 *
 * [clock] must be monotonic; see [OPEN_COOLDOWN_MILLIS].
 */
class DoorRepository(
    private val api: AmVideoApi,
    private val store: AccountStore,
    private val clock: () -> Long,
) {

    private val openLock = Mutex()
    private val renewalLock = Mutex()
    private val pinLock = Mutex()

    @Volatile
    private var lastOpenAt: Long? = null

    @Volatile
    private var lastPinRequestAt: Long? = null

    /**
     * Bumped on sign-out. Work already in flight checks it before persisting, so
     * a login or a door list that lands after the user signed out cannot quietly
     * put the account back.
     */
    @Volatile
    private var generation = 0

    val isSignedIn: Boolean
        get() = !store.sid.isNullOrEmpty()

    /**
     * The current session, so it can be carried to another device by hand.
     *
     * Treat it as a credential: anything holding it can open the doors until it
     * expires.
     */
    val sessionToken: String?
        get() = store.sid

    /** Whether the driver wants a prompt when the car reaches a КПУ. */
    var arrivalEnabled: Boolean
        get() = store.arrivalEnabled
        set(value) {
            store.arrivalEnabled = value
        }

    /** The last known doors, available without touching the network. */
    fun cachedDoors(): List<Door> = store.doors

    suspend fun signIn(phone: String, password: String, pin: String = ""): SignInResult {
        val response = try {
            api.login(phone, password, store.deviceKey, pin)
        } catch (e: ApiException) {
            return SignInResult.ServerError(e.message.orEmpty())
        } catch (e: IOException) {
            return SignInResult.NetworkError
        }

        val sid = response.sid
        return when {
            response.result && !sid.isNullOrEmpty() -> {
                store.sid = sid
                store.phone = phone
                store.password = password
                SignInResult.Success
            }

            response.changeDevice -> SignInResult.PinRequired(response.str.orEmpty())
            else -> SignInResult.Rejected(response.str.orEmpty())
        }
    }

    /**
     * Asks for a PIN so the user can finish signing in on a device the server
     * does not know yet. Nothing else sends one, so without this the sign-in
     * form asks for a code that can never arrive.
     */
    suspend fun requestPin(phone: String): PinRequestResult = pinLock.withLock {
        val since = lastPinRequestAt?.let { clock() - it }
        if (since != null && since in 0 until PIN_COOLDOWN_MILLIS) {
            return@withLock PinRequestResult.Cooldown(secondsLeftAfter(since, PIN_COOLDOWN_MILLIS))
        }

        // Asking twice is how the vendor's client escalates from a voice call to
        // an SMS, so a second request in the same session is a repeat.
        val repeat = lastPinRequestAt != null
        lastPinRequestAt = clock()

        val response = try {
            api.requestPin(phone, repeat)
        } catch (e: ApiException) {
            lastPinRequestAt = null
            return@withLock PinRequestResult.ServerError(e.message.orEmpty())
        } catch (e: IOException) {
            // Unlike an open, release the wait: a duplicate PIN costs the user
            // nothing, while three minutes of silence after a failed request
            // leaves them unable to sign in at all.
            lastPinRequestAt = null
            return@withLock PinRequestResult.NetworkError
        }

        if (response.result == false) {
            PinRequestResult.Rejected(response.str.orEmpty())
        } else {
            PinRequestResult.Sent(response.str.orEmpty())
        }
    }

    /**
     * Adopts a session obtained elsewhere, instead of signing in.
     *
     * The server caps logins per day and keeps one session per account, so moving
     * an existing session to a second device is strictly cheaper than logging in
     * again — and it is the only way to run two devices without them evicting
     * each other.
     *
     * The token is checked by asking for the door list with it, which doubles as
     * the first fetch. No password is stored, so nothing will silently log in
     * later: an expired token comes back to the user rather than spending one of
     * the day's logins behind their back.
     */
    suspend fun signInWithToken(token: String): SignInResult {
        val candidate = token.trim()
        if (candidate.isEmpty()) return SignInResult.Rejected("")

        val response = try {
            api.panels(candidate)
        } catch (e: ApiException) {
            return SignInResult.ServerError(e.message.orEmpty())
        } catch (e: IOException) {
            return SignInResult.NetworkError
        }
        if (!response.result) return SignInResult.Rejected(response.str.orEmpty())

        // The token may belong to a different account, so nothing from the old one
        // survives, and work already in flight must not write over it.
        generation++
        store.phone = null
        store.password = null
        store.sid = candidate
        store.doors = response.data.orEmpty().mapNotNull { it.toDoorOrNull() }
        return SignInResult.Success
    }

    fun signOut() {
        generation++
        store.clear()
        lastOpenAt = null
        lastPinRequestAt = null
    }

    /** Fetches the door list and, on success, refreshes the cache. */
    suspend fun refreshDoors(): DoorsResult {
        val generationAtStart = generation
        return when (val call = withSession { api.panels(it) }) {
            SessionCall.Network -> DoorsResult.NetworkError
            is SessionCall.Server -> DoorsResult.ServerError(call.detail)
            is SessionCall.NotTrusted -> DoorsResult.DeviceNotTrusted(call.message)
            SessionCall.NoSession -> DoorsResult.SignInRequired
            is SessionCall.Answered -> {
                val body = call.value
                if (!body.result) {
                    DoorsResult.Rejected(body.str.orEmpty())
                } else {
                    val doors = body.data.orEmpty().mapNotNull { it.toDoorOrNull() }
                    if (generation == generationAtStart) store.doors = doors
                    DoorsResult.Loaded(doors)
                }
            }
        }
    }

    /**
     * Opens [door], unless the cooldown is still running.
     *
     * The lock serialises opens across the phone and the car, so a second tap
     * waits for the first answer instead of racing it.
     */
    suspend fun open(door: Door): OpenResult = openLock.withLock {
        if (!isSignedIn) return@withLock OpenResult.SignInRequired

        val since = lastOpenAt?.let { clock() - it }
        if (since != null && since in 0 until OPEN_COOLDOWN_MILLIS) {
            return@withLock OpenResult.Cooldown(secondsLeftAfter(since, OPEN_COOLDOWN_MILLIS))
        }

        // Armed before the request goes out, as the vendor's own client does.
        // Once the call is on the wire the relay may fire whatever the app hears
        // back — a read timeout, a dropped connection or Android Auto
        // disconnecting must not let the next tap open the gate a second time.
        lastOpenAt = clock()

        when (val call = withSession { api.open(it, door.id, door.relay) }) {
            SessionCall.Network -> OpenResult.NetworkError
            is SessionCall.Server -> OpenResult.ServerError(call.detail)
            is SessionCall.NotTrusted -> OpenResult.DeviceNotTrusted(call.message)
            SessionCall.NoSession -> OpenResult.SignInRequired
            is SessionCall.Answered -> {
                val body = call.value
                if (body.result == false) {
                    OpenResult.Rejected(body.str.orEmpty())
                } else {
                    OpenResult.Sent(body.str.orEmpty())
                }
            }
        }
    }

    /**
     * Runs [call] with the stored session. If the server says the session is gone,
     * signs in again with the stored credentials and retries once — without this a
     * car that has not been driven for a while would show an empty grid until the
     * driver picked up the phone.
     */
    private suspend fun <T : ApiAnswer> withSession(call: suspend (sid: String) -> T): SessionCall<T> {
        val sid = store.sid
        if (sid.isNullOrEmpty()) return SessionCall.NoSession

        val first = attempt(sid, call)
        if (first !is SessionCall.Answered || !first.value.isSessionLost()) return first

        return when (val renewal = renewSession(staleSid = sid)) {
            is Renewal.Unavailable ->
                renewal.detail?.let { SessionCall.Server(it) } ?: SessionCall.Network

            is Renewal.NotTrusted -> SessionCall.NotTrusted(renewal.message)
            Renewal.Dead -> SessionCall.NoSession
            is Renewal.Renewed -> attempt(renewal.sid, call).let { second ->
                if (second is SessionCall.Answered && second.value.isSessionLost()) {
                    SessionCall.NoSession
                } else {
                    second
                }
            }
        }
    }

    private suspend fun <T : ApiAnswer> attempt(
        sid: String,
        call: suspend (sid: String) -> T,
    ): SessionCall<T> = try {
        SessionCall.Answered(call(sid))
    } catch (e: ApiException) {
        SessionCall.Server(e.message.orEmpty())
    } catch (e: IOException) {
        SessionCall.Network
    }

    /**
     * Signs in again after the server rejected [staleSid].
     *
     * Serialised, because the phone and the car can both meet the same dead
     * session at once: the second caller finds the session id already replaced and
     * reuses it rather than logging in again, which on a server that keeps one
     * session per account would invalidate the first.
     *
     * A renewal that cannot reach the server is [Renewal.Network], not
     * [Renewal.Dead]: the credentials are still good as far as anyone knows, and
     * reporting a dead session would throw the driver back to the sign-in form
     * over a moment of bad coverage in a garage.
     */
    private suspend fun renewSession(staleSid: String): Renewal = renewalLock.withLock {
        store.sid?.takeIf { it.isNotEmpty() && it != staleSid }?.let { return@withLock Renewal.Renewed(it) }

        val phone = store.phone
        val password = store.password
        if (phone.isNullOrEmpty() || password.isNullOrEmpty()) {
            store.sid = null
            return@withLock Renewal.Dead
        }

        val generationAtStart = generation
        when (val result = signIn(phone, password)) {
            SignInResult.Success -> when {
                // Signed out while this login was in flight; don't revive it.
                generation != generationAtStart -> {
                    store.clear()
                    Renewal.Dead
                }

                else -> store.sid?.let { Renewal.Renewed(it) } ?: Renewal.Dead
            }

            SignInResult.NetworkError -> Renewal.Unavailable(null)
            is SignInResult.ServerError -> Renewal.Unavailable(result.detail)

            is SignInResult.PinRequired -> {
                store.sid = null
                Renewal.NotTrusted(result.message)
            }

            else -> {
                store.sid = null
                Renewal.Dead
            }
        }
    }

    private fun secondsLeftAfter(elapsed: Long, cooldown: Long): Int =
        ((cooldown - elapsed + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()

    private sealed interface SessionCall<out T> {
        data class Answered<T>(val value: T) : SessionCall<T>

        /** The session is gone and could not be renewed. */
        data object NoSession : SessionCall<Nothing>

        data object Network : SessionCall<Nothing>

        data class Server(val detail: String) : SessionCall<Nothing>

        /** The account has moved to another device; only a new PIN gets it back. */
        data class NotTrusted(val message: String) : SessionCall<Nothing>
    }

    private sealed interface Renewal {
        data class Renewed(val sid: String) : Renewal

        /**
         * Could not finish; the stored session and credentials stand. [detail] is
         * set when the server answered badly rather than being unreachable.
         */
        data class Unavailable(val detail: String?) : Renewal

        /** The account can no longer sign in; the session has been cleared. */
        data object Dead : Renewal

        /** The server wants a PIN, because this is no longer the device it knows. */
        data class NotTrusted(val message: String) : Renewal
    }

    private companion object {
        /**
         * The web client blocks the button for 20 s after an open, so the same
         * wait is kept here rather than letting the car hammer the gate.
         *
         * Measured on the injected clock, which must be monotonic: with wall-clock
         * time an NTP correction stepping backwards would lock the gate for as
         * long as the step.
         */
        const val OPEN_COOLDOWN_MILLIS = 20_000L

        /** The vendor's client allows one PIN request every three minutes. */
        const val PIN_COOLDOWN_MILLIS = 180_000L
        const val MILLIS_PER_SECOND = 1_000L

        /**
         * api4.php has no status codes — an expired session is just `result:false`
         * with this message, so matching the text is the only signal available.
         */
        const val NO_ACCESS_MARKER = "нет доступа"

        fun ApiAnswer.isSessionLost(): Boolean =
            accepted == false && message?.contains(NO_ACCESS_MARKER, ignoreCase = true) == true
    }
}
