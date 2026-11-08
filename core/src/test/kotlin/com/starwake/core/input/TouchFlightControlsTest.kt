package com.starwake.core.input

import com.starwake.core.Settings
import com.starwake.core.ui.UiHost
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
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

    private fun controls(settings: Settings = Settings()): TouchFlightControls =
        TouchFlightControls(settings, host).also { it.layout(w, h, u, 0f, 0f, 0f, 0f) }

    @Test
    fun stickTouchDownNearAnEdgeGivesNoInput() {
        val c = controls()
        // right thumb lands 20 dp above the bottom edge and does not move
        assertTrue(c.onDown(1, w * 0.8f, h - 20f * u))
        c.update(1f / 60f)
        assertEquals(0f, abs(c.pitch))
        assertEquals(0f, abs(c.roll))
        // a touch just right of the zone split
        c.onUp(1, 0f, 0f, false)
        assertTrue(c.onDown(2, w * 0.42f + 5f, h * 0.7f))
        c.update(1f / 60f)
        assertEquals(0f, abs(c.roll))
    }

    @Test
    fun stickDragUpClimbsByDefaultAndDivesWhenInverted() {
        val c = controls()
        c.onDown(1, w * 0.75f, h * 0.7f)
        c.onMove(1, w * 0.75f, h * 0.7f - 40f * u)
        c.update(1f / 60f)
        assertTrue(c.pitch > 0.4f, "pitch ${c.pitch}")
        val inverted = controls(Settings().also { it.pitchUpClimb = false })
        inverted.onDown(1, w * 0.75f, h * 0.7f)
        inverted.onMove(1, w * 0.75f, h * 0.7f - 40f * u)
        inverted.update(1f / 60f)
        assertTrue(inverted.pitch < -0.4f, "pitch ${inverted.pitch}")
    }

    @Test
    fun throttleZeroDetentIsSymmetric() {
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
        c.onMove(1, gx + 80f * u, h * 0.6f + 40f * u)
        assertTrue(c.throttle < -0.05f)
        // the lever latches when released
        val t = c.throttle
        c.onUp(1, 0f, 0f, false)
        assertEquals(t, c.throttle)
    }

    @Test
    fun verticalThrustButtonsAreHeld() {
        val c = controls()
        c.onDown(3, c.upBtn.cx, c.upBtn.cy)
        c.update(1f / 60f)
        assertEquals(1f, c.vertical)
        c.onUp(3, c.upBtn.cx, c.upBtn.cy, false)
        c.update(1f / 60f)
        assertEquals(0f, c.vertical)
        c.onDown(4, c.downBtn.cx, c.downBtn.cy)
        c.update(1f / 60f)
        assertEquals(-1f, c.vertical)
    }

    @Test
    fun lockButtonFiresOnce() {
        val c = controls()
        c.onDown(5, c.lockBtn.cx, c.lockBtn.cy)
        assertTrue(c.consumeLock())
        assertTrue(!c.consumeLock())
    }

    @Test
    fun leftHandedLayoutKeepsEachSidesInset() {
        val s = Settings().also { it.leftHanded = true }
        val c = TouchFlightControls(s, host)
        val cutout = 100f
        c.layout(w, h, u, 0f, 0f, cutout, 0f) // cutout on the right edge
        // the throttle gauge moved to the right side and must clear the right inset
        assertTrue(c.gauge.x + c.gauge.w <= w - cutout, "gauge ${c.gauge.x}..${c.gauge.x + c.gauge.w}")
        assertTrue(abs(c.pauseBtn.x + c.pauseBtn.w - (w - cutout - 14f * u)) < 1f)
    }
}
