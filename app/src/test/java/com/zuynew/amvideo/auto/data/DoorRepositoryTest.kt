package com.zuynew.amvideo.auto.data

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

private const val NO_ACCESS = "нет доступа!"

class DoorRepositoryTest {

    private val api = FakeApi()
    private val store = FakeStore()
    private var now = 0L
    private val repository = DoorRepository(api, store) { now }

    private val door = Door(id = "7", address = "Ленина 5", relay = "1")

    @Test
    fun `sign in keeps the session and the credentials needed to renew it`() = runTest {
        api.loginResponse = LoginResponse(result = true, sid = "sid-1")

        val result = repository.signIn("79990000000", "secret")

        assertEquals(SignInResult.Success, result)
        assertEquals("sid-1", store.sid)
        assertEquals("79990000000", store.phone)
        assertEquals("secret", store.password)
        assertTrue(repository.isSignedIn)
    }

    @Test
    fun `an unknown device is reported as needing a pin, and the pin is forwarded`() = runTest {
        api.loginResponse = LoginResponse(result = false, changeDevice = true, str = "введите ПИН")

        val result = repository.signIn("79990000000", "secret", pin = "4321")

        assertEquals(SignInResult.PinRequired("введите ПИН"), result)
        assertEquals("4321", api.lastPin)
        assertNull(store.sid)
    }

    @Test
    fun `a pin can be requested, and the second one asks for an sms`() = runTest {
        val first = repository.requestPin("79990000000")
        now += 180_000
        val second = repository.requestPin("79990000000")

        assertEquals(PinRequestResult.Sent("ПИН выслан"), first)
        assertEquals(PinRequestResult.Sent("ПИН выслан"), second)
        // The first delivery may be a voice call; the repeat forces an SMS.
        assertEquals(listOf(false, true), api.pinRepeats)
    }

    @Test
    fun `a second pin request inside three minutes is refused locally`() = runTest {
        repository.requestPin("79990000000")
        now += 60_000
        val again = repository.requestPin("79990000000")

        assertEquals(PinRequestResult.Cooldown(120), again)
        assertEquals(1, api.pinCount)
    }

    /**
     * The opposite of the gate: a duplicate PIN is harmless, while three minutes
     * of silence after a failed request leaves the user unable to sign in.
     */
    @Test
    fun `a pin request that fails on the network can be retried at once`() = runTest {
        api.offline = true
        val failed = repository.requestPin("79990000000")
        api.offline = false
        val retried = repository.requestPin("79990000000")

        assertEquals(PinRequestResult.NetworkError, failed)
        assertEquals(PinRequestResult.Sent("ПИН выслан"), retried)
    }

    @Test
    fun `a token is adopted without spending a login, and brings the doors with it`() = runTest {
        api.panelsAnswers += PanelsResponse(
            result = true,
            data = listOf(
                buildJsonObject {
                    put("id_shlag", "5")
                    put("adress", "Ленина 5")
                    put("relay", "1")
                },
            ),
        )

        val result = repository.signInWithToken("  sid-from-other-device  ")

        assertEquals(SignInResult.Success, result)
        assertEquals("sid-from-other-device", store.sid)
        assertEquals(0, api.loginCount)
        assertEquals(listOf("5"), repository.cachedDoors().map { it.id })
    }

    /**
     * The whole point of a token is that it costs no logins — now or later. With
     * no password stored, an expired one has to come back to the user.
     */
    @Test
    fun `a token sign-in cannot silently log in again when it expires`() = runTest {
        repository.signInWithToken("sid-from-other-device")
        api.panelsAnswers += PanelsResponse(result = false, str = NO_ACCESS)

        val result = repository.refreshDoors()

        assertEquals(DoorsResult.SignInRequired, result)
        assertEquals(0, api.loginCount)
        assertNull(store.password)
    }

    @Test
    fun `a token the server will not accept is refused in its own words`() = runTest {
        api.panelsAnswers += PanelsResponse(result = false, str = NO_ACCESS)

        val result = repository.signInWithToken("rubbish")

        assertEquals(SignInResult.Rejected(NO_ACCESS), result)
        assertNull(store.sid)
    }

    @Test
    fun `adopting a token drops the previous account's password and doors`() = runTest {
        store.sid = "old"
        store.phone = "79990000000"
        store.password = "secret"
        store.doors = listOf(door)
        api.panelsAnswers += PanelsResponse(result = true, data = emptyList())

        repository.signInWithToken("new")

        assertEquals("new", store.sid)
        assertNull(store.phone)
        assertNull(store.password)
        assertEquals(emptyList<Door>(), repository.cachedDoors())
    }

