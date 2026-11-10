package com.starwake.core.flight

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Fuel, heat and G-load (Roblox `ShipSystems`, `ShipClient/ShipSystems.lua:59-104`, 02-flight.md
 * 3.6) and the blackout overlay (`ShipHud.lua:355-370`).
 *
 * Heat runs 0..2 (2.2 max): warnings from 1.0, the ship burns up at 2.0, with time to react between.
 * Fuel never blocks anything: it burns with the lever and refills by scooping near a star (which
 * costs extra heat), and its mass changes the handling. The G-load weighs the felt acceleration by
 * direction: pulling up greys the screen out, pushing down tints it red. No allocation.
 */
class ShipSystems {
    /** Fuel units, 0..[FlightConfig.Systems.FUEL_CAPACITY]. */
    var fuel = FlightConfig.Systems.FUEL_CAPACITY
        private set

    /** Ship heat, 0..2.2 (1.0 = 100 %). */
    var heat = 0.0
        private set

    /** Felt G (smoothed, 0.12 s). */
    var gForce = 0.0
        private set

    /** Direction-weighted strain in g (smoothed, 0.12 s). */
    var strainG = 0.0
        private set

    /** Share of the strain from pushing (-Gz), 0..1 (smoothed, 0.25 s). */
    var redout = 0.0
        private set

    var scooping = false
        private set

    /** The blackout overlay's opacity (smoothed, 0.35 s; max 0.82). */
    var darkness = 0.0
        private set

    /** Full tank, cold, no load (crash respawn). */
    fun reset() {
        fuel = FlightConfig.Systems.FUEL_CAPACITY
        heat = 0.0
        gForce = 0.0
        strainG = 0.0
        redout = 0.0
        darkness = 0.0
        scooping = false
    }

    val fuelFraction: Double get() = fuel / FlightConfig.Systems.FUEL_CAPACITY

    /** `setFuelFraction`, 0..1 (the save at the start, refuelling at a station). */
    fun setFuelFraction(f: Double) {
        fuel = f.coerceIn(0.0, 1.0) * FlightConfig.Systems.FUEL_CAPACITY
    }

    /** `addHeat` (boost, atmosphere entry, ring debris), clamped 0..2.2. */
    fun addHeat(a: Double) {
        heat = (heat + a).coerceIn(0.0, FlightConfig.Systems.HEAT_MAX)
    }

    /** Debug "heat v": sets the heat, clamped. */
    fun setHeat(v: Double) {
        heat = v.coerceIn(0.0, FlightConfig.Systems.HEAT_MAX)
    }

    /**
     * One frame: [starHeat] 0..1 (the star's heat zone), the lever [throttle], the felt acceleration
     * in ship axes ([ax] side, [ay] up, [az] −forward; m/s²), the ship's perks [fuelBurn] and
     * [scoopRate].
     */
    fun update(dt: Double, starHeat: Double, throttle: Double, ax: Double, ay: Double, az: Double, fuelBurn: Double, scoopRate: Double) {
        val c = FlightConfig.Systems
        var gain = starHeat * c.HEAT_FROM_STAR + abs(throttle) * c.HEAT_FROM_THRUST
        scooping = starHeat > c.SCOOP_THRESHOLD && fuel < c.FUEL_CAPACITY
        if (scooping) {
            fuel = minOf(c.FUEL_CAPACITY, fuel + c.FUEL_SCOOP_RATE * scoopRate * dt)
            gain += c.FUEL_SCOOP_HEAT * starHeat
        }
        fuel = maxOf(0.0, fuel - abs(throttle) * c.FUEL_PER_SECOND_AT_FULL_THROTTLE * fuelBurn * dt)
        // base cooling always works, else the ship never cools down completely
        val loss = c.HEAT_COOLING * (c.COOLING_OFFSET + heat)
        heat = (heat + (gain - loss) * dt).coerceIn(0.0, c.HEAT_MAX)

        // ---------- G-load: felt g weighted by direction (Gx along, Gy across, Gz head-foot)
        val p = FlightConfig.Physics
        val perG = FlightConfig.Handling.FELT_PER_G
        val gx0 = ax / perG
        val gy0 = ay / perG
        val gz0 = az / perG
        val forward = -gz0
        val gx = forward * (if (forward >= 0.0) p.G_TOLERANCE_FORWARD else p.G_TOLERANCE_BACKWARD)
        val gy = gx0 * p.G_TOLERANCE_LATERAL
        val gz = gy0 * (if (gy0 >= 0.0) p.G_TOLERANCE_UP else p.G_TOLERANCE_DOWN)
        val strain = sqrt(gx * gx + gy * gy + gz * gz)
        val redoutRaw = if (gy0 < 0.0 && strain > 1e-3) (gz * gz) / (strain * strain) else 0.0
        gForce = smooth(gForce, sqrt(gx0 * gx0 + gy0 * gy0 + gz0 * gz0), c.G_SMOOTHING, dt)
        strainG = smooth(strainG, strain, c.G_SMOOTHING, dt)
        redout = smooth(redout, redoutRaw, c.REDOUT_SMOOTHING, dt)

        // ---------- the blackout overlay (ShipHud)
        val target = if (strainG > p.BLACKOUT_START_G) {
            ((strainG - p.BLACKOUT_START_G) / (p.BLACKOUT_FULL_G - p.BLACKOUT_START_G)).coerceIn(0.0, 1.0) * p.BLACKOUT_MAX_DARKNESS
        } else {
            0.0
        }
        darkness = smooth(darkness, target, p.BLACKOUT_RESPONSE, dt)
    }

    private fun smooth(c: Double, t: Double, tau: Double, dt: Double): Double = if (tau <= 0.0) t else c + (t - c) * (1.0 - exp(-dt / tau))
}
