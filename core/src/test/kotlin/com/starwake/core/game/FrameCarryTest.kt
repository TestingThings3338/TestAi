package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.input.InputFrame
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.world.WorldBuilder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Body and station reference frames (03-world.md 2.9.4, DECISIONS 6): which frame holds a ship, the
 * nesting of moons in planets, station priority, hysteresis, and the carry: translation only, the
 * velocity relative, nothing jumps.
 */
class FrameCarryTest {
    private val system = WorldBuilder.sol()
    private fun idx(n: String) = system.bodyIndex(n)

    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

    /** A point [alt] m above body [i]'s surface, straight up (+Y). */
    private fun above(i: Int, alt: Double): DVec3 {
        val b = system.bodies[i]
        return DVec3(b.pos.x, b.pos.y + b.radius + alt, b.pos.z)
    }

    @Test
    fun stationsComeFirstThenTheInnermostBody() {
        val meridian = system.stations[0]
        val near = DVec3(meridian.pos.x + 1000.0, meridian.pos.y, meridian.pos.z)
        assertEquals(StarSystem.stationFrame(0), system.frameFor(near, StarSystem.FRAME_NONE))
        // 10 km off the station: Earth's frame
        val off = DVec3(meridian.pos.x + 10_000.0, meridian.pos.y, meridian.pos.z)
        assertEquals(StarSystem.bodyFrame(idx("Earth")), system.frameFor(off, StarSystem.FRAME_NONE))
        // Phobos (3.2 Mars radii out) nests inside Mars's frame
        val phobos = idx("Phobos")
        assertEquals(StarSystem.bodyFrame(phobos), system.frameFor(above(phobos, 50_000.0), StarSystem.FRAME_NONE))
        assertEquals(StarSystem.bodyFrame(idx("Mars")), system.frameFor(above(idx("Mars"), 50_000.0), StarSystem.FRAME_NONE))
        // between the planets: the star's frame (no carry)
        val e = system.bodies[idx("Earth")]
        val deep = DVec3(e.pos.x * 1.3, e.pos.y + 3e8, e.pos.z * 1.3)
        assertEquals(StarSystem.FRAME_NONE, system.frameFor(deep, StarSystem.FRAME_NONE))
        // the frame radius: Rc·(1 + factor), or more to hold the body's stations
        assertEquals(e.collisionRadius * 6.0, e.frameRadius, 1e-6)
        val luna = system.bodies[idx("Luna")]
        assertTrue(luna.frameRadius >= 3400.0 * 3000.0 * StarSystem.FRAME_HYSTERESIS, "TRANQUILITY hangs inside Luna's frame")
    }

    @Test
    fun hysteresisKeepsTheCurrentFrameALittleLonger() {
        val earth = idx("Earth")
        val e = system.bodies[earth]
        // just outside the frame radius: entered only from inside, kept up to x1.25
        val r = e.frameRadius * 1.1
        val p = DVec3(e.pos.x, e.pos.y + r, e.pos.z)
        assertEquals(StarSystem.FRAME_NONE, system.frameFor(p, StarSystem.FRAME_NONE))
        assertEquals(StarSystem.bodyFrame(earth), system.frameFor(p, StarSystem.bodyFrame(earth)))
        val q = DVec3(e.pos.x, e.pos.y + e.frameRadius * 1.3, e.pos.z)
        assertEquals(StarSystem.FRAME_NONE, system.frameFor(q, StarSystem.bodyFrame(earth)))
        // a station frame holds 2200 m, 2750 m once in it
        val st = system.stations[0]
        val s = DVec3(st.pos.x + 2500.0, st.pos.y, st.pos.z)
        assertEquals(StarSystem.bodyFrame(earth), system.frameFor(s, StarSystem.FRAME_NONE))
        assertEquals(StarSystem.stationFrame(0), system.frameFor(s, StarSystem.stationFrame(0)))
        // leaving TRANQUILITY's zone hands over to Luna, not to the star
        val tq = system.stations[1]
        val out = DVec3(tq.pos.x + 3000.0, tq.pos.y, tq.pos.z)
        assertEquals(StarSystem.bodyFrame(idx("Luna")), system.frameFor(out, StarSystem.stationFrame(1)))
    }

    @Test
    fun aMoonsDeltaIncludesItsPlanetsMotion() {
        val mars = system.bodies[idx("Mars")]
        val phobos = system.bodies[idx("Phobos")]
        system.advance(0.5)
        // Phobos moves with Mars (9.8 km/s) plus its own 3.3 km/s around it
        val rel = len(phobos.delta.x - mars.delta.x, phobos.delta.y - mars.delta.y, phobos.delta.z - mars.delta.z)
        assertEquals(1.10 * 3000.0 * 0.5, rel, 1.0)
        assertTrue(len(phobos.delta.x, phobos.delta.y, phobos.delta.z) > 4000.0)
    }

    private val input = InputFrame()

