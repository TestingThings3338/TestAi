package com.starwake.core.flight

import com.starwake.core.math.DEG2RAD
import kotlin.math.max
import kotlin.math.pow

/**
 * Flight properties from the ship profile and the current mass and speed (Roblox `ShipHandling`,
 * `ShipClient/ShipHandling.lua`, 02-flight.md 3.2). Updated every frame before rotation and thrust
 * with the speed of the previous frame; rebuilt ([profile]) on a ship switch.
 *
 * Every number of a profile holds at its design mass; fuel and cargo make the ship heavier, so it
 * accelerates and turns more slowly: `factor(e) = ratio^-e` with `ratio = max(mass / design, 0.5)`.
 * Agility (an inverted V over the speed) scales the turn rates, not the turn accelerations.
 * No allocation.
 */
class ShipHandling(profile: ShipProfile = ShipProfiles.VARROW) {
    var profile: ShipProfile = profile
        set(p) {
            field = p
            update(0.0, 0.0, 1.0, 0.0)
        }

    var mass = profile.designMass
        private set
    var massRatio = 1.0
        private set
    var overloaded = false
        private set
    var topSpeed = 0.0
        private set
    var boostSpeed = 0.0
        private set
    var reverseSpeed = 0.0
        private set
    var strafeSpeed = 0.0
        private set
    var accelForward = 0.0
        private set
    var accelReverse = 0.0
        private set
    var accelLateral = 0.0
        private set
    var accelVertical = 0.0
        private set

    /** Turn rates, rad/s (pitch, yaw, roll), agility included. */
    var turnRatePitch = 0f
        private set
    var turnRateYaw = 0f
        private set
    var turnRateRoll = 0f
        private set

    /** Turn accelerations, rad/s² (no agility factor). */
    var turnAccelPitch = 0f
        private set
    var turnAccelYaw = 0f
        private set
    var turnAccelRoll = 0f
        private set
    var agility = 1.0
        private set

    init {
        update(0.0, 0.0, 1.0, 0.0)
    }

    /**
     * `update(dt, speed, fuelFraction, cargoMass)`: [speed] m/s (the previous frame's), [fuelFraction]
     * 0..1, [cargoMass] t.
     */
    fun update(dt: Double, speed: Double, fuelFraction: Double, cargoMass: Double) {
        val p = profile
        val m = p.hullMass + p.moduleMass + p.fuelMass * fuelFraction.coerceIn(0.0, 1.0) + cargoMass
        mass = m
        overloaded = m > p.maxMass
        val ratio = max(m / p.designMass, FlightConfig.Handling.MIN_MASS_RATIO)
        massRatio = ratio
        val speedMul = massFactor(ratio, FlightConfig.Handling.MASS_EXPONENT_TOP_SPEED)
        topSpeed = p.topSpeed * speedMul
        boostSpeed = p.boostSpeed * speedMul
        reverseSpeed = p.reverseSpeed * speedMul
        strafeSpeed = p.strafeSpeed * speedMul
        val accelMul = massFactor(ratio, FlightConfig.Handling.MASS_EXPONENT_ACCELERATION)
        accelForward = p.accelForward * accelMul
        accelReverse = p.accelReverse * accelMul
        accelLateral = p.accelLateral * accelMul
        accelVertical = p.accelVertical * accelMul
        agility = agilityAt(speed / max(topSpeed, 1.0))
        val rateMul = (massFactor(ratio, FlightConfig.Handling.MASS_EXPONENT_TURN_RATE) * agility).toFloat() * DEG2RAD
        turnRatePitch = p.turnRatePitch.toFloat() * rateMul
        turnRateYaw = p.turnRateYaw.toFloat() * rateMul
        turnRateRoll = p.turnRateRoll.toFloat() * rateMul
        val turnMul = massFactor(ratio, FlightConfig.Handling.MASS_EXPONENT_TURN_ACCELERATION).toFloat() * DEG2RAD
        turnAccelPitch = p.turnAccelPitch.toFloat() * turnMul
        turnAccelYaw = p.turnAccelYaw.toFloat() * turnMul
        turnAccelRoll = p.turnAccelRoll.toFloat() * turnMul
    }

    companion object {
        /** `massFactor(ratio, e) = ratio^-e`. */
        fun massFactor(ratio: Double, exponent: Double): Double = ratio.pow(-exponent)

        /**
         * `blueZone` (`:35-43`) over [fraction] = speed / TopSpeed: 0.65 standing, 1.0 at half
         * TopSpeed, 0.7 at TopSpeed.
         */
        fun blueZone(fraction: Double): Double {
            val c = FlightConfig.Handling.BLUE_ZONE_CENTER
            if (fraction <= c) {
                val low = FlightConfig.Handling.BLUE_ZONE_LOW
                return low + (1.0 - low) * (fraction / c)
            }
            val t = ((fraction - c) / (1.0 - c)).coerceIn(0.0, 1.0)
            return 1.0 - (1.0 - FlightConfig.Handling.BLUE_ZONE_HIGH) * t
        }

        /**
         * Agility at [fraction] = speed / TopSpeed: the blue zone up to TopSpeed, then
         * `0.7 · max(0.6, fraction^-0.6)` in cruise (0.42 from 2.343 × TopSpeed).
         */
        fun agilityAt(fraction: Double): Double =
            if (fraction <= 1.0) {
                blueZone(fraction)
            } else {
                FlightConfig.Handling.BLUE_ZONE_HIGH *
                    max(FlightConfig.Handling.CRUISE_TURN_FLOOR, fraction.pow(-FlightConfig.Handling.CRUISE_TURN_EXPONENT))
            }
    }
}
