package com.starwake.core.flight

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Why the speed limit is what it is (Roblox `gravityState.limitZone`, `ShipGravity.lua:189-209`):
 * the constraint that won the minimum over bodies, stations and the field edge.
 */
enum class LimitZone {
    /** At the cruise cap: nothing limits. */
    OPEN,

    /** Outside a mass shadow: the gap law (`cruiseLimit`); APPROACH / DEPARTURE / OPEN SPACE by the heading. */
    CRUISE,

    /** Inside a body's mass shadow: GRAVITY WELL. */
    SHADOW,

    /** Inside a star's heat zone: HEAT ZONE. */
    HEAT,

    /** The approach zone (`approachReach`, tracked at once): LOW ALTITUDE. */
    SURFACE,

    /** A station's mass lock: STATION ZONE. */
    STATION,

    /** The field edge: SYSTEM EDGE. */
    EDGE,

    /** The near-ground band (Roblox's landing-scene law, DECISIONS 3): SURFACE. */
    LANDING,
}

/**
 * The pure functions of the speed law (`ShipGravity.lua:142-239`, 02-flight.md 3.5 and 9.5): every
 * speed in m/s, every length in m. The far law's scaled numbers come from a [FlightLaw]; the near
 * band and the stations are absolute. No allocation.
 */
object SpeedLaw {
    /**
     * `cruiseLimit`: flying at this limit towards the edge of a shadow (or a station lock, or the
     * field edge) [gap] metres ahead, the gap shrinks by 1/e every [time] seconds: long trips take
     * hardly longer than short ones and the ship arrives at [base]. Capped at [cap].
     */
    fun cruiseLimit(base: Double, gap: Double, time: Double, cap: Double): Double = min(base + max(gap, 0.0) / time, cap)

    /**
     * `cruiseTime`: the gap's e-folding time by heading: [cos] 1 (straight at it) 3.5 s, 0 (level)
     * 2.5 s, -1 (straight away) 1.5 s.
     */
    fun cruiseTime(cos: Double): Double {
        val f = (0.5 + 0.5 * cos).coerceIn(0.0, 1.0)
        val depart = FlightConfig.Thrust.CRUISE_DEPART_TIME
        return depart + (FlightConfig.Thrust.CRUISE_DISTANCE_TIME - depart) * f
    }

    /**
     * `approachReach`: the highest speed towards a surface [h] metres below that the approach brake
     * [brake] (m/s²) can still stop: `h / 2` up to the knee `brake · 4`, `sqrt(2 · brake · (h − knee / 2))`
     * above it (C1-continuous: at the knee both give `2 · brake` with slope 0.5).
     */
    fun approachReach(h: Double, brake: Double): Double {
        val t = FlightConfig.Gravity.APPROACH_TIME
        val knee = brake * t * t
        val a = max(h, 0.0)
        return if (a <= knee) a / t else sqrt(2.0 * brake * (a - knee / 2.0))
    }

    /**
     * `edgeLimit` (`:217-239`): the limit in front of the field edge, a sphere of [fieldRadius]
     * round the star; [r] is the ship's distance from the star and [cos] the cosine between its
     * velocity and the outward direction (+1 flying outward; 1 without a direction). `EdgeSpeed`
     * applies `EdgeMargin` before the edge on the way out, half of it level, none flying inward.
     * Returns +∞ without a field.
     */
    fun edgeLimit(fieldRadius: Double, r: Double, cos: Double, cap: Double): Double {
        if (fieldRadius <= 0.0) return Double.POSITIVE_INFINITY
        val gap = fieldRadius - r
        val margin = FlightConfig.Ship.EDGE_MARGIN * (0.5 + 0.5 * cos).coerceIn(0.0, 1.0)
        return cruiseLimit(FlightConfig.Ship.EDGE_SPEED, gap - margin, cruiseTime(cos), cap)
    }

    /** `zoneLabel` (`:189-209`): the caption the HUD shows for a limit zone. */
    fun zoneLabel(zone: LimitZone, cos: Double): String = when (zone) {
        LimitZone.CRUISE -> when {
            cos >= 0.25 -> "APPROACH"
            cos <= -0.25 -> "DEPARTURE"
            else -> "OPEN SPACE"
        }
        LimitZone.OPEN -> "OPEN SPACE"
        LimitZone.SHADOW -> "GRAVITY WELL"
        LimitZone.HEAT -> "HEAT ZONE"
        LimitZone.SURFACE -> "LOW ALTITUDE"
        LimitZone.STATION -> "STATION ZONE"
        LimitZone.EDGE -> "SYSTEM EDGE"
        LimitZone.LANDING -> "SURFACE"
    }

    /** `ShipSurface.getSpeedLimit`: the landing scene's law `clamp(0.35 · h, 260, 4000)`. */
    fun surfaceLimit(h: Double): Double =
        (FlightConfig.Surface.SPEED_PER_ALTITUDE * max(h, 0.0)).coerceIn(FlightConfig.Surface.MAX_SPEED, FlightConfig.Surface.MAX_SPEED_HIGH)
}
