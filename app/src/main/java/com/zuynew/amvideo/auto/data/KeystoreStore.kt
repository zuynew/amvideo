package com.zuynew.amvideo.auto.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the account in app-private preferences, with every secret encrypted
 * under an AES-256-GCM key held in the Android keystore.
 *
 * This is done by hand rather than with Jetpack Security because
 * `EncryptedSharedPreferences` was deprecated in favour of plain, unencrypted
 * preferences — and the password here opens the user's front door.
 *
 * Values are decrypted once into memory and written through. Reading on demand
 * would put a keystore round trip on the main thread every time the car rebuilds
 * its template, and holding them also means a failed *write* still leaves the
 * driver able to open the gate for the rest of the trip.
 *
 * The keystore key cannot leave the device, so the manifest excludes this file
 * from both cloud backup and device-to-device transfer rather than let the app
 * carry ciphertext it will never be able to read to another phone. A value that
 * does fail to decrypt is treated as absent: that costs one sign-in instead of
 * leaving the app wedged.
 */
class KeystoreStore(
    context: Context,
    private val json: Json = Json,
) : AccountStore {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val secret: SecretKey by lazy { loadOrCreateKey() }

    @Volatile
    private var sidValue: String? = read(KEY_SID)

    @Volatile
    private var phoneValue: String? = read(KEY_PHONE)

    @Volatile
    private var passwordValue: String? = read(KEY_PASSWORD)

    @Volatile
    private var doorsValue: List<Door> = readDoors()

    /**
     * Stored in the clear on purpose: it identifies the install rather than
     * guarding anything, and keeping it readable after a keystore reset saves
     * the driver an SMS PIN they would otherwise have to wait for.
     */
    override val deviceKey: String by lazy {
        prefs.getString(KEY_DEVICE, null) ?: UUID.randomUUID().toString().also { generated ->
            // commit() so a key already handed to the server cannot be lost to a crash.
            prefs.edit(commit = true) { putString(KEY_DEVICE, generated) }
        }
    }

    override var sid: String?
        get() = sidValue
        set(value) {
            sidValue = value
            write(KEY_SID, value)
        }

    override var phone: String?
        get() = phoneValue
        set(value) {
            phoneValue = value
            write(KEY_PHONE, value)
        }

    override var password: String?
        get() = passwordValue
        set(value) {
            passwordValue = value
            write(KEY_PASSWORD, value)
        }

    // Not a secret, and wanted before anything is decrypted.
    override var arrivalEnabled: Boolean
        get() = prefs.getBoolean(KEY_ARRIVAL, false)
        set(value) = prefs.edit { putBoolean(KEY_ARRIVAL, value) }

    // Not a secret either, and read on every location fix.
    override var arrivalRadiusMeters: Int
        get() = prefs.getInt(KEY_ARRIVAL_RADIUS, Proximity.DEFAULT_RADIUS_METERS)
        set(value) = prefs.edit { putInt(KEY_ARRIVAL_RADIUS, value) }

    override var doors: List<Door>
        get() = doorsValue
        set(value) {
            doorsValue = value
            write(KEY_DOORS, json.encodeToString(value))
        }

    override fun clear() {
        sidValue = null
        phoneValue = null
        passwordValue = null
        doorsValue = emptyList()
        prefs.edit {
            remove(KEY_SID)
            remove(KEY_PHONE)
            remove(KEY_PASSWORD)
            remove(KEY_DOORS)
        }
    }

    private fun readDoors(): List<Door> {
        val stored = read(KEY_DOORS) ?: return emptyList()
        // A cache that cannot be read is simply an empty cache.
        return runCatching { json.decodeFromString<List<Door>>(stored) }.getOrDefault(emptyList())
    }

    private fun read(key: String): String? = prefs.getString(key, null)?.let { decrypt(it) }

    /**
     * Best effort. A keystore that refuses to encrypt costs the next launch a
     * sign-in; it must not take down the app at the gate.
     */
    private fun write(key: String, value: String?) {
        runCatching {
            prefs.edit {
                if (value == null) remove(key) else putString(key, encrypt(value))
            }
        }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secret)
        // The keystore insists on generating the IV itself, so it is stored
        // alongside the ciphertext.
        val payload = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = runCatching {
        val payload = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secret,
            GCMParameterSpec(TAG_BITS, payload, 0, IV_BYTES),
        )
        String(cipher.doFinal(payload, IV_BYTES, payload.size - IV_BYTES))
    }.getOrNull()

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "amvideo_account"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "amvideo_account_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BITS = 256
        const val TAG_BITS = 128
        const val IV_BYTES = 12

        const val KEY_DEVICE = "device_key"
        const val KEY_SID = "sid"
        const val KEY_PHONE = "phone"
        const val KEY_PASSWORD = "password"
        const val KEY_DOORS = "doors"
        const val KEY_ARRIVAL = "arrival_enabled"
        const val KEY_ARRIVAL_RADIUS = "arrival_radius_meters"
    }
}
