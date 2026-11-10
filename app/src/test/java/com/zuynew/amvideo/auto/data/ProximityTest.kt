package com.zuynew.amvideo.auto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Red Square, as a stable origin to measure from. */
private val ORIGIN = GeoPoint(55.753930, 37.620795)

/** Roughly 100 m north of [ORIGIN]; one degree of latitude is about 111 km. */
private val NORTH_100M = GeoPoint(ORIGIN.latitude + 0.0009, ORIGIN.longitude)

private fun doorAt(id: String, point: GeoPoint?) =
    Door(id = id, address = "КПУ $id", relay = "1", location = point)

class GeoTest {

    @Test
    fun `distance to the same point is zero`() {
        assertEquals(0.0, Geo.distanceMeters(ORIGIN, ORIGIN), 0.001)
    }

    @Test
    fun `a tenth of a kilometre north measures about a hundred metres`() {
        assertEquals(100.0, Geo.distanceMeters(ORIGIN, NORTH_100M), 2.0)
    }

    @Test
    fun `bearing due north is zero degrees`() {
        assertEquals(0.0, Geo.bearingDegrees(ORIGIN, NORTH_100M), 0.5)
    }

    @Test
    fun `bearing due south is a hundred and eighty`() {
        assertEquals(180.0, Geo.bearingDegrees(NORTH_100M, ORIGIN), 0.5)
    }

    @Test
    fun `angles compare the short way round the compass`() {
        assertEquals(20.0, Geo.angleDifference(350.0, 10.0), 0.001)
        assertEquals(180.0, Geo.angleDifference(0.0, 180.0), 0.001)
        assertEquals(10.0, Geo.angleDifference(-5.0, 5.0), 0.001)
    }
}

class GpsParsingTest {

    @Test
    fun `a lat long pair is read, spaces and all`() {
        assertEquals(GeoPoint(55.75393, 37.620795), parseGps("55.75393, 37.620795"))
    }

    @Test
    fun `the server's ways of saying there are no coordinates all mean null`() {
        assertNull(parseGps(null))
        assertNull(parseGps(""))
        assertNull(parseGps("0"))
        assertNull(parseGps("0,0"))
    }

    @Test
    fun `nonsense is treated as absent rather than throwing at the gate`() {
        assertNull(parseGps("55.75393"))
        assertNull(parseGps("широта, долгота"))
        assertNull(parseGps("55.75393, 37.620795, 12"))
        assertNull(parseGps("95.0, 37.0"))
    }
}

class ProximityTest {

    /**
     * These cases are built around a 150 m zone. Stated outright rather than
     * leaning on the default, which is a choice the driver makes and may move.
     */
    private val radius = 150.0

    private fun inRange(doors: List<Door>, fix: Fix): List<NearbyDoor> =
        Proximity.inRange(doors, fix, radius)

    private val near = doorAt("near", NORTH_100M)
    private val far = doorAt("far", GeoPoint(ORIGIN.latitude + 0.05, ORIGIN.longitude))
    private val unlocated = doorAt("unlocated", null)

    @Test
    fun `only doors inside the radius count`() {
        val result = inRange(listOf(near, far, unlocated), Fix(ORIGIN))

        assertEquals(listOf("near"), result.map { it.door.id })
        assertEquals(100.0, result.single().distanceMeters, 2.0)
    }

    @Test
    fun `a door with no coordinates is never nearby`() {
        assertTrue(inRange(listOf(unlocated), Fix(ORIGIN)).isEmpty())
    }

    @Test
    fun `driving towards a door marks it as approached`() {
        val heading = Fix(ORIGIN, bearingDegrees = 0.0, speedMetersPerSecond = 10.0)

        assertTrue(inRange(listOf(near), heading).single().approaching)
    }

    @Test
    fun `driving away marks it as not approached, but keeps it listed`() {
        val leaving = Fix(ORIGIN, bearingDegrees = 180.0, speedMetersPerSecond = 10.0)
        val result = inRange(listOf(near), leaving)

        assertEquals(1, result.size)
        assertFalse(result.single().approaching)
    }

    /** A parked phone reports a heading that is mostly noise. */
    @Test
    fun `a heading is ignored while the car is barely moving`() {
        val parked = Fix(ORIGIN, bearingDegrees = 180.0, speedMetersPerSecond = 0.05)

        assertTrue(inRange(listOf(near), parked).single().approaching)
    }

    @Test
    fun `a heading with no speed reading is still trusted`() {
        val noSpeed = Fix(ORIGIN, bearingDegrees = 180.0, speedMetersPerSecond = null)

        assertFalse(inRange(listOf(near), noSpeed).single().approaching)
    }

    @Test
    fun `the speed floor is a boundary, not an approximation`() {
        fun approachingAtSpeed(speed: Double) =
            inRange(listOf(near), Fix(ORIGIN, 180.0, speed)).single().approaching

        // Driving away: at the floor the heading counts, below it it is noise.
        assertFalse(approachingAtSpeed(Fix.MIN_SPEED_FOR_BEARING))
        assertTrue(approachingAtSpeed(Fix.MIN_SPEED_FOR_BEARING - 0.01))
    }

