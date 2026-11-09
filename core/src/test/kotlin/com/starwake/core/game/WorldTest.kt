package com.starwake.core.game

import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.TestAsserts.assertSameRotation
import com.starwake.core.TestAsserts.assertUnit
import com.starwake.core.TestAsserts.assertVec
import com.starwake.core.content.Content
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.PI_F
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AsteroidFieldTest {
    private val seed = 1234L
    private val field = AsteroidField(seed, Content.MAX_ASTEROIDS, Content.ROCK_VARIANTS)

    @Test
    fun sameSeedGivesTheSameField() {
        val other = AsteroidField(seed, Content.MAX_ASTEROIDS, Content.ROCK_VARIANTS)
        assertEquals(field.total, other.total)
        assertContentEquals(field.px, other.px)
        assertContentEquals(field.py, other.py)
        assertContentEquals(field.pz, other.pz)
        assertContentEquals(field.radius, other.radius)
        assertContentEquals(field.instanceCount, other.instanceCount)
        for (v in 0 until field.variants) assertContentEquals(field.instanceData[v], other.instanceData[v])
        val different = AsteroidField(seed + 1, Content.MAX_ASTEROIDS, Content.ROCK_VARIANTS)
        assertTrue(!field.px.contentEquals(different.px))
    }

    @Test
    fun theWholeBudgetIsPlacedAndSplitAcrossVariants() {
        println("AsteroidField($seed): ${field.total} placed, per variant ${field.instanceCount.toList()}")
        assertEquals(Content.MAX_ASTEROIDS, field.total, "all asteroids should find a free spot")
        assertEquals(field.total, field.instanceCount.sum())
        for (c in field.instanceCount) assertTrue(c > field.total / field.variants / 2, "variants should be balanced")
    }

    @Test
    fun instanceDataMatchesTheCollisionArrays() {
        val next = IntArray(field.variants)
        for (i in 0 until field.total) {
            // instances are appended per variant in placement order
            var matched = -1
            for (v in 0 until field.variants) {
                val o = next[v] * 8
                val d = field.instanceData[v]
                if (next[v] < field.instanceCount[v] && d[o] == field.px[i] && d[o + 1] == field.py[i] &&
                    d[o + 2] == field.pz[i] && d[o + 3] == field.radius[i]
                ) {
                    matched = v; break
                }
            }
            assertTrue(matched >= 0, "asteroid $i not found in instanceData")
            val o = next[matched] * 8
            val d = field.instanceData[matched]
            assertUnit(Vec3(d[o + 4], d[o + 5], d[o + 6]), 1e-5f, "spin axis")
            assertTrue(d[o + 7] > 0f && d[o + 7] < 0.3f, "spin ${d[o + 7]}")
            next[matched]++
        }
        assertContentEquals(field.instanceCount, next)
    }

    @Test
    fun radiiAreInTheirDesignRanges() {
        for (i in 0 until field.total) {
            val r = field.radius[i]
            if (i < 14) assertTrue(r in 70f..150f, "landmark rock $i radius $r")
            else assertTrue(r in 3f..51f, "rock $i radius $r")
        }
    }

    @Test
    fun asteroidsDoNotOverlap() {
        var closest = Float.MAX_VALUE
        for (i in 0 until field.total) for (j in i + 1 until field.total) {
            val dx = field.px[i] - field.px[j]
            val dy = field.py[i] - field.py[j]
            val dz = field.pz[i] - field.pz[j]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            val gap = d / (field.radius[i] + field.radius[j])
            closest = minOf(closest, gap)
            assertTrue(gap >= 1.05f * (1f - 1e-5f), "asteroids $i and $j overlap: distance $d, radii ${field.radius[i]} + ${field.radius[j]}")
        }
        println("AsteroidField: closest pair at %.3f × the sum of radii".format(closest))
    }

    @Test
    fun spawnLaneIsClear() {
        for (i in 0 until field.total) {
            val x = field.px[i] + field.origin.x.toFloat()
            val y = field.py[i] + field.origin.y.toFloat()
            val z = field.pz[i] + field.origin.z.toFloat()
            val cz = z.coerceIn(-700f, 200f)
            val lane = sqrt(x * x + y * y + (z - cz) * (z - cz))
            assertTrue(lane >= field.radius[i] + 140f - 0.01f, "asteroid $i (r=${field.radius[i]}) is $lane m from the spawn lane")
            val sx = x - AsteroidField.SHOWCASE_X
            val sy = y - AsteroidField.SHOWCASE_Y
            val sz = z - AsteroidField.SHOWCASE_Z
            val showcase = sqrt(sx * sx + sy * sy + sz * sz)
            assertTrue(showcase >= field.radius[i] + 200f - 0.01f, "asteroid $i is $showcase m from the menu showcase")
        }
    }

    @Test
    fun aShipFlyingTheLaneHitsNothing() {
        val model = FlightModel(ShipSpec.STANDARD)
        val s = ShipState()
        s.pos.set(0.0, 0.0, 200.0)
        val c = ControlInput().also { it.throttle = 1f }
        while (s.pos.z > -700.0) {
            model.step(s, c, 1f / 60f)
            for (i in 0 until field.total) {
                val hit = model.collideSphere(
                    s, field.px[i] + field.origin.x, field.py[i] + field.origin.y, field.pz[i] + field.origin.z, field.radius[i].toDouble(),
                )
                assertEquals(0f, hit, "collided with asteroid $i at z=${s.pos.z}")
            }
        }
    }

    @Test
    fun qualityLevelsActivateIncreasingSubsets() {
        val counts = IntArray(3)
        val previous = BooleanArray(field.maxCount)
        for (q in 0..2) {
            field.setQuality(q)
            val perVariant = IntArray(field.variants)
            for (i in 0 until field.total) if (field.active[i]) counts[q]++
            // a higher quality never deactivates a rock that was active before
            if (q > 0) for (i in 0 until field.total) if (previous[i]) assertTrue(field.active[i], "rock $i dropped at quality $q")
            System.arraycopy(field.active, 0, previous, 0, field.maxCount)
            // collision set matches what is drawn: the first drawCount instances of each variant
            val next = IntArray(field.variants)
            for (i in 0 until field.total) {
                val v = (0 until field.variants).first { v ->
                    val o = next[v] * 8
                    next[v] < field.instanceCount[v] && field.instanceData[v][o] == field.px[i] &&
                        field.instanceData[v][o + 1] == field.py[i] && field.instanceData[v][o + 2] == field.pz[i]
                }
                assertEquals(next[v] < field.drawCount(v, q), field.active[i], "rock $i (variant $v #${next[v]}) at quality $q")
                if (field.active[i]) perVariant[v]++
                next[v]++
            }
            for (v in 0 until field.variants) assertEquals(field.drawCount(v, q), perVariant[v])
        }
        println("AsteroidField active per quality: ${counts.toList()}")
        assertTrue(counts[0] < counts[1] && counts[1] < counts[2], "active counts ${counts.toList()}")
        assertEquals(field.total, counts[2])
        assertTrue(counts[0] > 0)
    }

    @Test
    fun smallFieldsStillHoldTheInvariants() {
        val f = AsteroidField(7L, 40, 1)
        assertTrue(f.total in 1..40)
        assertEquals(f.total, f.instanceCount[0])
        f.setQuality(0)
        assertEquals(f.drawCount(0, 0), f.active.count { it })
    }
}

