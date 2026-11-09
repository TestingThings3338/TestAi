package com.starwake.core.station

import com.starwake.core.content.StationModel
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runtime state of a station (ShipStation.place / updateLights) and its placement (ShipStations.offset). */
class StationInstanceTest {
    private val assets: File = listOf(File("../app/src/main/assets"), File("app/src/main/assets")).first { File(it, "stations").isDirectory }
    private fun model(t: String) = StationModel.load(t) { File(assets, it).readBytes() }

    private fun near(e: Float, a: Float, tol: Float, what: String) = assertTrue(abs(e - a) <= tol, "$what: expected $e, got $a")

    @Test
    fun blinkersFollowPeriodPhaseAndDuty() {
        val m = model("SpireStation")
        val st = StationInstance(m, seed = 3)
        // a RabbitStrobe class: period 0.5 s, duty 0.15
        val s = m.lamps.indexOfFirst { it.kind == StationModel.LAMP_BLINK && abs(it.period - 0.5f) < 1e-4f && abs(it.duty - 0.15f) < 1e-4f } + 1
        assertTrue(s > 0)
        val l = m.lamps[s - 1]
        val dt = 1f / 240f
        var on = 0
        val n = 2400 // 10 s
        for (i in 0 until n) {
            st.update(dt, Float.NaN, 0f, 0f)
            val t = st.clock
            var u = (t - l.phase) % l.period
            if (u < 0) u += l.period
            assertEquals(u < l.period * l.duty, st.isLit(s), "t = $t")
            if (st.isLit(s)) on++
            // off: Transparency max(base, 0.85) -> opacity 0.15; on: as built
            near(if (st.isLit(s)) -1f else 0.15f, st.lamps[s * 4 + 3], 1e-5f, "opacity")
        }
        near(0.15f, on.toFloat() / n, 0.01f, "duty")
    }

    @Test
    fun lampTableChangesOnlyWhenALampSwitches() {
        val m = model("RingStation")
        val st = StationInstance(m, seed = 1)
        st.update(0.001f, Float.NaN, 0f, 0f)
        val v0 = st.lampVersion
        // a tiny step changes nothing (no blinker is within 0.1 ms of a switch with these phases)
        st.update(0.0001f, Float.NaN, 0f, 0f)
        assertTrue(st.lampVersion - v0 <= 1)
        val before = st.lamps.copyOf()
        val v1 = st.lampVersion
        st.update(0f, Float.NaN, 0f, 0f)
        assertEquals(v1, st.lampVersion)
        assertContentEquals(before, st.lamps)
    }

    @Test
    fun padLampsShowIdleAssignedAndClamped() {
        val m = model("FoundryStation")
        val st = StationInstance(m)
        val p = m.pad(4)!!
        val o = p.lampSlot * 4
        st.update(0.01f, Float.NaN, 0f, 0f)
        assertContentEquals(floatArrayOf(70 / 255f, 120 / 255f, 160 / 255f, 1f), st.lamps.copyOfRange(o, o + 4))
        st.setPadState(4, StationInstance.PAD_ASSIGNED)
        // assigned blinks: 0.5 s full, 0.3 s at transparency 0.7, period 0.8 s on the station clock
        var bright = 0
        var dim = 0
        repeat(800) {
            st.update(0.001f, Float.NaN, 0f, 0f)
            assertContentEquals(floatArrayOf(1f, 170 / 255f, 60 / 255f), st.lamps.copyOfRange(o, o + 3))
            val a = st.lamps[o + 3]
            if (a == 1f) bright++ else {
                near(0.3f, a, 1e-6f, "dim opacity")
                dim++
            }
        }
        near(0.5f / 0.8f, bright / 800f, 0.01f, "bright share")
        st.setPadState(4, StationInstance.PAD_CLAMPED)
        st.update(0.001f, Float.NaN, 0f, 0f)
        assertContentEquals(floatArrayOf(120 / 255f, 220 / 255f, 150 / 255f, 1f), st.lamps.copyOfRange(o, o + 4))
        assertEquals(StationInstance.PAD_CLAMPED, st.padState(4))
        assertTrue(dim > 0)
    }

