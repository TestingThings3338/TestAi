package com.starwake.core.world

import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `ShipRingDebris` (03-world.md 2.12 and 9.4) in Saturn's real rings at S = 3000. */
class RingDebrisTest {
    private val sol = WorldBuilder.sol()
    private val saturn = sol.bodies[sol.bodyIndex("Saturn")]
    private val dt = 1.0 / 60.0

    // Saturn's ring frame as ShipRingDebris builds it: x = right, y = the normal, z = right x normal
    private val n = saturn.ringNormal
    private val right: Vec3
    private val zAxis: Vec3

    init {
        val hx = if (abs(n.x) < 0.9f) 1f else 0f
        val hz = 1f - hx
        val d = hx * n.x + hz * n.z
        right = Vec3(hx - n.x * d, -n.y * d, hz - n.z * d).normalize()
        zAxis = Vec3(right.y * n.z - right.z * n.y, right.z * n.x - right.x * n.z, right.x * n.y - right.y * n.x)
    }

    /** f = radius / outer radius along the frame's x axis, [h] metres above the plane. */
    private fun ringPoint(f: Double, h: Double, z: Double = 0.0): DVec3 {
        val r = f * saturn.ringOuter
        return DVec3(
            saturn.pos.x + right.x * r + n.x * h + zAxis.x * z,
            saturn.pos.y + right.y * r + n.y * h + zAxis.y * z,
            saturn.pos.z + right.z * r + n.z * h + zAxis.z * z,
        )
    }

    /** A velocity of [v] m/s along the ring frame's z axis (ring coordinates: the rocks move by −v·dt). */
    private fun alongZ(v: Float) = Vec3(zAxis.x * v, zAxis.y * v, zAxis.z * v)

    /** Gets the field going at [p] and parks every rock but [keep] far off the ship's path (x = ±600). */
    private fun isolated(d: RingDebris, p: DVec3, keep: Int) {
        d.update(dt, sol, p, Vec3())
        for (i in 0 until d.count) if (i != keep) d.placeRock(i, if (i % 2 == 0) 600.0 else -600.0, (i % 7) * 90.0 - 300.0)
    }

    /** The first rock that is fully shown at the B ring and sits close to the plane (|height| < 10 m). */
    private fun pickRock(d: RingDebris, p: DVec3): Int {
        d.update(dt, sol, p, Vec3())
        for (i in 0 until d.count) if (d.shown(i) >= 0.99f && abs(d.rockHeight(i)) < 10.0 && d.rockRadius(i) < 6.0) return i
        error("no rock close to the plane")
    }

    @Test
    fun wrapKeepsTheFieldAroundTheShip() {
        val s = RingDebris.FIELD_SIZE
        assertEquals(0.0, RingDebris.wrap(0.0, s), 1e-9)
        assertEquals(-700.0, RingDebris.wrap(700.0, s), 1e-9)
        assertEquals(-700.0, RingDebris.wrap(-700.0, s), 1e-9)
        assertEquals(-699.0, RingDebris.wrap(701.0, s), 1e-9)
        assertEquals(699.0, RingDebris.wrap(-701.0, s), 1e-9)
        assertEquals(5.0, RingDebris.wrap(2805.0, s), 1e-9)
        assertEquals(-5.0, RingDebris.wrap(-2805.0, s), 1e-9)
    }

    @Test
    fun sweptDistanceIsTheSegmentsClosestApproach() {
        // a fast pass from 100 m ahead to 100 m behind, 10 m to the side
        assertEquals(10.0, RingDebris.sweptDistance(-100.0, 0.0, 10.0, 100.0, 0.0, 10.0), 1e-9)
        // a segment that stays ahead: its near end
        assertEquals(50.0, RingDebris.sweptDistance(50.0, 0.0, 0.0, 100.0, 0.0, 0.0), 1e-9)
        // no motion: the distance itself
        assertEquals(5.0, RingDebris.sweptDistance(3.0, 4.0, 0.0, 3.0, 4.0, 0.0), 1e-9)
    }