class ChaseCameraTest {
    private fun shipAt(q: Quat): ShipState = ShipState().also {
        it.pos.set(1000.0, -250.0, 5000.0)
        it.rot.set(q)
    }

    /** Camera position relative to the ship, in ship space. */
    private fun offsetInShipSpace(cam: ChaseCamera, ship: ShipState): Vec3 {
        val w = Vec3((cam.pos.x - ship.pos.x).toFloat(), (cam.pos.y - ship.pos.y).toFloat(), (cam.pos.z - ship.pos.z).toFloat())
        return ship.rot.rotateInverse(w, Vec3())
    }

    private val tilted = Quat().setAxisAngle(0.3f, 1f, -0.4f, 1.9f)

    @Test
    fun chaseModeSitsBehindAndAboveTheShip() {
        for (q in listOf(Quat(), tilted)) {
            val ship = shipAt(q)
            val cam = ChaseCamera()
            cam.snapTo(ship)
            assertSameRotation(ship.rot, cam.rot, 1e-6f)
            val o = offsetInShipSpace(cam, ship)
            assertTrue(o.z > 10f, "camera must be behind the ship (+Z in ship space): $o")
            assertTrue(o.y > 2f, "camera must be above the ship: $o")
            assertNear(0f, o.x, 1e-3f)
            assertVec(0f, 4.0f, 16.5f, o, 1e-3f)
        }
    }

    @Test
    fun theShipIsInViewSlightlyBelowCentre() {
        val ship = shipAt(tilted)
        val cam = ChaseCamera()
        cam.snapTo(ship)
        val view = cam.renderRotation(Quat())
        val toShip = Vec3((ship.pos.x - cam.pos.x).toFloat(), (ship.pos.y - cam.pos.y).toFloat(), (ship.pos.z - cam.pos.z).toFloat())
        val local = view.rotateInverse(toShip, Vec3()) // camera space, looking down -Z
        assertTrue(local.z < 0f, "ship must be in front of the camera: $local")
        val elevation = atan2(local.y, -local.z)
        assertTrue(elevation < 0f && elevation > -0.4f, "ship should sit a little below centre, elevation $elevation rad")
        assertNear(0f, local.x, 1e-3f)
    }

