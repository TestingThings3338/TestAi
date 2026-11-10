package com.starwake.core.hud

import com.starwake.core.Settings
import com.starwake.core.flight.Autopilot
import com.starwake.core.game.GearRig
import com.starwake.core.game.RecordingHost
import com.starwake.core.game.Session
import com.starwake.core.input.InputFrame
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.ui.Frame
import com.starwake.core.ui.UiHost
import com.starwake.core.world.WorldBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The first-flight hints count their own screen time: the game calls [SessionHud.updateHints] only
 * while the flight HUD is on screen, so the station services of a docked start do not use them up.
 * The HUD's texts follow the session at Roblox's 10 Hz cadence ([SessionHud.updateTexts]).
 */
class SessionHudTest {
    private val settings = Settings()
    private val hud = SessionHud(Frame(), settings)
    private val touch = TouchFlightControls(settings, object : UiHost {
        override fun haptic(type: Int) {}
    }).also { it.layout(2670f, 1200f, 2.875f, 0f, 0f, 0f, 0f) }

    private fun shown(seconds: Float) {
        repeat((seconds * 60f).toInt()) { hud.updateHints(1f / 60f, touch) }
    }

    @Test
    fun hintsStayForTwentyFiveSecondsOnScreenThenFade() {
        assertEquals(1f, hud.hints)
        shown(24.9f)
        assertEquals(1f, hud.hints, "full for 25 s of screen time")
        shown(1.1f)
        assertTrue(hud.hints < 1f && hud.hints > 0.6f, "fading at 0.3/s: ${hud.hints}")
        shown(3.4f)
        assertEquals(0f, hud.hints)
    }

    @Test
    fun theAutopilotStatusReadsAtTenHertzThroughTheBrakingAndShowsAtOnceOnEngageAndDisengage() {
        // AUTO to Luna from the MERIDIAN spawn: near 1.05 x the desired speed the live status flips
        // between CRUISING and BRAKING every frame (ShipAutopilot.lua:176, kept); the HUD's line and
        // AUTO's sub-label read it at Roblox's 10 Hz text cadence
        val system = WorldBuilder.sol()
        val s = Session(system, settings, RecordingHost(), GearRig()).also { it.startInSpace() }
        val input = InputFrame()
        val dt = 1f / 60f
        s.lock(Session.LOCK_BODY, system.bodyIndex("Luna"))
        s.autopilotButton()
        hud.updateTexts(dt, s)
        assertTrue(hud.autopilotLine.visible, "the line shows in the frame AUTO engaged")
        assertEquals(s.autopilot.status, hud.autopilotLine.text)
        var frame = 0
        var liveChanges = 0
        var shownChanges = 0
        var live = s.autopilot.status
        var shown = hud.autopilotLine.text
        var lastShownChange = 0
        var minGap = Int.MAX_VALUE
        var firstBraking = -1
        while (s.autopilot.engaged && frame < 60 * 400) {
            input.begin()
            s.frame(dt, input)
            hud.updateTexts(dt, s)
            frame++
            if (s.autopilot.status != live) {
                live = s.autopilot.status
                liveChanges++
                if (live == Autopilot.BRAKING && firstBraking < 0) firstBraking = frame
            }
            if (s.autopilot.engaged) {
                assertTrue(hud.autopilotLine.visible)
                if (hud.autopilotLine.text != shown) {
                    shown = hud.autopilotLine.text
                    shownChanges++
                    minGap = minOf(minGap, frame - lastShownChange)
                    lastShownChange = frame
                }
            }
        }
        assertFalse(s.autopilot.engaged)
        assertEquals(Autopilot.ARRIVED, s.autopilot.status)
        assertFalse(hud.autopilotLine.visible, "gone in the frame it arrived")
        val seconds = frame / 60.0
        println("autopilot to Luna: %.1f s, the live status changed %d times, the HUD's %d times (closest %d frames apart)".format(seconds, liveChanges, shownChanges, minGap))
        assertTrue(firstBraking > 0, "it braked")
        assertTrue(liveChanges > 200, "the faithful flicker is there: $liveChanges changes")
        assertTrue(minGap >= 6, "two shown changes $minGap frames apart (0.1 s = 6 frames)")
        assertTrue(shownChanges <= (seconds - firstBraking / 60.0) * 10.0 + 10, "$shownChanges shown changes")
    }

    @Test
    fun aNewFlightShowsThemAgain() {
        shown(30f)
        assertEquals(0f, hud.hints)
        hud.resetHints()
        assertEquals(1f, hud.hints)
        assertEquals(0f, hud.hintTime)
        shown(20f)
        assertEquals(1f, hud.hints, "the time of the last flight does not carry over")
    }
}
