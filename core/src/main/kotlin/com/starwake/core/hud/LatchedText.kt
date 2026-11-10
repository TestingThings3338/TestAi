package com.starwake.core.hud

/**
 * A HUD text at Roblox's text cadence: `ShipHud` writes its texts at most once per
 * `Hud.TextUpdateInterval` (0.1 s, `ShipHud.lua:375-379`; the autopilot label `:504-507` among
 * them), and 02-flight.md 9.6 keeps the 10 Hz for readability. [update] takes the live text once
 * per simulated frame; [text] follows it at most ten times a second, so a value that changes every
 * frame reads steadily. The line appearing or disappearing ([visible]) shows at once, with the live
 * text, and starts a new interval. The strings are the caller's (constants), never built here: no
 * allocation.
 */
class LatchedText(private val interval: Float = INTERVAL) {
    /** The text as shown. */
    var text: String = ""
        private set

    /** The line is shown (it follows the live state at once). */
    var visible = false
        private set

    private var timer = 0f

    /** One frame: the line is [visibleNow] with the text [live]. */
    fun update(dt: Float, visibleNow: Boolean, live: String) {
        timer -= dt
        if (visibleNow != visible) {
            visible = visibleNow
            text = live
            timer = interval
        } else if (timer <= 0f) {
            text = live
            timer = interval
        }
    }

    companion object {
        /** `Hud.TextUpdateInterval`, s. */
        const val INTERVAL = 0.1f
    }
}