    @Test
    fun configureCirclesTheHullCentre() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.configure(Vec3(0f, -2f, 10f), Vec3(0f, 16f, 85f), -8f * DEG2RAD)
        cam.snapTo(ship)
        // the offset is from the centre, which is itself offset from the origin
        assertVec(0f, 14f, 95f, offsetInShipSpace(cam, ship), 1e-3f)
        assertNear(kotlin.math.sqrt(16f * 16f + 85f * 85f), cam.viewDistance, 1e-3f)
    }

    @Test
    fun speedPullsTheCameraBack() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        cam.update(ship, 1f / 60f, 1f, 220f)
        assertNear(16.5f + 3f, offsetInShipSpace(cam, ship).z, 1e-3f)
    }

    @Test
    fun rotationLagsThenCatchesUp() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        ship.rot.setAxisAngle(0f, 1f, 0f, -PI_F / 2f) // ship snaps 90° right
        cam.update(ship, 1f / 60f, 0f, 220f)
        val d = abs(cam.rot.dot(ship.rot))
        assertTrue(d < 0.999f && cam.rot.dot(Quat()) < 0.9999f, "camera should be part-way through the turn (|dot| = $d)")
        repeat(120) { cam.update(ship, 1f / 60f, 0f, 220f) }
        assertSameRotation(ship.rot, cam.rot, 1e-5f)
        assertVec(0f, 4.0f, 16.5f, offsetInShipSpace(cam, ship), 1e-2f)
    }

    @Test
    fun shakeDisplacesThenDecays() {
        val ship = shipAt(Quat())
        val cam = ChaseCamera()
        cam.snapTo(ship)
        cam.addShake(2f)
        var maxDev = 0f
        repeat(30) {
            cam.update(ship, 1f / 60f, 0f, 220f)
            val o = offsetInShipSpace(cam, ship)
            maxDev = maxOf(maxDev, abs(o.x), abs(o.y - 4.0f))
        }
        assertTrue(maxDev > 0.1f, "shake should move the camera, max deviation $maxDev")
        repeat(300) { cam.update(ship, 1f / 60f, 0f, 220f) }
        assertVec(0f, 4.0f, 16.5f, offsetInShipSpace(cam, ship), 1e-3f)
    }
}

class OrbitCameraTest {
    private fun checkLooksAtTarget(cam: OrbitCamera) {
        val target = DVec3(12.0, -3.0, 400.0)
        val pos = DVec3()
        val rot = Quat()
        cam.apply(target, pos, rot)
        val to = Vec3((target.x - pos.x).toFloat(), (target.y - pos.y).toFloat(), (target.z - pos.z).toFloat())
        assertNear(cam.distance, to.length(), 1e-3f, "orbit distance")
        val fwd = rot.rotate(0f, 0f, -1f, Vec3())
        assertVec(to.normalize(), fwd, 1e-4f, "camera must look at the target (yaw ${cam.yaw}, pitch ${cam.pitch})")
        assertTrue(rot.rotate(0f, 1f, 0f, Vec3()).y > 0f, "camera must stay upright")
        assertUnit(rot)
    }

    @Test
    fun orbitCameraLooksAtTheTarget() {
        val cam = OrbitCamera()
        checkLooksAtTarget(cam)
        for (yaw in listOf(-3f, -1f, 0f, 0.5f, 2.35f, 6f)) for (pitch in listOf(-0.9f, -0.3f, 0f, 0.12f, 0.9f)) {
            cam.yaw = yaw
            cam.pitch = pitch
            checkLooksAtTarget(cam)
        }
    }

    @Test
    fun dragRotatesAndClampsPitch() {
        val cam = OrbitCamera()
        val yaw = cam.yaw
        cam.drag(100f, 0f, 500f)
        assertNear(yaw - 0.2f, cam.yaw, 1e-6f)
        assertEquals(0f, cam.idle)
        cam.drag(0f, 10_000f, 500f)
        assertEquals(0.9f, cam.pitch)
        cam.drag(0f, -10_000f, 500f)
        assertEquals(-0.9f, cam.pitch)
        checkLooksAtTarget(cam)
    }

    @Test
    fun flingCoastsAndIdleAutoSpinResumes() {
        val cam = OrbitCamera()
        cam.drag(0f, 0f, 500f)
        cam.fling(500f, 0f, 500f) // 1 rad/s to the left
        val yaw0 = cam.yaw
        repeat(60) { cam.update(1f / 60f) }
        val coasted = yaw0 - cam.yaw
        assertTrue(coasted in 0.25f..0.4f, "fling should coast and decay, moved $coasted rad")
        // after a long idle the camera spins on its own and returns to the default pitch
        cam.pitch = 0.8f
        repeat(60 * 30) { cam.update(1f / 60f) }
        val before = cam.yaw
        repeat(60) { cam.update(1f / 60f) }
        assertNear(cam.autoSpin, cam.yaw - before, 1e-3f)
        assertNear(0.28f, cam.pitch, 0.05f)
    }
}