    @Test
    fun theLayoutIsFixedAndShaped() {
        val a = RingDebris()
        val b = RingDebris()
        var small = 0
        for (i in 0 until RingDebris.COUNT) {
            assertEquals(a.rockRadius(i), b.rockRadius(i))
            assertEquals(a.rockHeight(i), b.rockHeight(i))
            assertEquals(a.rockX(i), b.rockX(i))
            // diameters 3..26 m, many small: u^2.2
            assertTrue(a.rockRadius(i) in 1.5..13.0, "radius ${a.rockRadius(i)}")
            if (a.rockRadius(i) < 4.0) small++
            // within the slab (thickness 168 m) and the wrapped square (1400 m)
            assertTrue(abs(a.rockHeight(i)) <= RingDebris.THICKNESS * 0.5)
            assertTrue(abs(a.rockX(i)) <= 700.0 && abs(a.rockZ(i)) <= 700.0)
            assertTrue(a.rockThreshold(i) in 0.0..1.0)
        }
        assertTrue(small > RingDebris.COUNT / 3, "most rocks are small: $small")
    }

    @Test
    fun rockDensityFollowsTheRingProfile() {
        val p = RingProfiles.values[RingProfiles.SATURN]
        // the emptiest step of the Cassini division (0-based steps 108..116) and the dense B ring
        val gapStep = (108..116).minBy { p[it] }
        val gapF = (gapStep + 0.5) / 128.0
        fun shownAt(f: Double, h: Double = 0.0): Int {
            val d = RingDebris()
            d.update(dt, sol, ringPoint(f, h), Vec3())
            return d.visible
        }
        val bRing = shownAt(0.78)
        val gap = shownAt(gapF)
        val aRing = shownAt(0.93)
        println("rocks shown: B ring (f 0.78) $bRing, Cassini division (f %.3f) $gap, A ring (f 0.93) $aRing".format(gapF))
        assertTrue(bRing > 150, "the B ring is full of rocks: $bRing")
        assertTrue(gap < bRing / 4, "the Cassini division is almost empty: $gap vs $bRing")
        assertTrue(aRing > gap, "the A ring has more rocks than the gap: $aRing")
        // inside the inner edge (f 0.5, 2.6 km spawn range included) and far outside: nothing
        assertEquals(0, shownAt(0.45))
        assertEquals(0, shownAt(1.2))
        // above the slab: inactive beyond 784 m, thinner but present inside it
        assertEquals(0, shownAt(0.78, 800.0))
        assertTrue(shownAt(0.78, 300.0) < bRing, "300 m above the plane fewer rocks are near")
        // the low-quality count shows a subset
        val low = RingDebris().also { it.count = RingDebris.COUNT_LOW }
        low.update(dt, sol, ringPoint(0.78, 0.0), Vec3())
        assertTrue(low.visible in 1..RingDebris.COUNT_LOW)
    }

    @Test
    fun aFastPassThroughARockCountsOnce() {
        val d = RingDebris()
        val base = ringPoint(0.78, 0.0)
        val i = pickRock(d, base)
        // the ship flies along z at the rock's height: the rock 300 m ahead, then 30 km/s
        val p = ringPoint(0.78, d.rockHeight(i))
        isolated(d, p, i)
        d.placeRock(i, 0.0, 300.0)
        val v = alongZ(30_000f) // 500 m per frame: the rock is ahead before and behind after
        var total = 0
        for (k in 0 until 3) {
            total += d.update(dt, sol, p, v)
            p.x += v.x * dt
            p.y += v.y * dt
            p.z += v.z * dt
        }
        assertEquals(1, total, "one pass, one hit")
        assertTrue(d.heat > 0.0 || total == 1)
    }

    @Test
    fun aContactIsReleasedAtOneAndAQuarterHitRadii() {
        val d = RingDebris()
        val i = pickRock(d, ringPoint(0.78, 0.0))
        val p = ringPoint(0.78, d.rockHeight(i))
        isolated(d, p, i)
        d.placeRock(i, 0.0, 20.0)
        assertEquals(1, d.update(dt, sol, p, Vec3()), "a rock 20 m away is a contact")
        assertTrue(d.inContact(i))
        // drift away at 1 m per frame: still the same contact up to 32.5 m, released beyond it
        val v = alongZ(-60f)
        var hits = 0
        var releasedAt = -1.0
        for (k in 0 until 20) {
            hits += d.update(dt, sol, p, v)
            if (releasedAt < 0 && !d.inContact(i)) releasedAt = d.rockZ(i)
        }
        assertEquals(0, hits, "drifting off is no new hit")
        assertTrue(releasedAt > 32.0 && releasedAt < 34.0, "released at %.1f m (1.25 x 26 = 32.5)".format(releasedAt))
        // back in: a new contact
        d.placeRock(i, 0.0, 10.0)
        assertEquals(1, d.update(dt, sol, p, Vec3()))
    }