    private fun session(): Session = Session(system, Settings(), RecordingHost(), GearRig())

    private fun fly(s: Session, frames: Int) {
        repeat(frames) {
            input.begin()
            s.frame(1f / 60f, input)
        }
    }

    @Test
    fun aShipAtRestStaysPutInItsFrameWhileThePlanetMoves() {
        val s = session()
        s.startInSpace()
        s.setThrottle(0f)
        s.ship.vel.zero()
        fly(s, 2)
        assertEquals(StarSystem.bodyFrame(idx("Earth")), s.refFrame)
        val p0 = s.framePosition(DVec3())
        val abs0 = DVec3().set(s.ship.pos)
        fly(s, 600) // 10 s: Earth moves 112 km
        val p1 = s.framePosition(DVec3())
        val drift = len(p1.x - p0.x, p1.y - p0.y, p1.z - p0.z)
        val moved = len(s.ship.pos.x - abs0.x, s.ship.pos.y - abs0.y, s.ship.pos.z - abs0.z)
        assertTrue(moved > 100_000.0, "carried along with Earth: moved $moved m")
        assertTrue(drift < 1.0, "the ship keeps its place in Earth's frame: drifted $drift m")
    }

    @Test
    fun dockedAtMeridianTheShipRidesInTheSlot() {
        val s = session()
        s.startDocked()
        val st = system.stations[s.dockStation]
        val rel0 = DVec3(s.ship.pos.x - st.pos.x, s.ship.pos.y - st.pos.y, s.ship.pos.z - st.pos.z)
        val q0 = Quat().set(s.ship.rot)
        fly(s, 300)
        assertTrue(s.mode.docked)
        assertEquals(StarSystem.stationFrame(s.dockStation), s.refFrame)
        assertEquals(rel0.x, s.ship.pos.x - st.pos.x, 1e-6)
        assertEquals(rel0.y, s.ship.pos.y - st.pos.y, 1e-6)
        assertEquals(rel0.z, s.ship.pos.z - st.pos.z, 1e-6)
        assertEquals(1f, kotlin.math.abs(q0.dot(s.ship.rot)), 1e-6f)
        // the clean hull gap to the slot walls stays as parked (StationCollision works on the moving station)
        assertTrue(s.stationClearance() > 0f, "parked clear of the walls: ${s.stationClearance()}")
    }

    @Test
    fun theVelocityIsRelativeToTheFrameAndNeverJumps() {
        val s = session()
        s.startInSpace()
        s.setThrottle(0f)
        s.toggleFlightAssist() // Newtonian: no thrust, the velocity stays as set
        s.placePhysics = false // (without Earth's pull, which would bend a coasting path: this is about the carry)
        // coasting at 100 m/s in Earth's frame: the frame-relative position moves v·t
        s.ship.vel.set(0f, 100f, 0f)
        fly(s, 2)
        val p0 = s.framePosition(DVec3())
        fly(s, 600)
        val p1 = s.framePosition(DVec3())
        assertEquals(StarSystem.bodyFrame(idx("Earth")), s.refFrame)
        assertEquals(1000.0, len(p1.x - p0.x, p1.y - p0.y, p1.z - p0.z), 1.0)
        assertEquals(100f, s.ship.vel.y, 1e-3f)
        // out across the frame's edge at 2 Mm/s straight up: past the radius the frame holds on
        // (×1.25 hysteresis), then drops; the velocity never changes (translation-only carry)
        val earth = idx("Earth")
        val e = system.bodies[earth]
        s.teleport(e.pos.x, e.pos.y + e.frameRadius * 0.95, e.pos.z, 0f, 1f, 0f, true)
        assertEquals(StarSystem.bodyFrame(earth), s.refFrame)
        s.ship.vel.set(0f, 2.0e6f, 0f)
        var heldBeyond = false
        var leftAt = -1.0
        repeat(60 * 10) {
            val v0 = s.ship.vel.y
            val f0 = s.refFrame
            input.begin()
            s.frame(1f / 60f, input)
            val h = (s.ship.pos.y - e.pos.y) / e.frameRadius
            if (h > 1.1 && h < 1.2 && s.refFrame == StarSystem.bodyFrame(earth)) heldBeyond = true
            if (leftAt < 0.0 && s.refFrame == StarSystem.FRAME_NONE) leftAt = h
            // no step at the switch (the pre-port flight model's float round trips through the
            // hull frame drift a coasting speed by ~1e-4 over the run, never by the planet's 11 km/s)
            if (s.refFrame != f0) assertEquals(v0, s.ship.vel.y, 5f)
            assertEquals(2.0e6f, s.ship.vel.y, 2000f)
        }
        assertTrue(heldBeyond, "the frame holds beyond its radius (hysteresis)")
        assertEquals(StarSystem.FRAME_HYSTERESIS, leftAt, 0.02, "left Earth's frame at x1.25 its radius")
    }
}
