package com.zuynew.amvideo.auto.data

import kotlinx.serialization.Serializable

/**
 * One КПУ (контрольно-пропускное устройство) — a gate, barrier or door the
 * signed-in account is allowed to open.
 *
 * [id] and [relay] are passed straight back to the server when opening; both are
 * kept as text because the back end is loose about whether it sends them as
 * numbers or strings.
 */
@Serializable
data class Door(
    val id: String,
    val address: String,
    val relay: String,
    /** Where the КПУ stands, when the server knows; many rows have no coordinates. */
    val location: GeoPoint? = null,
) {
    /**
     * Identity for lists and for the "currently opening" marker. One gate can
     * appear twice with two relays, so [id] alone is not unique.
     */
    val key: String get() = "$id:$relay"
}
