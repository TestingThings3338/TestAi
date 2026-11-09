package com.starwake.core.hud

import com.starwake.core.Settings
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.ui.Frame
import com.starwake.core.ui.UiHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first-flight hints count their own screen time: the game calls [SessionHud.updateHints] only
 * while the flight HUD is on screen, so the station services of a docked start do not use them up.
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