    @Test
    fun `the session token is what gets handed to the next device`() = runTest {
        assertNull(repository.sessionToken)
        store.sid = "sid-1"
        assertEquals("sid-1", repository.sessionToken)
    }

    @Test
    fun `opening without a session asks for sign-in instead of calling the server`() = runTest {
        val result = repository.open(door)

        assertEquals(OpenResult.SignInRequired, result)
        assertEquals(0, api.openCount)
    }

    @Test
    fun `a second open within the cooldown is refused locally`() = runTest {
        store.sid = "sid-1"

        val first = repository.open(door)
        now += 5_000
        val second = repository.open(door)

        assertTrue(first is OpenResult.Sent)
        assertEquals(OpenResult.Cooldown(15), second)
        assertEquals(1, api.openCount)
    }

    @Test
    fun `the gate can be opened again once the cooldown has passed`() = runTest {
        store.sid = "sid-1"

        repository.open(door)
        now += 20_000
        val again = repository.open(door)

        assertTrue(again is OpenResult.Sent)
        assertEquals(2, api.openCount)
    }

    /**
     * The request may have reached the relay even though the answer never came
     * back, so the cooldown has to hold or the next tap opens the gate twice.
     */
    @Test
    fun `a request that fails after being sent still starts the cooldown`() = runTest {
        store.sid = "sid-1"
        api.offline = true

        val failed = repository.open(door)
        api.offline = false
        val retried = repository.open(door)

        assertEquals(OpenResult.NetworkError, failed)
        assertEquals(OpenResult.Cooldown(20), retried)
    }

    @Test
    fun `a clock that steps backwards does not lock the gate`() = runTest {
        store.sid = "sid-1"

        repository.open(door)
        now -= 3_600_000

        assertTrue(repository.open(door) is OpenResult.Sent)
    }

    @Test
    fun `an expired session is renewed and the open retried`() = runTest {
        store.sid = "sid-stale"
        store.phone = "79990000000"
        store.password = "secret"
        api.openAnswers += OpenResponse(result = false, str = NO_ACCESS)
        api.openAnswers += OpenResponse(result = true, str = "открыто")
        api.loginResponse = LoginResponse(result = true, sid = "sid-fresh")

        val result = repository.open(door)

        assertEquals(OpenResult.Sent("открыто"), result)
        assertEquals("sid-fresh", store.sid)
        assertEquals(1, api.loginCount)
        assertEquals(2, api.openCount)
        assertEquals("sid-fresh", api.lastOpenSid)
    }

    @Test
    fun `an expired session with nothing to renew from clears the session`() = runTest {
        store.sid = "sid-stale"
        api.openAnswers += OpenResponse(result = false, str = NO_ACCESS)

        val result = repository.open(door)

        assertEquals(OpenResult.SignInRequired, result)
        assertNull(store.sid)
        assertFalse(repository.isSignedIn)
        assertEquals(0, api.loginCount)
    }

    /**
     * Losing the network while renewing must not look like a dead account, or a
     * moment of bad coverage in a garage throws the driver back to sign-in.
     */
    @Test
    fun `a renewal that cannot reach the server reports a network error and keeps the account`() = runTest {
        store.sid = "sid-stale"
        store.phone = "79990000000"
        store.password = "secret"

        // Says the session is gone, then cannot be reached to sign in again.
        val unreachable = object : AmVideoApi {
            override suspend fun login(
                phone: String,
                password: String,
                deviceKey: String,
                pin: String,
            ): LoginResponse = throw IOException("offline")

            override suspend fun requestPin(phone: String, repeat: Boolean) = PinResponse()

            override suspend fun panels(sid: String) = PanelsResponse(result = false, str = NO_ACCESS)

            override suspend fun open(sid: String, doorId: String, relay: String) = OpenResponse()
        }

        val result = DoorRepository(unreachable, store) { now }.refreshDoors()

        assertEquals(DoorsResult.NetworkError, result)
        assertEquals("sid-stale", store.sid)
        assertEquals("secret", store.password)
    }

    /**
     * An HTTP status or a PHP warning is the server's problem, not the phone's.
     * Reporting it as "no connection" would send the user chasing their signal.
     */
    @Test
    fun `a bad answer from the server is reported as a server error, not a network one`() = runTest {
        store.sid = "sid-1"
        api.serverError = "HTTP 500 (open)"

        assertEquals(OpenResult.ServerError("HTTP 500 (open)"), repository.open(door))
        assertEquals(SignInResult.ServerError("HTTP 500 (open)"), repository.signIn("79990000000", "x"))
        assertEquals(DoorsResult.ServerError("HTTP 500 (open)"), repository.refreshDoors())
        assertEquals(PinRequestResult.ServerError("HTTP 500 (open)"), repository.requestPin("79990000000"))
    }