    @Test
    fun heatGrowsWithTheSquareOfTheSpeed() {
        fun heatAt(speed: Float): Double {
            val d = RingDebris()
            val i = pickRock(d, ringPoint(0.78, 0.0))
            val p = ringPoint(0.78, d.rockHeight(i))
            isolated(d, p, i)
            // half a frame's travel ahead (or 5 m when at rest): the pass goes right through it
            d.placeRock(i, 0.0, maxOf(5.0, speed * dt * 0.5))
            val hits = d.update(dt, sol, p, alongZ(speed))
            assertEquals(1, hits, "one hit at $speed m/s")
            return d.heat
        }
        assertEquals(0.012, heatAt(0f), 1e-9)
        assertEquals(0.012 + (0.25 - 0.012) * 0.25, heatAt(500f), 1e-6)
        assertEquals(0.25, heatAt(1000f), 1e-9)
        assertEquals(0.25, heatAt(5000f), 1e-9)
    }

    @Test
    fun noRingsNoField() {
        val d = RingDebris()
        val earth = sol.bodies[sol.bodyIndex("Earth")]
        assertEquals(0, d.update(dt, sol, DVec3(earth.pos.x, earth.pos.y + earth.radius + 1000.0, earth.pos.z), Vec3()))
        assertEquals(0, d.visible)
        assertEquals(-1, d.body)
        // entering Saturn's ring activates it; leaving hides everything and forgets the contacts
        d.update(dt, sol, ringPoint(0.78, 0.0), Vec3())
        assertEquals(sol.bodyIndex("Saturn"), d.body)
        assertTrue(d.visible > 0)
        d.update(dt, sol, ringPoint(0.78, 5000.0), Vec3())
        assertEquals(-1, d.body)
        assertEquals(0, d.visible)
        for (i in 0 until d.count) assertFalse(d.inContact(i))
    }

    @Test
    fun theFrameTurnsRingCoordinatesIntoTheWorld() {
        // Uranus: the ring stands at 82 degrees; ring y must be its normal, x and z in its plane
        val uranus = sol.bodies[sol.bodyIndex("Uranus")]
        val d = RingDebris()
        val un = uranus.ringNormal
        val hx = if (abs(un.x) < 0.9f) 1f else 0f
        val hz = 1f - hx
        val dd = hx * un.x + hz * un.z
        val r = Vec3(hx - un.x * dd, -un.y * dd, hz - un.z * dd).normalize()
        val at = (uranus.ringInner + uranus.ringOuter) * 0.5
        d.update(dt, sol, DVec3(uranus.pos.x + r.x * at, uranus.pos.y + r.y * at, uranus.pos.z + r.z * at), Vec3())
        assertEquals(sol.bodyIndex("Uranus"), d.body)
        val y = d.frame.rotate(0f, 1f, 0f, Vec3())
        assertEquals(1f, y.dot(un), 1e-5f)
        val x = d.frame.rotate(1f, 0f, 0f, Vec3())
        assertEquals(0f, x.dot(un), 1e-5f)
        assertEquals(1f, x.dot(r), 1e-5f)
        // the instances' quaternions are unit
        for (k in 0 until d.visible) {
            val o = k * RingDebris.FLOATS
            val qx = d.instances[o + 4]
            val qy = d.instances[o + 5]
            val qz = d.instances[o + 6]
            val qw = d.instances[o + 7]
            assertEquals(1f, sqrt(qx * qx + qy * qy + qz * qz + qw * qw), 1e-4f)
        }
    }

    @Test
    fun anUpdateDoesNotAllocate() {
        val mx = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        val d = RingDebris()
        val p = ringPoint(0.78, 0.0)
        val v = alongZ(300f)
        for (i in 0 until 2000) d.update(dt, sol, p, v)
        val a = mx.currentThreadAllocatedBytes
        for (i in 0 until 2000) d.update(dt, sol, p, v)
        val b = mx.currentThreadAllocatedBytes
        val per = (b - a) / 2000.0
        assertTrue(per < 1.0, "RingDebris.update allocates %.1f bytes per frame".format(per))
    }
}
