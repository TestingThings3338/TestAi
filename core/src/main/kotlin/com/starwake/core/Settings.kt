package com.starwake.core

/** Player settings, persisted as a compact "key=value;" string through [Platform]. */
class Settings {
    /** 0 low, 1 medium, 2 high. */
    var quality = 2

    /** 0 = display maximum (e.g. 120 Hz), 1 = 60 FPS, 2 = 30 FPS. */
    var fpsMode = 0

    /** Right stick horizontal axis: true = roll (flight-sim style), false = yaw. */
    var stickXRoll = true

    /** true: pushing the stick up raises the nose. false: flight-sim style (up = dive). */
    var pitchUpClimb = true
    var sensitivity = 1f
    var leftHanded = false
    var haptics = true
    var showFps = false
    var livery = 0

    val renderScale: Float
        get() = when (quality) {
            0 -> 0.7f
            1 -> 0.85f
            else -> 1f
        }

    val fpsLimit: Int
        get() = when (fpsMode) {
            1 -> 60
            2 -> 30
            else -> 0
        }

    fun serialize(): String =
        "q=$quality;fps=$fpsMode;roll=${b(stickXRoll)};climb=${b(pitchUpClimb)};sens=$sensitivity;" +
            "left=${b(leftHanded)};hap=${b(haptics)};showfps=${b(showFps)};livery=$livery"

    fun deserialize(data: String?) {
        if (data.isNullOrEmpty()) return
        for (part in data.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq)
            val v = part.substring(eq + 1)
            try {
                when (k) {
                    "q" -> quality = v.toInt().coerceIn(0, 2)
                    "fps" -> fpsMode = v.toInt().coerceIn(0, 2)
                    "roll" -> stickXRoll = v == "1"
                    "climb" -> pitchUpClimb = v == "1"
                    "sens" -> v.toFloat().let { if (!it.isNaN()) sensitivity = it.coerceIn(0.5f, 1.5f) }
                    "left" -> leftHanded = v == "1"
                    "hap" -> haptics = v == "1"
                    "showfps" -> showFps = v == "1"
                    "livery" -> livery = v.toInt().coerceIn(0, 31)
                }
            } catch (_: NumberFormatException) {
                // ignore corrupt values, keep defaults
            }
        }
    }

    private fun b(v: Boolean) = if (v) "1" else "0"
}
