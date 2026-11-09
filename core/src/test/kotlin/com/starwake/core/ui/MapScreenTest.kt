package com.starwake.core.ui

import com.starwake.core.Haptics
import com.starwake.core.game.StarSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** System map gestures (no GL: only the touch handlers and the view transform are exercised). */
class MapScreenTest {
    private val system = StarSystem()
    private val haptics = ArrayList<Int>()

    private val host = object : MapHost {
        override fun haptic(type: Int) {
            haptics += type
        }

        override fun closeMap() {}
        override fun lockFromMap(kind: Int, index: Int) {}
    }

    /** A 20:9 phone map, opened at the spawn point like Game.openMap does. */
    private fun map(w: Float = 2400f, h: Float = 1080f): MapScreen {
        val m = MapScreen(host, system)
        m.layout(Frame().also { it.w = w; it.h = h; it.u = 2.75f })
        m.open(system.home, 0, -1)
        return m
    }

    private fun body(name: String) = system.bodies.first { it.name == name }

    /** Two fingers on the map, as ids 0 and 1, with their current positions. */
    private class Pinch(val m: MapScreen, x0: Float, y0: Float, x1: Float, y1: Float) {
        val x = floatArrayOf(x0, x1)
        val y = floatArrayOf(y0, y1)
        val midX get() = (x[0] + x[1]) * 0.5f
        val midY get() = (y[0] + y[1]) * 0.5f

        init {
            m.down(0, x0, y0)
            m.down(1, x1, y1)
        }

        fun move(finger: Int, nx: Float, ny: Float) {
            x[finger] = nx; y[finger] = ny
            m.move(finger, nx, ny)
        }

        fun up() {
            m.up(1, x[1], y[1], false)
            m.up(0, x[0], y[0], false)
        }
    }

    /** The system point ([wx], [wz]) must be drawn at the pinch midpoint (within a hundredth of a pixel). */
    private fun assertUnderMidpoint(p: Pinch, wx: Double, wz: Double, step: String) {
        val sx = p.m.screenX(wx)
        val sy = p.m.screenY(wz)
        if (abs(sx - p.midX) > 0.01f || abs(sy - p.midY) > 0.01f) {
            fail("$step: anchored point drawn at ($sx, $sy), finger midpoint is (${p.midX}, ${p.midY}), zoom ${p.m.metresPerPixel} m/px")
        }
    }

    @Test
    fun pinchZoomsAboutTheFingersNotTheScreenCentre() {
        val m = map()
        // pinch on PLANET II, well away from the screen centre
        val p2 = body("PLANET II")
        val cx = m.screenX(p2.pos.x)
        val cy = m.screenY(p2.pos.z)
        assertTrue(abs(cx - 1200f) > 150f || abs(cy - 540f) > 150f, "PLANET II should open off-centre: ($cx, $cy)")
        val mpp0 = m.metresPerPixel
        val p = Pinch(m, cx - 30f, cy, cx + 30f, cy)
        val wx = m.worldX(p.midX)
        val wz = m.worldZ(p.midY)
        // spread both fingers to 10x their distance, one move event per finger per step
        for (k in 1..20) {
            val half = 30f + 270f * k / 20f
            p.move(0, cx - half, cy)
            assertUnderMidpoint(p, wx, wz, "step $k finger 0")
            p.move(1, cx + half, cy)
            assertUnderMidpoint(p, wx, wz, "step $k finger 1")
        }
        assertEquals(0.1, m.metresPerPixel / mpp0, 1e-4)
        // the pinched planet itself is still under the fingers
        assertEquals(cx, m.screenX(p2.pos.x), 0.01f)
        assertEquals(cy, m.screenY(p2.pos.z), 0.01f)
        p.up()
    }

    @Test
    fun movingOneFingerZoomsAndPansWithTheMidpoint() {
        val m = map()
        val p = Pinch(m, 1500f, 300f, 1600f, 400f)
        val wx = m.worldX(p.midX)
        val wz = m.worldZ(p.midY)
        val mpp0 = m.metresPerPixel
        // only the second finger moves: the midpoint travels, and the point under it travels along
        for (k in 1..15) {
            p.move(1, 1600f + 40f * k, 400f + 25f * k)
            assertUnderMidpoint(p, wx, wz, "step $k")
        }
        assertTrue(m.metresPerPixel < mpp0, "spreading must zoom in")
        p.up()
    }

