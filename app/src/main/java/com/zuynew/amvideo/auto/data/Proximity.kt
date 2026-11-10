package com.zuynew.amvideo.auto.data

/**
 * A door the car is currently inside the radius of.
 *
 * [approaching] is true when the car is pointed at it rather than away. It only
 * ever affects the order: a door is never hidden on the strength of a heading,
 * because a stale one would take away the gate the driver is sitting at.
 */
data class NearbyDoor(
    val door: Door,
    val distanceMeters: Double,
    val approaching: Boolean,
)

/**
 * Decides which doors count as nearby. Pure, so the rules can be tested without a
 * device or a GPS.
 */
object Proximity {

    /**
     * How close counts as arrived, unless the driver picks otherwise.
     *
     * 50 m is roughly the last few seconds of the approach at town speed, which
     * is when the prompt is wanted. A wider radius fires while merely driving
     * past, which is what it used to do.
     */
    const val DEFAULT_RADIUS_METERS = 50

    /**
     * What the phone offers. Whole metres: the driver is choosing how late the
     * prompt arrives, not calibrating anything.
     */
    val RADIUS_CHOICES_METERS = listOf(50, 100, 150, 300)

    /** How far off a straight line to the door still counts as heading for it. */
    const val APPROACH_TOLERANCE_DEGREES = 45.0

    /**
     * A door stays "already announced" until the car is this many radii away, so
     * that parking inside the zone does not re-announce it over and over.
     */
    const val RELEASE_FACTOR = 2.0

    /**
     * @return every door within [radiusMeters] of [fix], the ones being driven
     *   towards first and nearest first within that. Doors with no coordinates are
     *   never included — there is no way to tell where they are.
     */
    fun inRange(
        doors: List<Door>,
        fix: Fix,
        radiusMeters: Double = DEFAULT_RADIUS_METERS.toDouble(),
    ): List<NearbyDoor> =
        doors.mapNotNull { door -> door.nearbyOrNull(fix, radiusMeters) }
            .sortedWith(compareByDescending<NearbyDoor> { it.approaching }.thenBy { it.distanceMeters })

    private fun Door.nearbyOrNull(fix: Fix, radiusMeters: Double): NearbyDoor? {
        val target = location ?: return null
        val distance = Geo.distanceMeters(fix.point, target)
        if (distance > radiusMeters) return null

        val heading = fix.heading
        val approaching = heading == null || Geo.angleDifference(
            Geo.bearingDegrees(fix.point, target),
            heading,
        ) <= APPROACH_TOLERANCE_DEGREES

        return NearbyDoor(door = this, distanceMeters = distance, approaching = approaching)
    }
}

/**
 * Remembers which doors have already been announced on this drive.
 *
 * Entering a zone should prompt the driver once, not on every location update, and
 * the flag has to survive sitting at the gate — so it is only cleared once the car
 * has driven [Proximity.RELEASE_FACTOR] radii away, well outside the jitter of a
 * stationary fix.
 */
class ProximityTracker {

    private val announced = mutableSetOf<String>()

    /**
     * What the latest fix means.
     *
     * [candidates] are in range and not yet announced. The caller decides which
     * one it actually shows and calls [markAnnounced] for that one only — marking
     * them all here would silence the second gate of a pair for the rest of the
     * drive without ever having told the driver about it.
     */
    data class Update(
        val inRange: List<NearbyDoor>,
        val candidates: List<NearbyDoor>,
    )

    fun update(
        doors: List<Door>,
        fix: Fix,
        radiusMeters: Double = Proximity.DEFAULT_RADIUS_METERS.toDouble(),
    ): Update {
        // Anything no longer within the wider release radius is armed again. Doors
        // that have vanished from the list entirely drop out here too, rather than
        // keeping their flag for ever.
        val stillNearby = Proximity
            .inRange(doors, fix, radiusMeters * Proximity.RELEASE_FACTOR)
            .map { it.door.key }
            .toSet()
        announced.retainAll(stillNearby)

        val inRange = Proximity.inRange(doors, fix, radiusMeters)
        return Update(
            inRange = inRange,
            candidates = inRange.filterNot { it.door.key in announced },
        )
    }

    /** Records that the driver has been told about [door] on this visit. */
    fun markAnnounced(door: Door) {
        announced += door.key
    }

    /** Starts the next drive with a clean slate. */
    fun forget() {
        announced.clear()
    }
}
