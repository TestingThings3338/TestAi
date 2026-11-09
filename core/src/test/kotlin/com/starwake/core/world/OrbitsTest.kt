package com.starwake.core.world

import com.starwake.core.game.StarSystem
import com.starwake.core.math.DVec3
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `ShipOrbits` in the port (03-world.md 2.9, 9.4 OrbitsTest): positions, rest positions, deltas, double clock. */
class OrbitsTest {
    private fun len(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

    @Test
    fun positionOnOrbitAtQuarterTurns() {
        val c = DVec3(10.0, -20.0, 30.0)
        val out = DVec3()
        val r = 1000.0
        val p = 400.0
        // tilt 0: a circle in XZ, angle from +X towards +Z
        Orbits.position(r, p, 0.0, 0.0, 0.0, c, out)
        assertEquals(10.0 + r, out.x, 1e-9); assertEquals(-20.0, out.y, 1e-9); assertEquals(30.0, out.z, 1e-9)
        Orbits.position(r, p, 0.0, 0.0, p / 4, c, out)
        assertEquals(10.0, out.x, 1e-9); assertEquals(30.0 + r, out.z, 1e-9)
        Orbits.position(r, p, 0.0, 0.0, p / 2, c, out)
        assertEquals(10.0 - r, out.x, 1e-9); assertEquals(30.0, out.z, 1e-9)
        // tilt 1.5°: y = -z·sin t, z = z·cos t, the radius unchanged
        val t = Math.toRadians(1.5)
        Orbits.position(r, p, 0.0, 1.5, p / 4, c, out)
        assertEquals(-20.0 - r * sin(t), out.y, 1e-9)
        assertEquals(30.0 + r * cos(t), out.z, 1e-9)
        assertEquals(r, len(out.x - c.x, out.y - c.y, out.z - c.z), 1e-9)
        // the phase shifts the start: phase 0.25 at clock 0 = phase 0 at a quarter period
        Orbits.position(r, p, 0.25, 0.0, 0.0, c, out)
        assertEquals(30.0 + r, out.z, 1e-9)
    }

    @Test
    fun theAngleIsComputedInDoubleAtLargeClocks() {
        // Phobos at 10 years of play: (phase + (clock mod period)/period) against a BigDecimal reference
        val period = 20_563.0
        val phase = 0.123456789
        val clock = 3.0e8 + 0.375
        val mc = MathContext(40)
        val ref = BigDecimal(phase).add(BigDecimal(clock).divide(BigDecimal(period), mc), mc)
        val refTurns = ref.subtract(BigDecimal(ref.toBigInteger()), mc).toDouble()
        assertEquals(refTurns, Orbits.turns(phase, period, clock), 1e-12)
        // the position follows: at 10.8 Mm (Phobos at S = 3000), 1e-12 turns is 0.07 mm
        val o = DVec3()
        Orbits.position(3600.0 * WorldScale.S, period, phase, 0.0, clock, DVec3(), o)
        val a = refTurns * 2.0 * PI
        assertEquals(cos(a) * 3600.0 * WorldScale.S, o.x, 1e-3)
        assertEquals(sin(a) * 3600.0 * WorldScale.S, o.z, 1e-3)
        // a float clock would be quantised to 32 s up there: the double clock resolves microseconds
        assertTrue(Math.ulp(clock) < 1e-7)
    }

    @Test
    fun restPositionsIgnoreTheTiltAndMoonsHangFromTheirPlanet() {
        val sys = WorldBuilder.sol()
        for (b in sys.bodies) {
            if (b.isStar) {
                assertEquals(0.0, len(b.restPos.x, b.restPos.y, b.restPos.z))
                continue
            }
            assertEquals(0.0, b.restPos.y, "${b.name}: rest positions lie in the plane (no tilt)")
            val p = sys.bodies[b.parent].restPos
            val a = b.orbitPhase * 2.0 * PI
            assertEquals(p.x + cos(a) * b.orbitRadius, b.restPos.x, 1e-3, b.name)
            assertEquals(p.z + sin(a) * b.orbitRadius, b.restPos.z, 1e-3, b.name)
            // at clock 0 a body sits at its orbit's start, tilted: same x, the radius kept
            val dx = b.pos.x - sys.bodies[b.parent].pos.x
            val dy = b.pos.y - sys.bodies[b.parent].pos.y
            val dz = b.pos.z - sys.bodies[b.parent].pos.z
            assertEquals(b.orbitRadius, len(dx, dy, dz), b.orbitRadius * 1e-12, "${b.name} on its circle")
        }
    }

    @Test
    fun moonsAreCarriedByTheirPlanetAndDeltasSumToTheDisplacement() {
        val sys = WorldBuilder.sol()
        val luna = sys.bodies[sys.bodyIndex("Luna")]
        val earth = sys.bodies[sys.bodyIndex("Earth")]
        val start = DVec3().set(luna.pos)
        val sum = DVec3()
        repeat(600) {
            sys.advance(1.0 / 60.0)
            sum.x += luna.delta.x; sum.y += luna.delta.y; sum.z += luna.delta.z
            // the moon stays on its circle around the moving planet
            val d = len(luna.pos.x - earth.pos.x, luna.pos.y - earth.pos.y, luna.pos.z - earth.pos.z)
            assertEquals(luna.orbitRadius, d, 1e-3)
        }
        assertEquals(10.0, sys.clock, 1e-9)
        assertEquals(luna.pos.x - start.x, sum.x, 1e-3)
        assertEquals(luna.pos.y - start.y, sum.y, 1e-3)
        assertEquals(luna.pos.z - start.z, sum.z, 1e-3)
        // Earth moves 11.16 km/s, Luna adds its own 4.8 km/s around Earth
        val e = sys.bodies[sys.bodyIndex("Earth")]
        val v = len(e.delta.x, e.delta.y, e.delta.z) * 60.0
        assertEquals(11_160.0, v, 1.0)
        // stations move with their host and never turn
        val st = sys.stations[0]
        val rot = com.starwake.core.math.Quat().set(st.rot)
        assertEquals(e.pos.x + st.offset.x, st.pos.x, 1e-6)
        assertEquals(e.pos.z + st.offset.z, st.pos.z, 1e-6)
        assertEquals(1f, kotlin.math.abs(rot.dot(st.rot)), 1e-6f)
    }

    @Test
    fun snapToSetsTheClockWithoutACarry() {
        val sys = WorldBuilder.sol()
        sys.advance(1.0)
        sys.snapTo(86_400.0)
        assertEquals(86_400.0, sys.clock)
        for (b in sys.bodies) assertEquals(0.0, len(b.delta.x, b.delta.y, b.delta.z), b.name)
        // a day later Phobos has gone round 4.2 times (period 0.24 days)
        val phobos = sys.bodies[sys.bodyIndex("Phobos")]
        val turns = Orbits.turns(phobos.orbitPhase, phobos.orbitPeriod, 86_400.0)
        assertEquals((phobos.orbitPhase + 86_400.0 / phobos.orbitPeriod) % 1.0, turns, 1e-9)
    }

    @Test
    fun aStationHangsFromItsHostsRestPosition() {
        // 9.4: Meridian with the star at the origin and Earth at (828,660, 0, 0): base (-1, 0, 0) →
        // Ry(90°) gives (0, 0, 1) → offset = (0, sin 24°, cos 24°)·6600 = (0, 2684.5, 6029.4) studs
        val meridian = StationDirectory.SOL[0]
        val o = StationDirectory.offset(meridian, 828_660.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, DVec3())
        assertEquals(0.0, o.x, 1e-6)
        assertEquals(2684.5, o.y, 0.05)
        assertEquals(6029.4, o.z, 0.05)
        // scaled once by S
        val os = StationDirectory.offset(meridian, 828_660.0, 0.0, 0.0, 0.0, 0.0, 0.0, WorldScale.S, DVec3())
        assertEquals(6029.4 * WorldScale.S, os.z, 0.05 * WorldScale.S)
        // a host on top of the star: base -Z
        val c = StationDirectory.offset(meridian, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, DVec3())
        // Ry(90°)·(0, 0, -1) = (-1, 0, 0)
        assertEquals(-6600.0 * cos(Math.toRadians(24.0)), c.x, 1e-6)
        // the mouth (-Z) faces the host
        val rot = StationDirectory.rotation(o.x, o.y, o.z, com.starwake.core.math.Quat())
        val mouth = rot.rotate(0f, 0f, -1f, com.starwake.core.math.Vec3())
        val l = len(o.x, o.y, o.z)
        assertEquals(1.0, -(mouth.x * o.x + mouth.y * o.y + mouth.z * o.z) / l, 1e-6)
        // and the built system uses the rest positions: every station is where the rule puts it
        val sys = WorldBuilder.sol()
        for (st in sys.stations) {
            val h = sys.bodies[st.parent].restPos
            val def = StationDirectory.SOL.first { it.id == st.id }
            val exp = StationDirectory.offset(def, h.x, h.y, h.z, 0.0, 0.0, 0.0, WorldScale.S, DVec3())
            assertEquals(exp.x, st.offset.x, 1e-6, st.name)
            assertEquals(exp.y, st.offset.y, 1e-6, st.name)
            assertEquals(exp.z, st.offset.z, 1e-6, st.name)
        }
    }

    @Test
    fun angularSizesAreRobloxs() {
        // B.2: uniform scaling keeps every sky angle; computed from the built system at S = 3000
        val sys = WorldBuilder.sol()
        fun deg(x: Double) = Math.toDegrees(x)
        fun body(n: String) = sys.bodies[sys.bodyIndex(n)]
        val earth = body("Earth")
        val luna = body("Luna")
        // Luna from Earth's surface: 0.53°
        assertEquals(0.53, deg(2.0 * kotlin.math.asin(luna.radius / (luna.orbitRadius - earth.radius))), 0.005)
        // the Sun from Earth: 0.66°
        assertEquals(0.66, deg(2.0 * kotlin.math.asin(sys.bodies[sys.star].radius / earth.orbitRadius)), 0.005)
        // hosts from their stations: 37.5° / 19.6° / 28.4° / 55.5° / 24.2° / 21.2°
        val expect = doubleArrayOf(37.5, 19.6, 28.4, 55.5, 24.2, 21.2)
        for ((k, st) in sys.stations.withIndex()) {
            val h = sys.bodies[st.parent]
            val d = len(st.pos.x - h.pos.x, st.pos.y - h.pos.y, st.pos.z - h.pos.z)
            assertEquals(expect[k], deg(2.0 * kotlin.math.asin(h.radius / d)), 0.05, st.name)
        }
        // Earth from MERIDIAN: 37.5° wide
        val m = sys.stations[0]
        assertEquals(37.47, deg(2.0 * kotlin.math.asin(earth.radius / 19_800_000.0)), 0.01)
        assertEquals(19_800_000.0, len(m.offset.x, m.offset.y, m.offset.z), 1e-3)
    }
}
