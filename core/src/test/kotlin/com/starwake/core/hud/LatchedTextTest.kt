package com.starwake.core.hud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Roblox's HUD text timer (`Hud.TextUpdateInterval` 0.1 s, `ShipHud.lua:375-379`; 02-flight.md 9.6). */
class LatchedTextTest {
    private val dt = 1f / 60f

    /** A different live text every frame (the worst case of a status that flips each frame). */
    private val live = Array(1201) { "T$it" }

    @Test
    fun aTextThatChangesEveryFrameIsShownAtMostTenTimesASecond() {
        val l = LatchedText()
        l.update(dt, true, live[0])
        assertTrue(l.visible)
        assertEquals("T0", l.text, "the line appears at once with the live text")
        var changes = 0
        var lastChange = 0
        var minGap = Int.MAX_VALUE
        var shown = l.text
        for (i in 1..1200) {
            l.update(dt, true, live[i])
            if (l.text != shown) {
                changes++
                minGap = minOf(minGap, i - lastChange)
                lastChange = i
                shown = l.text
            }
        }
        // 20 s at 60 fps: the live text changed 1200 times, the shown one every 6 or 7 frames
        assertTrue(changes in 171..200, "$changes changes in 20 s")
        assertTrue(minGap >= 6, "two changes $minGap frames apart (0.1 s = 6 frames)")
        assertTrue(lastChange >= 1200 - 7, "it keeps following: last change at frame $lastChange")
    }

    @Test
    fun theLineAppearsAndDisappearsAtOnce() {
        val l = LatchedText()
        l.update(dt, true, "ALIGNING")
        l.update(dt, true, "CRUISING")
        assertEquals("ALIGNING", l.text, "not yet: the interval runs")
        l.update(dt, false, "ARRIVED")
        assertFalse(l.visible, "gone in the frame it disengaged")
        l.update(dt, true, "ENGAGED")
        assertTrue(l.visible)
        assertEquals("ENGAGED", l.text, "back in the frame it engaged, with its own text")
        // and the steady text after a change shows within the interval
        var n = 0
        while (l.text != "BRAKING" && n < 60) {
            l.update(dt, true, "BRAKING")
            n++
        }
        assertEquals("BRAKING", l.text)
        assertTrue(n in 6..7, "shown after $n frames")
    }
}