    @Test
    fun twoFingerDragPansWithoutZooming() {
        val m = map()
        val p = Pinch(m, 700f, 500f, 900f, 600f)
        val wx = m.worldX(p.midX)
        val wz = m.worldZ(p.midY)
        val mpp0 = m.metresPerPixel
        for (k in 1..12) {
            p.move(0, 700f + 30f * k, 500f - 12f * k)
            p.move(1, 900f + 30f * k, 600f - 12f * k)
            assertUnderMidpoint(p, wx, wz, "step $k")
        }
        // (each finger moves on its own event, so the spread wobbles and the zoom is float-rounded)
        assertEquals(mpp0, m.metresPerPixel, mpp0 * 1e-5)
        p.up()
    }

    @Test
    fun anchorHoldsAtTheZoomLimits() {
        val m = map()
        // pinch in hard, three times, so the zoom runs into its 50 m/px limit
        var last = Double.MAX_VALUE
        for (round in 0 until 3) {
            val p = Pinch(m, 1300f, 600f, 1310f, 600f)
            val wx = m.worldX(p.midX)
            val wz = m.worldZ(p.midY)
            for (k in 1..30) {
                p.move(0, 1300f - 30f * k, 600f + 3f * k)
                p.move(1, 1310f + 30f * k, 600f - 3f * k)
                assertUnderMidpoint(p, wx, wz, "zoom-in round $round step $k")
            }
            p.up()
            assertTrue(m.metresPerPixel <= last)
            last = m.metresPerPixel
        }
        assertEquals(50.0, m.metresPerPixel, 1e-3)

        // and out again past the 3e7 m/px limit (x10 per pinch), fingers drifting sideways as they close
        for (round in 0 until 7) {
            val p = Pinch(m, 200f, 300f, 2200f, 800f)
            val wx = m.worldX(p.midX)
            val wz = m.worldZ(p.midY)
            for (k in 1..20) {
                p.move(0, 200f + 45f * k + 3f * k, 300f + 11.25f * k)
                p.move(1, 2200f - 45f * k + 3f * k, 800f - 11.25f * k)
                assertUnderMidpoint(p, wx, wz, "zoom-out round $round step $k")
            }
            p.up()
        }
        assertEquals(3.0e7, m.metresPerPixel, 1.0)
    }

    @Test
    fun liftingOneFingerKeepsPanningWithoutAJump() {
        val m = map()
        val p = Pinch(m, 800f, 500f, 1000f, 500f)
        p.move(1, 1200f, 500f)
        // first finger lifts: the remaining one drags the map and the point under it follows it
        m.up(0, p.x[0], p.y[0], false)
        val wx = m.worldX(1200f)
        val wz = m.worldZ(500f)
        m.move(1, 1100f, 650f)
        assertEquals(1100f, m.screenX(wx), 0.01f)
        assertEquals(650f, m.screenY(wz), 0.01f)
        m.up(1, 1100f, 650f, false)
    }

    @Test
    fun tapSelectsButAPinchReleaseDoesNot() {
        val m = map()
        val p1 = body("PLANET I")
        val x = m.screenX(p1.pos.x)
        val y = m.screenY(p1.pos.z)
        haptics.clear()
        val p = Pinch(m, x - 20f, y, x + 20f, y)
        p.move(1, x + 25f, y)
        p.up()
        assertFalse(Haptics.TICK in haptics, "releasing a pinch must not select")
        val x2 = m.screenX(p1.pos.x)
        val y2 = m.screenY(p1.pos.z)
        m.down(5, x2, y2)
        m.up(5, x2, y2, false)
        assertTrue(Haptics.TICK in haptics, "a tap on PLANET I selects it")
    }

    @Test
    fun wheelZoomKeepsTheCursorPoint() {
        val m = map()
        val wx = m.worldX(1700f)
        val wz = m.worldZ(250f)
        m.zoom(0.5f, 1700f, 250f)
        assertEquals(1700f, m.screenX(wx), 0.01f)
        assertEquals(250f, m.screenY(wz), 0.01f)
        // default focus: the screen centre stays put
        val cx = m.worldX(1200f)
        m.zoom(2f)
        assertEquals(1200f, m.screenX(cx), 0.01f)
    }
}