    @Test
    fun papiShowsWhiteAboveItsGlidePathAndRedBelow() {
        val m = model("SpireStation")
        val st = StationInstance(m)
        val papi = (1..m.lamps.size).filter { m.lamps[it - 1].kind == StationModel.LAMP_PAPI }
        assertEquals(4, papi.size)
        // 1500 m out on the approach axis at 1 degree above the lamps: thresholds -1.5, -0.5, 0.5 white, 1.5 red
        val l0 = m.lamps[papi[0] - 1]
        val dist = 1500f
        val y = l0.papiY + dist * kotlin.math.tan(Math.toRadians(1.0)).toFloat()
        st.update(0.016f, l0.papiX, y, l0.papiZ - dist)
        for (s in papi) {
            val l = m.lamps[s - 1]
            val elev = Math.toDegrees(kotlin.math.atan2((y - l.papiY).toDouble(), (dist - (l.papiZ - l0.papiZ)).toDouble()))
            val white = elev >= Math.toDegrees(l.papiThreshold.toDouble())
            assertEquals(if (white) 1 else 0, st.papiState(s), "lamp threshold ${Math.toDegrees(l.papiThreshold.toDouble())}")
            val c = if (white) StationInstance.PAPI_WHITE else StationInstance.PAPI_RED
            assertContentEquals(c, st.lamps.copyOfRange(s * 4, s * 4 + 3))
        }
        assertEquals(3, papi.count { st.papiState(it) == 1 })
        // far below the glide path: all red; far above: all white
        st.update(0.016f, 0f, -600f, -1500f)
        assertTrue(papi.all { st.papiState(it) == 0 })
        st.update(0.016f, 0f, 800f, -1500f)
        assertTrue(papi.all { st.papiState(it) == 1 })
    }

    @Test
    fun accentColourGoesToTheAccentSlot() {
        val m = model("SpireStation")
        val st = StationInstance(m)
        st.setAccent(80, 170, 255)
        val o = m.accentSlot * 4
        assertContentEquals(floatArrayOf(80 / 255f, 170 / 255f, 1f, -1f), st.lamps.copyOfRange(o, o + 4))
        // no accent parts on the Ring: ignored
        StationInstance(model("RingStation")).setAccent(1, 2, 3)
    }

    @Test
    fun groupsSpinAtTheirRates() {
        val m = model("SpireStation")
        val st = StationInstance(m, seed = 7)
        val a0 = st.angles.copyOf()
        val dt = 1f / 60f
        repeat(600) { st.update(dt, Float.NaN, 0f, 0f) } // 10 s
        for (g in 1 until m.groups.size) {
            val expected = (a0[g] + m.groups[g].radiansPerSecond * 10f).mod((Math.PI * 2).toFloat())
            near(expected, st.angles[g], 2e-3f, m.groups[g].name)
        }
        // the frame rotates points about the group's axis through its centre
        val lh = m.groups.indexOfFirst { it.name == "Lighthouse" }
        val rows = FloatArray(12)
        st.groupRows(lh, rows, 0)
        val gr = m.groups[lh]
        // the centre stays put
        val cx = rows[0] * gr.centreX + rows[1] * gr.centreY + rows[2] * gr.centreZ + rows[3]
        val cz = rows[8] * gr.centreX + rows[9] * gr.centreY + rows[10] * gr.centreZ + rows[11]
        near(gr.centreX, cx, 1e-2f, "centre x")
        near(gr.centreZ, cz, 1e-2f, "centre z")
        // a point 100 m off the axis turns by the group's angle
        val px = gr.centreX + 100f
        val x = rows[0] * px + rows[1] * gr.centreY + rows[2] * gr.centreZ + rows[3]
        val z = rows[8] * px + rows[9] * gr.centreY + rows[10] * gr.centreZ + rows[11]
        near(100f * kotlin.math.cos(st.angles[lh]), x - gr.centreX, 1e-2f, "x")
        near(-100f * kotlin.math.sin(st.angles[lh]), z - gr.centreZ, 1e-2f, "z")
        // deterministic start angles per seed
        assertContentEquals(StationInstance(m, seed = 7).angles, StationInstance(m, seed = 7).angles)
        assertFalse(StationInstance(m, seed = 7).angles.contentEquals(StationInstance(m, seed = 8).angles))
    }