    @Test
    fun `the approach tolerance is forty-five degrees either side`() {
        fun approachingAt(bearing: Double) =
            inRange(listOf(near), Fix(ORIGIN, bearing, 10.0)).single().approaching

        // The door is due north, so a heading within 45 degrees of north counts.
        assertTrue(approachingAt(44.0))
        assertFalse(approachingAt(46.0))
        assertTrue(approachingAt(316.0))
        assertFalse(approachingAt(314.0))
    }

    @Test
    fun `doors being driven towards sort above ones being left behind`() {
        val ahead = doorAt("ahead", GeoPoint(ORIGIN.latitude + 0.0011, ORIGIN.longitude))
        val behind = doorAt("behind", GeoPoint(ORIGIN.latitude - 0.0002, ORIGIN.longitude))
        val northbound = Fix(ORIGIN, bearingDegrees = 0.0, speedMetersPerSecond = 10.0)

        val result = inRange(listOf(behind, ahead), northbound)

        // "behind" is nearer, but the car is driving away from it.
        assertEquals(listOf("ahead", "behind"), result.map { it.door.id })
    }

    @Test
    fun `the default radius is fifty metres`() {
        // 100 m away: outside the default zone, inside a wider one.
        assertTrue(Proximity.inRange(listOf(near), Fix(ORIGIN)).isEmpty())
        assertEquals(1, Proximity.inRange(listOf(near), Fix(ORIGIN), 150.0).size)
    }

    @Test
    fun `the offered choices include the default`() {
        assertTrue(Proximity.DEFAULT_RADIUS_METERS in Proximity.RADIUS_CHOICES_METERS)
        assertEquals(
            Proximity.RADIUS_CHOICES_METERS.sorted(),
            Proximity.RADIUS_CHOICES_METERS,
        )
    }
}

class ProximityTrackerTest {

    private val door = doorAt("1", NORTH_100M)
    private val tracker = ProximityTracker()
    private val radius = 150.0

    /** Announcing is the caller's job, so the test does what the service does. */
    private fun arrive(doors: List<Door> = listOf(door), from: GeoPoint = ORIGIN) =
        tracker.update(doors, Fix(from), radius)

    @Test
    fun `arriving offers the door once, not on every fix`() {
        val first = arrive()
        tracker.markAnnounced(first.candidates.single().door)
        val second = arrive()

        assertEquals(listOf("1"), first.candidates.map { it.door.id })
        assertTrue(second.candidates.isEmpty())
        // Still in range, so the car screen keeps showing it.
        assertEquals(1, second.inRange.size)
    }

    /**
     * Two gates close together: telling the driver about the nearer one must not
     * silence the other, or they reach the second gate and nothing ever prompts.
     */
    @Test
    fun `a door that was never announced stays available to announce later`() {
        val other = doorAt("2", GeoPoint(ORIGIN.latitude + 0.0005, ORIGIN.longitude))
        val doors = listOf(door, other)

        val first = tracker.update(doors, Fix(ORIGIN), radius)
        tracker.markAnnounced(first.candidates.first().door)
        val second = tracker.update(doors, Fix(ORIGIN), radius)

        assertEquals(2, first.candidates.size)
        assertEquals(
            first.candidates.drop(1).map { it.door.id },
            second.candidates.map { it.door.id },
        )
    }

    /**
     * Leaving the radius is not enough — a stationary fix jitters across it. The
     * door only re-arms once the car is genuinely away.
     */
    @Test
    fun `stepping just outside the radius does not re-arm the announcement`() {
        tracker.markAnnounced(arrive().candidates.single().door)

        arrive(from = GeoPoint(ORIGIN.latitude - 0.0006, ORIGIN.longitude))

        assertTrue(arrive().candidates.isEmpty())
    }

    @Test
    fun `driving well clear re-arms it for the next trip`() {
        tracker.markAnnounced(arrive().candidates.single().door)

        arrive(from = GeoPoint(ORIGIN.latitude - 0.01, ORIGIN.longitude))

        assertEquals(listOf("1"), arrive().candidates.map { it.door.id })
    }

    /** A door dropped from the account must not keep its flag for ever. */
    @Test
    fun `a door that disappears from the list releases its flag`() {
        tracker.markAnnounced(arrive().candidates.single().door)

        arrive(doors = emptyList())

        assertEquals(listOf("1"), arrive().candidates.map { it.door.id })
    }

    /** The whole point of the setting: it must bite without restarting anything. */
    @Test
    fun `a radius change takes effect on the very next fix`() {
        val doors = listOf(door)

        assertTrue(tracker.update(doors, Fix(ORIGIN), 50.0).inRange.isEmpty())
        assertEquals(1, tracker.update(doors, Fix(ORIGIN), 150.0).inRange.size)
    }
}
