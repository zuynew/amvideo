package com.zuynew.amvideo.auto.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to api4.php over form-encoded POSTs.
 *
 * Timeouts are deliberately short: at a gate a quick failure the driver can
 * retry beats a request that hangs while the car waits.
 */
class HttpAmVideoApi(
    private val client: OkHttpClient = defaultClient(),
    private val endpoint: HttpUrl = ENDPOINT.toHttpUrl(),
    private val json: Json = defaultJson(),
) : AmVideoApi {

    override suspend fun login(
        phone: String,
        password: String,
        deviceKey: String,
        pin: String,
    ): LoginResponse = post(
        LoginResponse.serializer(),
        type = "login",
        "login" to phone,
        "password" to password,
        "device_key" to deviceKey,
        "pin" to pin,
    )

    override suspend fun requestPin(phone: String, repeat: Boolean): PinResponse = post(
        PinResponse.serializer(),
        type = "pin",
        "login" to phone,
        // The vendor's client also uses "reg" and "forgot" here; this app only
        // ever signs in an existing account on a new device.
        "pin_type" to "device",
        "sms" to if (repeat) "1" else "0",
    )

    override suspend fun panels(sid: String): PanelsResponse = post(
        PanelsResponse.serializer(),
        type = "panels",
        "sid" to sid,
    )

    override suspend fun open(sid: String, doorId: String, relay: String): OpenResponse = post(
        OpenResponse.serializer(),
        type = "open",
        "sid" to sid,
        "id_shlag" to doorId,
        "relay" to relay,
    )

    private suspend fun <T> post(
        serializer: KSerializer<T>,
        type: String,
        vararg fields: Pair<String, String>,
    ): T = withContext(Dispatchers.IO) {
        val form = FormBody.Builder().add("type", type)
        fields.forEach { (name, value) -> form.add(name, value) }

        val request = Request.Builder()
            .url(endpoint)
            .post(form.build())
            .build()

        val body = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            // The UI can only say "no connection"; the cause belongs in the log,
            // or a report of it is impossible to act on.
            Log.w(TAG, "'$type' did not reach api4.php", e)
            throw e
        }.use { response ->
            if (!response.isSuccessful) {
                throw ApiException("HTTP ${response.code} ($type)")
            }
            response.body.string()
        }

        try {
            json.decodeFromString(serializer, body)
        } catch (e: Exception) {
            // Show a slice of what did come back: a PHP warning or an HTML error
            // page is the single most useful clue when this goes wrong.
            throw ApiException(
                "$type: " + body.take(ERROR_SNIPPET).replace(Regex("\\s+"), " ").trim(),
            )
        }
    }

    companion object {
        private const val TAG = "AmVideoApi"
        const val ENDPOINT = "https://lk.amvideo-msk.ru/api/api4.php"

        /** Enough of an unparseable body to diagnose it, not enough to flood the UI. */
        private const val ERROR_SNIPPET = 160

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
        }
    }
}