    @Test
    fun siteStagesShowBuiltPlanAndHidden() {
        val m = model(StationModel.SITE)
        val st = StationInstance(m, seed = 5)
        assertEquals(0f, st.spin)
        // the stage groups never turn: no start angle, identity frames
        assertTrue(st.angles.all { it == 0f })
        assertContentEquals(GroupFrames(m.groups.size).rot, st.frames.rot)
        st.setSiteStages(2, building = false)
        val modes = st.groupModes
        assertEquals(1f, modes[0]) // base
        assertEquals(1f, modes[1 * 4]); assertEquals(1f, modes[2 * 4])
        assertEquals(0f, modes[3 * 4]); near(1f - 0.55f, modes[3 * 4 + 1], 1e-6f, "waiting hologram")
        assertEquals(0f, modes[4 * 4]); assertEquals(0f, modes[4 * 4 + 1])
        val v = st.groupVersion
        st.setSiteStages(2, building = false)
        assertEquals(v, st.groupVersion, "no change, no upload")
        st.setSiteStages(2, building = true)
        near(1f - 0.2f, modes[3 * 4 + 1], 1e-6f, "building hologram")
        assertTrue(st.frames.solid[2] && !st.frames.solid[3])
        // nothing turns on a site
        val a = st.angles.copyOf()
        st.update(100f, Float.NaN, 0f, 0f)
        assertContentEquals(a, st.angles)
    }

    @Test
    fun placementFollowsShipStations() {
        // 08-stations-economy.md A.5: host (828660, 0, 0), star at the origin, MERIDIAN (90, 24, 6600)
        val o = StationPlacement.offset(828660.0, 0.0, 0.0, 0.0, 0.0, 0.0, 90.0, 24.0, 6600.0, DVec3())
        near(0f, o.x.toFloat(), 0.01f, "x")
        near(2684.46f, o.y.toFloat(), 0.01f, "y")
        near(6029.40f, o.z.toFloat(), 0.01f, "z")
        // the mouth (-Z) faces the host
        val q = StationPlacement.rotation(o.x, o.y, o.z, Quat())
        val look = q.rotate(0f, 0f, -1f, Vec3())
        val l = sqrt(o.x * o.x + o.y * o.y + o.z * o.z).toFloat()
        near(-o.x.toFloat() / l, look.x, 1e-5f, "look x")
        near(-o.y.toFloat() / l, look.y, 1e-5f, "look y")
        near(-o.z.toFloat() / l, look.z, 1e-5f, "look z")
        // up stays as close to +Y as it can
        val up = q.rotate(0f, 1f, 0f, Vec3())
        assertTrue(up.y > 0.9f)
        // nearly vertical: up = +X instead
        val qv = StationPlacement.rotation(0.0, -5000.0, 1.0, Quat())
        assertTrue(qv.rotate(0f, 1f, 0f, Vec3()).x > 0.99f)
        // world <-> station round trip
        val pos = DVec3(1.0e9, 2.0e6, -3.0e8)
        val local = StationPlacement.toStation(pos.x + 100.0, pos.y - 20.0, pos.z + 5.0, pos, q, Vec3())
        val back = StationPlacement.toWorld(local.x, local.y, local.z, pos, q, Vec3(), DVec3())
        near(100f, (back.x - pos.x).toFloat(), 1e-3f, "x")
        near(-20f, (back.y - pos.y).toFloat(), 1e-3f, "y")
        near(5f, (back.z - pos.z).toFloat(), 1e-3f, "z")
    }
}
