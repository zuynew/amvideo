@file:UseSerializers(LenientBooleanSerializer::class)

package com.zuynew.amvideo.auto.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The same flag arrives from api4.php as `true`, `1` or `"1"` depending on which
 * branch of the PHP built the answer, so every boolean in this file is read
 * through here instead of trusting one shape.
 */
object LenientBooleanSerializer : KSerializer<Boolean> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        val primitive = element as? JsonPrimitive ?: return false
        return primitive.asFlag()
    }

    override fun serialize(encoder: Encoder, value: Boolean) = encoder.encodeBoolean(value)
}

/**
 * An answer to a request that carried a session id. [accepted] is null when the
 * server gave no verdict at all, which some requests do on success.
 */
interface ApiAnswer {
    val accepted: Boolean?
    val message: String?
}

/** Login answers `result:false` plus `change_device` when the device is unknown. */
@Serializable
data class LoginResponse(
    val result: Boolean = false,
    val str: String? = null,
    val sid: String? = null,
    @SerialName("change_device") val changeDevice: Boolean = false,
)

/**
 * The web client shows `str` from an open without ever looking at `result`, which
 * suggests the server may omit the flag on success. [result] is therefore nullable:
 * absent means "no verdict given", which is treated as accepted, while an explicit
 * `false` is a real refusal.
 */
@Serializable
data class OpenResponse(
    val result: Boolean? = null,
    val str: String? = null,
) : ApiAnswer {
    override val accepted: Boolean? get() = result
    override val message: String? get() = str
}

/** The web client shows `str` from a PIN request and ignores any verdict. */
@Serializable
data class PinResponse(
    val result: Boolean? = null,
    val str: String? = null,
)

/**
 * Panel rows come straight out of the vendor's database, so each column may be a
 * string, a number or a bool. They are kept as raw JSON and read through [text]
 * rather than being typed up front, which would turn a harmless type change on
 * the server into a crash at the gate.
 */
@Serializable
data class PanelsResponse(
    val result: Boolean = false,
    val str: String? = null,
    val data: List<JsonObject>? = null,
) : ApiAnswer {
    override val accepted: Boolean get() = result
    override val message: String? get() = str
}

private fun JsonPrimitive.asFlag(): Boolean {
    if (this is JsonNull) return false
    booleanOrNull?.let { return it }
    intOrNull?.let { return it != 0 }
    // Quoted "true"/"0" are handled above; anything else non-empty counts as set.
    return content.isNotEmpty()
}

/** Reads [key] as text whether the server sent a string, a number or a bool. */
internal fun JsonObject.text(key: String): String? {
    val primitive = this[key] as? JsonPrimitive ?: return null
    if (primitive is JsonNull) return null
    return primitive.content
}

/**
 * Maps one panel row to a [Door], or null if it has no usable id.
 *
 * `adress` is the server's spelling, not a typo on this side — but it is the
 * noisy form, "1 шлагбаум (Примерная улица, 1к2)(10001)", with the
 * SIP id tacked on. `shlag_adress` carries just the street, which is what fits on
 * a car tile, so it wins where the server supplies it.
 */
internal fun JsonObject.toDoorOrNull(): Door? {
    val id = text("id_shlag")?.takeIf { it.isNotBlank() && it != "0" } ?: return null
    val address = text("shlag_adress")?.takeIf { it.isNotBlank() }
        ?: text("adress")?.takeIf { it.isNotBlank() }
        ?: id
    return Door(
        id = id,
        address = address,
        relay = text("relay")?.takeIf { it.isNotBlank() } ?: "0",
        location = readLocation(),
    )
}

/**
 * Rows carry the position twice: as separate `latitude` and `longitude` columns
 * and as a combined `gps` string. The separate pair is preferred because it needs
 * no splitting, with `gps` kept as the fallback the vendor's own client uses.
 */
private fun JsonObject.readLocation(): GeoPoint? {
    val latitude = text("latitude")?.trim()?.toDoubleOrNull()
    val longitude = text("longitude")?.trim()?.toDoubleOrNull()
    if (latitude != null && longitude != null) {
        return geoPointOrNull(latitude, longitude)
    }
    return parseGps(text("gps"))
}

/**
 * The `gps` column arrives as "lat, long", or as "0" or an empty string for a КПУ
 * whose position was never recorded. Anything that does not parse is treated the
 * same as absent: a door without coordinates simply never counts as nearby.
 */
internal fun parseGps(raw: String?): GeoPoint? {
    val parts = raw?.takeIf { it.isNotBlank() && it != "0" }?.split(",") ?: return null
    if (parts.size != 2) return null
    val latitude = parts[0].trim().toDoubleOrNull() ?: return null
    val longitude = parts[1].trim().toDoubleOrNull() ?: return null
    return geoPointOrNull(latitude, longitude)
}

private fun geoPointOrNull(latitude: Double, longitude: Double): GeoPoint? {
    // (0, 0) is in the Atlantic, so the server means "unset" rather than there.
    if (latitude == 0.0 && longitude == 0.0) return null
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return GeoPoint(latitude, longitude)
}