    /** The request did reach the gate, so the wait has to stand. */
    @Test
    fun `a server error still starts the open cooldown`() = runTest {
        store.sid = "sid-1"
        api.serverError = "HTTP 503 (open)"
        repository.open(door)

        api.serverError = null
        assertEquals(OpenResult.Cooldown(20), repository.open(door))
    }

    /**
     * Exactly the state seen on the emulator after signing in elsewhere: the
     * session id is dropped, the credentials stay, and the device key is
     * untouched — reporting this as a plain expiry would send the user round a
     * sign-in attempt that is bound to fail.
     */
    @Test
    fun `a re-login refused as a new device is reported as such, not as an expiry`() = runTest {
        store.sid = "sid-stale"
        store.phone = "79990000000"
        store.password = "secret"
        api.panelsAnswers += PanelsResponse(result = false, str = NO_ACCESS)
        api.loginResponse = LoginResponse(result = false, changeDevice = true, str = "нужен ПИН")

        val result = repository.refreshDoors()

        assertEquals(DoorsResult.DeviceNotTrusted("нужен ПИН"), result)
        assertNull(store.sid)
        assertEquals("79990000000", store.phone)
        assertEquals("secret", store.password)
        assertEquals("device-1", store.deviceKey)
    }

    @Test
    fun `opening the gate reports the same when the device is no longer trusted`() = runTest {
        store.sid = "sid-stale"
        store.phone = "79990000000"
        store.password = "secret"
        api.openAnswers += OpenResponse(result = false, str = NO_ACCESS)
        api.loginResponse = LoginResponse(result = false, changeDevice = true, str = "нужен ПИН")

        assertEquals(OpenResult.DeviceNotTrusted("нужен ПИН"), repository.open(door))
    }

    @Test
    fun `a refusal from the gate is passed through in the server's own words`() = runTest {
        store.sid = "sid-1"
        api.openAnswers += OpenResponse(result = false, str = "КПУ недоступно")

        val result = repository.open(door)

        assertEquals(OpenResult.Rejected("КПУ недоступно"), result)
        assertEquals(0, api.loginCount)
    }

    @Test
    fun `an open with no verdict is treated as sent`() = runTest {
        store.sid = "sid-1"
        api.openAnswers += OpenResponse(result = null, str = "команда принята")

        val result = repository.open(door)

        assertEquals(OpenResult.Sent("команда принята"), result)
    }

    @Test
    fun `refreshing doors caches them and tolerates numeric fields`() = runTest {
        store.sid = "sid-1"
        api.panelsAnswers += PanelsResponse(
            result = true,
            data = listOf(
                buildJsonObject {
                    put("id_shlag", 42)
                    put("adress", "Ленина 5")
                    put("relay", 1)
                },
                // No id, so there is nothing openable here.
                buildJsonObject { put("adress", "Без идентификатора") },
            ),
        )

        val result = repository.refreshDoors()

        val expected = listOf(Door(id = "42", address = "Ленина 5", relay = "1"))
        assertEquals(DoorsResult.Loaded(expected), result)
        assertEquals(expected, repository.cachedDoors())
    }

    @Test
    fun `one gate on two relays yields two distinct keys`() = runTest {
        store.sid = "sid-1"
        api.panelsAnswers += PanelsResponse(
            result = true,
            data = listOf(
                buildJsonObject {
                    put("id_shlag", "9")
                    put("adress", "Въезд")
                    put("relay", "1")
                },
                buildJsonObject {
                    put("id_shlag", "9")
                    put("adress", "Выезд")
                    put("relay", "2")
                },
            ),
        )

        val doors = (repository.refreshDoors() as DoorsResult.Loaded).doors

        assertEquals(2, doors.map { it.key }.distinct().size)
    }

    @Test
    fun `signing out forgets the session and the cached doors`() = runTest {
        store.sid = "sid-1"
        store.doors = listOf(door)

        repository.signOut()

        assertTrue(store.cleared)
        assertFalse(repository.isSignedIn)
        assertEquals(emptyList<Door>(), repository.cachedDoors())
    }

    @Test
    fun `signing out releases the cooldown`() = runTest {
        store.sid = "sid-1"
        repository.open(door)
        repository.signOut()
        store.sid = "sid-2"

        assertTrue(repository.open(door) is OpenResult.Sent)
    }
}
