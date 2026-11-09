package com.starwake.core

import com.starwake.core.content.ShipCatalog
import kotlin.math.floor

/** Player settings, persisted as a compact "key=value;" string through [Platform]. */
class Settings {
    /** 0 low, 1 medium, 2 high. */
    /** Graphics quality: 0 LOW, 1 MEDIUM, 2 HIGH, 3 ULTRA (more terrain detail, finer air, a sharper planet). */
    var quality = 2

    /**
     * Which graphics defaults this install has been offered (0: from before ULTRA existed): a
     * strong GPU on HIGH is moved to ULTRA once, after which the pilot's choice stands.
     */
    var graphicsRev = 0

    /** 0 = display maximum (e.g. 120 Hz), 1 = 60 FPS, 2 = 30 FPS. 60 by default (DECISIONS 21a). */
    var fpsMode = 1

    /** Right stick horizontal axis: true = roll (flight-sim style), false = yaw (Roblox MOUSE X AXIS). */
    var stickXRoll = true

    /** true: pushing the stick up raises the nose. false: flight-sim style (up = dive; Roblox INVERT PITCH). */
    var pitchUpClimb = true
    var sensitivity = 1f
    var leftHanded = false
    var haptics = true
    var showFps = false

    /** Ship to fly: index into ShipCatalog (0 = VARROW). */
    var ship = 0

    /** FIELD OF VIEW, vertical degrees, cockpit and external views (Roblox 60–100, default 80, step 1). */
    var fovDeg = FOV_DEFAULT
        set(v) {
            field = snap(v, FOV_MIN, FOV_MAX, 1f, FOV_DEFAULT)
        }

    /** CAMERA SHAKE, 0–1 (Roblox default 1, step 0.05): scales the camera shake of impacts and the cockpit motion. */
    var cameraShake = 1f
        set(v) {
            field = snap(v, 0f, 1f, 0.05f, 1f)
        }

    /** VIEW also cycles to the lagging chase view of the pre-port game (DECISIONS 21a: off by default). */
    var chaseView = false

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
            "left=${b(leftHanded)};hap=${b(haptics)};showfps=${b(showFps)};ship=$ship;" +
            "fov=${fmt(fovDeg)};shake=${fmt(cameraShake)};chase=${b(chaseView)};rev=$graphicsRev"

    fun deserialize(data: String?) {
        if (data.isNullOrEmpty()) return
        for (part in data.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val k = part.substring(0, eq)
            val v = part.substring(eq + 1)
            try {
                when (k) {
                    "q" -> quality = v.toInt().coerceIn(0, MAX_QUALITY)
                    "rev" -> graphicsRev = v.toInt().coerceAtLeast(0)
                    "fps" -> fpsMode = v.toInt().coerceIn(0, 2)
                    "roll" -> stickXRoll = v == "1"
                    "climb" -> pitchUpClimb = v == "1"
                    "sens" -> v.toFloat().let { if (!it.isNaN()) sensitivity = it.coerceIn(0.5f, 1.5f) }
                    "left" -> leftHanded = v == "1"
                    "hap" -> haptics = v == "1"
                    "showfps" -> showFps = v == "1"
                    "ship" -> ship = v.toInt().coerceIn(0, ShipCatalog.all.size - 1)
                    "fov" -> v.toFloat().let { if (!it.isNaN()) fovDeg = it }
                    "shake" -> v.toFloat().let { if (!it.isNaN()) cameraShake = it }
                    "chase" -> chaseView = v == "1"
                }
            } catch (_: NumberFormatException) {
                // ignore corrupt values, keep defaults
            }
        }
    }

    private fun b(v: Boolean) = if (v) "1" else "0"

    /** Whole numbers without ".0", others as Kotlin prints them ("80", "0.35"). */
    private fun fmt(v: Float): String = if (v == floor(v)) v.toInt().toString() else v.toString()

    companion object {
        /** The highest [quality] level (ULTRA). */
        const val MAX_QUALITY = 3

        /** The current [graphicsRev]. */
        const val GRAPHICS_REV = 1
        const val FOV_MIN = 60f
        const val FOV_MAX = 100f
        const val FOV_DEFAULT = 80f

        /**
         * A slider value as the Roblox menu stores it (ShipMenu.lua:164-174): clamped, snapped to
         * [step] from [min], clamped again and rounded to 4 decimals (no 0.30000000000000004);
         * NaN gives [fallback].
         */
        fun snap(v: Float, min: Float, max: Float, step: Float, fallback: Float): Float {
            if (v.isNaN()) return fallback
            var x = v.coerceIn(min, max).toDouble()
            x = min + com.starwake.core.math.roundHalfAway((x - min) / step) * step
            x = x.coerceIn(min.toDouble(), max.toDouble())
            return (com.starwake.core.math.roundHalfAway(x * 10_000.0) / 10_000.0).toFloat()
        }
    }
}
