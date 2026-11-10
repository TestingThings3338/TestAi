package com.starwake.core.input

import com.starwake.core.Settings
import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.ui.Rect
import com.starwake.core.ui.UiHost
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TouchFlightControlsTest {
    private val w = 2670f
    private val h = 1200f
    private val u = 2.75f
    private val haptics = ArrayList<Int>()
    private val host = object : UiHost {
        override fun haptic(type: Int) {
            haptics += type
        }
    }
    private val f = InputFrame()

    private fun controls(settings: Settings = Settings()): TouchFlightControls =
        TouchFlightControls(settings, host).also { it.layout(w, h, u, 0f, 0f, 0f, 0f) }

    /** One frame of input: the controls write into a cleared frame. */
    private fun write(c: TouchFlightControls, dt: Float = 1f / 60f): InputFrame {
        f.begin()
        c.write(f, dt)
        return f
    }

    @Test
    fun stickTouchDownNearAnEdgeGivesNoInput() {
        val c = controls()
        // right thumb lands 20 dp above the bottom edge and does not move
        assertTrue(c.onDown(1, w * 0.8f, h - 20f * u))
        write(c)
        assertEquals(0f, abs(f.aimX))
        assertEquals(0f, abs(f.aimY))
        // a touch just right of the zone split
        c.onUp(1, 0f, 0f, false)
        assertTrue(c.onDown(2, w * 0.42f + 5f, h * 0.7f))
        write(c)
        assertEquals(0f, abs(f.aimX))
    }

    @Test
    fun stickUpIsCursorUpByDefaultAndDownWhenInverted() {
        // the aim is the Roblox cursor: Y down, so a climb (stick up) is a negative aim Y
        val c = controls()
        c.onDown(1, w * 0.75f, h * 0.7f)
        c.onMove(1, w * 0.75f, h * 0.7f - 40f * u)
        write(c)
        assertTrue(f.aimY < -0.4f, "aim ${f.aimY}")
        val inverted = controls(Settings().also { it.pitchUpClimb = false })
        inverted.onDown(1, w * 0.75f, h * 0.7f)
        inverted.onMove(1, w * 0.75f, h * 0.7f - 40f * u)
        write(inverted)
        assertTrue(f.aimY > 0.4f, "aim ${f.aimY}")
    }

    @Test
    fun stickStaysInsideTheUnitCircleAndScalesWithSensitivity() {
        val c = controls(Settings().also { it.sensitivity = 1.5f })
        c.onDown(1, w * 0.75f, h * 0.6f)
        // a drag of half the stick radius to the right, then far beyond it
        c.onMove(1, w * 0.75f + 26f * u, h * 0.6f)
        write(c)
        assertNear(0.75f, f.aimX, 1e-4f, "0.5 deflection x 1.5 sensitivity")
        c.onMove(1, w * 0.75f + 400f * u, h * 0.6f + 400f * u)
        write(c)
        assertNear(1f, f.aimMagnitude, 1e-4f)
        c.onUp(1, 0f, 0f, false)
        write(c)
        assertEquals(0f, f.aimMagnitude, "the stick springs back")
    }

    @Test
    fun throttleZeroDetentIsSymmetricAndTheLeverLatches() {
        val c = controls()
        val gx = c.gauge.cx
        // grab at zero (throttle starts at 0) and wiggle a few dp either way
        c.onDown(1, gx + 80f * u, h * 0.6f)
        for (dy in floatArrayOf(-5f, 5f, -6f, 6f)) {
            c.onMove(1, gx + 80f * u, h * 0.6f + dy * u)
            assertEquals(0f, c.throttle, "dy=$dy dp")
        }
        c.onMove(1, gx + 80f * u, h * 0.6f - 40f * u)
        assertTrue(c.throttle > 0.05f)
        write(c)
        assertTrue(f.leverSet, "a moved lever reports its new position")
        assertEquals(c.throttle, f.lever)
        write(c)
        assertFalse(f.leverSet, "an unmoved lever reports nothing")
        c.onMove(1, gx + 80f * u, h * 0.6f + 40f * u)
        assertTrue(c.throttle < -0.05f)
        // the lever latches when released
        val t = c.throttle
        c.onUp(1, 0f, 0f, false)
        assertEquals(t, c.throttle)
    }

    @Test
    fun theLeverRunsFromMinus25To100PercentWithZeroAtAFifth() {
        // DECISIONS 19: an absolute lever, −25 %..100 %, the zero line at 20 % of the gauge
        val c = controls()
        val g = c.gauge
        val x = g.cx + 80f * u
        // a drag over the whole gauge from the zero line: up to 100 %, down to −25 %
        c.onDown(1, x, h * 0.6f)
        c.onMove(1, x, h * 0.6f - g.h * 0.8f)
        assertEquals(1f, c.throttle, 1e-4f)
        c.onMove(1, x, h * 0.6f - g.h * 0.4f)
        assertEquals(0.5f, c.throttle, 1e-3f, "half way up the forward part is 50 %")
        c.onMove(1, x, h * 0.6f + g.h * 0.2f)
        assertEquals(-0.25f, c.throttle, 1e-4f, "the bottom fifth is the reverse quarter")
        c.onMove(1, x, h * 0.6f + g.h)
        assertEquals(-0.25f, c.throttle, "clamped")
        c.onUp(1, x, 0f, false)
    }

    @Test
    fun doubleTapOnTheLeverZeroesIt() {
        val c = controls()
        c.throttle = 0.6f
        val x = c.gauge.cx + 80f * u
        for (k in 0..1) {
            c.onDown(1, x, h * 0.6f)
            write(c, 0.05f)
            c.onUp(1, x, h * 0.6f, false)
            write(c, 0.05f)
        }
        assertEquals(0f, c.throttle)
        assertTrue(f.leverSet, "the zero goes to the session")
    }

    @Test
    fun doubleTapAndHoldIsTheFullStop() {
        // Roblox X: held, the lever runs to zero in 1.2 s and every axis brakes (the thrust does that)
        val c = controls()
        c.throttle = 0.8f
        val x = c.gauge.cx + 80f * u
        c.onDown(1, x, h * 0.6f)
        write(c, 0.05f)
        c.onUp(1, x, h * 0.6f, false)
        write(c, 0.05f)
        assertFalse(f.held(Action.FULL_STOP), "one tap is nothing")
        c.onDown(1, x, h * 0.6f)
        for (i in 0 until 30) {
            write(c, 1f / 60f)
            assertTrue(f.held(Action.FULL_STOP), "frame $i of the hold")
        }
        // dragging while it is held does not move the lever (the thumb only holds the stop)
        c.onMove(1, x, h * 0.6f - 60f * u)
        assertEquals(0.8f, c.throttle)
        c.onUp(1, x, h * 0.6f - 60f * u, false)
        write(c)
        assertFalse(f.held(Action.FULL_STOP))
        assertEquals(0f, c.throttle, "the lever ends at zero")
        // a later single touch is the lever again
        c.onDown(2, x, h * 0.6f)
        write(c, 0.5f)
        assertFalse(f.held(Action.FULL_STOP))
        c.onUp(2, x, h * 0.6f, false)
    }

    @Test
    fun leverSidewaysIsTheKeyAxis() {
        val c = controls()
        val x = c.gauge.cx + 80f * u
        c.onDown(1, x, h * 0.6f)
        c.onMove(1, x + 48f * u, h * 0.6f) // full right
        write(c)
        assertEquals(-1f, f.keyAxis, "right = -1 (Roblox: +1 is Q, yaw left)")
        c.onMove(1, x - 48f * u, h * 0.6f)
        write(c)
        assertEquals(1f, f.keyAxis)
    }

    @Test
    fun strafeCrossButtonsAreHeld() {
        val c = controls()
        val cases = listOf(c.upBtn to (0f to 1f), c.downBtn to (0f to -1f), c.leftBtn to (-1f to 0f), c.rightBtn to (1f to 0f))
        for ((r, v) in cases) {
            assertTrue(c.onDown(3, r.cx, r.cy))
            write(c)
            assertEquals(v.first, f.lateralX)
            assertEquals(v.second, f.lateralY)
            c.onUp(3, r.cx, r.cy, false)
            write(c)
            assertEquals(0f, f.lateralX)
            assertEquals(0f, f.lateralY)
        }
        // two buttons at once (up and right)
        c.onDown(4, c.upBtn.cx, c.upBtn.cy)
        c.onDown(5, c.rightBtn.cx, c.rightBtn.cy)
        write(c)
        assertEquals(1f, f.lateralX)
        assertEquals(1f, f.lateralY)
        assertTrue(f.held(Action.STRAFE_UP) && f.held(Action.STRAFE_RIGHT))
    }

    @Test
    fun lockTapCyclesAndHoldClears() {
        val c = controls()
        c.onDown(5, c.lockBtn.cx, c.lockBtn.cy)
        write(c, 0.1f)
        assertFalse(f.pressed(Action.CYCLE_TARGET), "a tap acts on release")
        c.onUp(5, c.lockBtn.cx, c.lockBtn.cy, false)
        write(c, 0.016f)
        assertEquals(1, f.presses(Action.CYCLE_TARGET))
        write(c)
        assertFalse(f.pressed(Action.CYCLE_TARGET), "fires once")
        // held 0.5 s: clear, and no cycle on release
        c.onDown(5, c.lockBtn.cx, c.lockBtn.cy)
        var cleared = 0
        repeat(40) { cleared += write(c, 1f / 60f).presses(Action.CLEAR_TARGET) }
        assertEquals(1, cleared)
        c.onUp(5, c.lockBtn.cx, c.lockBtn.cy, false)
        write(c)
        assertFalse(f.pressed(Action.CYCLE_TARGET))
    }

    @Test
    fun buttonsSendTheirActions() {
        val c = controls()
        f.contextAction = Action.LAND
        c.actionLabel = "LAND"
        c.onDown(1, c.camBtn.cx, c.camBtn.cy)
        c.onDown(2, c.mapBtn.cx, c.mapBtn.cy)
        c.onDown(3, c.boostBtn.cx, c.boostBtn.cy)
        c.onDown(4, c.faBtn.cx, c.faBtn.cy)
        c.onDown(6, c.actionBtn.cx, c.actionBtn.cy)
        f.begin()
        f.contextAction = Action.LAND
        c.write(f, 1f / 60f)
        for (a in listOf(Action.EXTERNAL_VIEW, Action.SYSTEM_MAP, Action.BOOST, Action.TOGGLE_FLIGHT_ASSIST, Action.LAND)) {
            assertEquals(1, f.presses(a), "$a")
        }
        assertTrue(f.held(Action.BOOST))
        // the pill sends what it shows: DOCK when the game says so
        c.onUp(6, 0f, 0f, false)
        c.onDown(6, c.actionBtn.cx, c.actionBtn.cy)
        f.begin()
        f.contextAction = Action.DOCK
        c.write(f, 1f / 60f)
        assertEquals(1, f.presses(Action.DOCK))
        assertFalse(f.pressed(Action.LAND))
        // pause fires on release over the button
        c.onDown(7, c.pauseBtn.cx, c.pauseBtn.cy)
        c.onUp(7, c.pauseBtn.cx, c.pauseBtn.cy, false)
        assertEquals(1, write(c).presses(Action.PAUSE))
    }

    @Test
    fun hiddenPillAndLaterControlsTakeNoTouches() {
        val c = controls()
        c.actionLabel = ""
        // with no label the pill is not there: the touch goes to the stick instead
        c.onDown(1, c.actionBtn.cx, c.actionBtn.cy)
        f.begin()
        f.contextAction = Action.LAND
        c.write(f, 1f / 60f)
        assertFalse(f.pressed(Action.LAND))
        c.releaseAll()
        // WALK, DATA, SCAN have no feature yet, AUTO shows only with a lock: never pressed hidden
        for (r in listOf(c.walkBtn, c.dataBtn, c.autoBtn, c.scanBtn)) {
            c.onDown(2, r.cx, r.cy)
            write(c)
            for (a in listOf(Action.LEAVE_SEAT, Action.STATION_PANEL, Action.AUTOPILOT, Action.SURVEY)) assertFalse(f.pressed(a))
            c.onUp(2, r.cx, r.cy, false)
        }
        // with a target locked AUTO is there: the autopilot (Roblox P)
        c.autoVisible = true
        c.onDown(3, c.autoBtn.cx, c.autoBtn.cy)
        assertEquals(1, write(c).presses(Action.AUTOPILOT))
        c.onUp(3, c.autoBtn.cx, c.autoBtn.cy, false)
    }

    @Test
    fun lookStripDragLooksAroundAtHalfADegreePerU() {
        val c = controls()
        val s = c.lookStrip
        assertTrue(c.onDown(9, s.cx, s.cy))
        c.onMove(9, s.cx + 100f * u, s.cy - 20f * u)
        write(c)
        assertTrue(f.lookHeld && f.held(Action.LOOK))
        assertNear(50f, f.lookX, 1e-3f, "100 u right = 50°")
        assertNear(10f, f.lookY, 1e-3f, "20 u up = 10° up")
        c.onUp(9, 0f, 0f, false)
        write(c)
        assertFalse(f.lookHeld)
        // invert pitch inverts the vertical look; sensitivity scales it
        val inv = controls(Settings().also { it.pitchUpClimb = false; it.sensitivity = 0.5f })
        inv.onDown(9, s.cx, s.cy)
        inv.onMove(9, s.cx, s.cy - 20f * u)
        write(inv)
        assertNear(-5f, f.lookY, 1e-3f)
        // switched off (chase view): the strip is lever zone again
        val off = controls().also { it.lookEnabled = false }
        off.onDown(9, s.cx - 200f * u, s.cy)
        off.onMove(9, s.cx - 200f * u + 50f * u, s.cy)
        write(off)
        assertFalse(f.lookHeld)
    }

    /** 01-core-loop.md 5.6: the consolidated layout at the reference 929 × 417 u (u = 1, no insets). */
    @Test
    fun layoutMatchesTheConsolidatedTable() {
        val c = TouchFlightControls(Settings(), host).also { it.layout(929f, 417f, 1f, 0f, 0f, 0f, 0f) }
        fun box(r: Rect, x0: Float, y0: Float, x1: Float, y1: Float, what: String) {
            assertNear(x0, r.x, 0.6f, "$what x0")
            assertNear(y0, r.y, 0.6f, "$what y0")
            assertNear(x1, r.x + r.w, 0.6f, "$what x1")
            assertNear(y1, r.y + r.h, 0.6f, "$what y1")
        }
        fun circle(r: Rect, cx: Float, cy: Float, radius: Float, what: String) {
            assertNear(cx, r.cx, 0.6f, "$what cx")
            assertNear(cy, r.cy, 0.6f, "$what cy")
            assertNear(radius, r.w * 0.5f, 0.01f, "$what r")
        }
        box(c.pauseBtn, 14f, 12f, 60f, 50f, "PAUSE")
        box(c.walkBtn, 70f, 12f, 132f, 50f, "WALK")
        box(c.dataBtn, 655f, 12f, 711f, 50f, "DATA")
        box(c.mapBtn, 721f, 12f, 777f, 50f, "MAP")
        box(c.lockBtn, 787f, 12f, 849f, 50f, "LOCK")
        box(c.camBtn, 859f, 12f, 915f, 50f, "VIEW")
        box(c.lookStrip, 150f, 0f, 640f, 56f, "look strip")
        box(c.actionBtn, 822f, 75f, 914f, 109f, "ACTION")
        box(c.autoBtn, 822f, 117f, 914f, 149f, "AUTO")
        box(c.scanBtn, 60f, 74f, 140f, 106f, "SCAN")
        circle(c.faBtn, 776f, 108f, 23f, "FA")
        circle(c.boostBtn, 868f, 192f, 31f, "BOOST")
        box(c.gauge, 30f, 125f, 44f, 365f, "lever track")
        circle(c.upBtn, 130f, 140f, 19f, "▲")
        circle(c.leftBtn, 86f, 177f, 19f, "◀")
        circle(c.rightBtn, 174f, 177f, 19f, "▶")
        circle(c.downBtn, 130f, 214f, 19f, "▼")
        // nothing that is shown today overlaps anything else
        val shown = listOf(c.pauseBtn, c.mapBtn, c.lockBtn, c.camBtn, c.actionBtn, c.faBtn, c.boostBtn, c.upBtn, c.leftBtn, c.rightBtn, c.downBtn, c.lookStrip)
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            val a = shown[i]
            val b = shown[j]
            val overlap = a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h
            assertFalse(overlap, "controls $i and $j overlap")
        }
        // pressed-in-flight targets are at least 38 u
        for (r in listOf(c.upBtn, c.leftBtn, c.faBtn, c.boostBtn)) assertTrue(minOf(r.w, r.h) >= 38f - 1e-3f)
    }

    /**
     * The first-flight hint plates cover no control on the usual phone screens, either hand: BOOST
     * moved down to y 192 (5.6) and now reaches into the stick hint's row, so that plate moves inward.
     */
    @Test
    fun firstFlightHintsCoverNoControl() {
        // (width px, height px, u px): the 5.6 reference at u = 1, Xiaomi 15 at 2.875, the harness'
        // 2400 x 1080 at 2.75, a typical 2400 x 1080 at 2.625, a small 1600 x 720 at 2.0 (u = 0.947 dp),
        // a 4:3 tablet
        val screens = listOf(
            Triple(929f, 417f, 1f), Triple(2670f, 1200f, 2.875f), Triple(2400f, 1080f, 2.75f),
            Triple(2400f, 1080f, 2.625f), Triple(1600f, 720f, 2f * 360f / 380f), Triple(2048f, 1536f, 2f),
        )
        for ((sw, sh, su) in screens) for (left in listOf(false, true)) for (cutout in listOf(0f, 30f * su)) {
            val c = TouchFlightControls(Settings().also { it.leftHanded = left }, host)
            c.layout(sw, sh, su, cutout, 0f, 0f, 0f)
            val what = "${sw.toInt()}x${sh.toInt()} u=$su left-handed=$left cutout=$cutout"
            // every laid-out control, those of later ports included (they appear in the same places)
            val controls = listOf(
                "PAUSE" to c.pauseBtn, "WALK" to c.walkBtn, "DATA" to c.dataBtn, "MAP" to c.mapBtn, "LOCK" to c.lockBtn,
                "VIEW" to c.camBtn, "look strip" to c.lookStrip, "ACTION" to c.actionBtn, "AUTO" to c.autoBtn,
                "SCAN" to c.scanBtn, "FA" to c.faBtn, "BOOST" to c.boostBtn, "lever" to c.gauge, "▲" to c.upBtn,
                "▼" to c.downBtn, "◀" to c.leftBtn, "▶" to c.rightBtn, "CANCEL JUMP" to c.cancelJumpBtn,
            )
            for ((hint, r) in listOf("stick hint" to c.stickHint, "lever hint" to c.throttleHint)) {
                for ((name, b) in controls) {
                    val overlap = r.x < b.x + b.w && b.x < r.x + r.w && r.y < b.y + b.h && b.y < r.y + r.h
                    assertFalse(overlap, "$what: the $hint covers $name")
                }
                assertTrue(r.x >= 0f && r.x + r.w <= sw && r.y >= 0f && r.y + r.h <= sh, "$what: the $hint is off screen")
            }
            // the plates do not meet, and the stick's stays over the stick's half of the screen
            val a = c.stickHint
            val b = c.throttleHint
            assertFalse(a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h, "$what: the hints overlap")
            assertTrue(if (left) a.cx < sw * 0.5f else a.cx > sw * 0.5f, "$what: stick hint on the wrong side")
        }
    }

    @Test
    fun leftHandedLayoutKeepsEachSidesInsetAndTheArrows() {
        val s = Settings().also { it.leftHanded = true }
        val c = TouchFlightControls(s, host)
        val cutout = 100f
        c.layout(w, h, u, 0f, 0f, cutout, 0f) // cutout on the right edge
        // the throttle gauge moved to the right side and must clear the right inset
        assertTrue(c.gauge.x + c.gauge.w <= w - cutout, "gauge ${c.gauge.x}..${c.gauge.x + c.gauge.w}")
        assertTrue(abs(c.pauseBtn.x + c.pauseBtn.w - (w - cutout - 14f * u)) < 1f)
        // mirrored, ◀ still sits left of ▶ and strafes left
        assertTrue(c.leftBtn.cx < c.rightBtn.cx)
        c.onDown(1, c.leftBtn.cx, c.leftBtn.cy)
        write(c)
        assertEquals(-1f, f.lateralX)
    }
}
