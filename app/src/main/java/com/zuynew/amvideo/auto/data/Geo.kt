package com.zuynew.amvideo.auto.data

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the ground, in degrees. */
@Serializable
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * Where the car is and which way it is pointed.
 *
 * [bearingDegrees] and [speedMetersPerSecond] are null when the fix did not carry
 * them; a parked phone reports a heading that is mostly noise, which is why speed
 * is kept alongside it.
 */
data class Fix(
    val point: GeoPoint,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
) {
    /**
     * A heading is only worth believing once the car is actually moving. The floor
     * is deliberately low so a slow crawl towards a gate still counts.
     */
    val heading: Double?
        get() = bearingDegrees?.takeIf {
            speedMetersPerSecond == null || speedMetersPerSecond >= MIN_SPEED_FOR_BEARING
        }

    companion object {
        /** About 1 km/h. */
        const val MIN_SPEED_FOR_BEARING = 0.3
    }
}

/**
 * Plain geography, with no Android in it, so the proximity rules can be tested on
 * the JVM rather than on a device.
 */
object Geo {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Great-circle distance, by the haversine formula. */
    fun distanceMeters(from: GeoPoint, to: GeoPoint): Double {
        val phi1 = Math.toRadians(from.latitude)
        val phi2 = Math.toRadians(to.latitude)
        val deltaPhi = Math.toRadians(to.latitude - from.latitude)
        val deltaLambda = Math.toRadians(to.longitude - from.longitude)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        return EARTH_RADIUS_METERS * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial compass bearing from [from] towards [to], in degrees from north. */
    fun bearingDegrees(from: GeoPoint, to: GeoPoint): Double {
        val phi1 = Math.toRadians(from.latitude)
        val phi2 = Math.toRadians(to.latitude)
        val deltaLambda = Math.toRadians(to.longitude - from.longitude)

        val y = sin(deltaLambda) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /** The smaller angle between two compass bearings, 0°–180°. */
    fun angleDifference(a: Double, b: Double): Double {
        val diff = abs(normalizeDegrees(a) - normalizeDegrees(b))
        return if (diff > 180.0) 360.0 - diff else diff
    }

    fun normalizeDegrees(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
}
