package com.starwake.core.world

import com.starwake.core.math.DVec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * `ShipClient/ShipOrbits.lua` as pure math (03-world.md 2.9): circles in the XZ plane, angle from +X
 * towards +Z, tilted about +X; moons around their planet's current position. Everything in double:
 * the angle is `phase + (clock mod period)/period` (2.9.1: a float clock would make Phobos jump
 * kilometres), and only the final sum becomes a position.
 */
object Orbits {
    private const val TAU = 2.0 * PI

    /**
     * The orbit angle in turns, reduced to [0, 1): `OrbitPhase + clock/OrbitPeriod` with the clock
     * taken modulo the period first.
     */
    fun turns(phase: Double, period: Double, clock: Double): Double {
        var c = clock % period
        if (c < 0.0) c += period
        var t = phase + c / period
        t -= kotlin.math.floor(t)
        return t
    }

    /**
     * `positionOnOrbit` (`:106`): the position at [clock] of a body on a circle of [radius] m with
     * [period] s, [phase] (0..1) and [tiltDeg] about +X, around [centre]; into [out] (may not alias
     * [centre]). The tilt turns the circle, it never changes the radius: y = −z·sin t, z = z·cos t.
     */
    fun position(radius: Double, period: Double, phase: Double, tiltDeg: Double, clock: Double, centre: DVec3, out: DVec3): DVec3 {
        val a = turns(phase, period, clock) * TAU
        val x = cos(a) * radius
        var z = sin(a) * radius
        val t = Math.toRadians(tiltDeg)
        val y = -z * sin(t)
        z *= cos(t)
        return out.set(centre.x + x, centre.y + y, centre.z + z)
    }

    /**
     * `restPosition` (`:53`) of one orbit level: (cos 2πφ, 0, sin 2πφ)·radius, added to [out]; no
     * tilt and y = 0, exactly where `build()` put the body. Walk it from the body up to the star.
     */
    fun addRest(radius: Double, phase: Double, out: DVec3): DVec3 {
        val a = phase * TAU
        out.x += cos(a) * radius
        out.z += sin(a) * radius
        return out
    }

    /** `OrbitPeriod` = 2π·orbit/max(speed, 0.01) (studs and studs per second: the period is scale free). */
    fun period(orbitStuds: Double, speedStudsPerSecond: Double): Double = TAU * orbitStuds / maxOf(speedStudsPerSecond, 0.01)
}
