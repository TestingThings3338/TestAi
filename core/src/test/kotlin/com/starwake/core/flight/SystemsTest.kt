package com.starwake.core.flight

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `ShipSystems` (02-flight.md 3.6): heat, fuel, G-load and the blackout. */
class SystemsTest {
    private val dt = 1.0 / 60.0

    private fun near(what: String, expected: Double, actual: Double, eps: Double) {
        if (abs(expected - actual) > eps) fail("$what: expected $expected ± $eps, was $actual")
    }

    /** Seconds until [cond], scooping at [starHeat] and full throttle with the tank kept below full. */
    private fun timeUntil(starHeat: Double, maxT: Double, cond: (ShipSystems) -> Boolean): Double {
        val s = ShipSystems()
        var t = 0.0
        while (t < maxT) {
            s.setFuelFraction(0.5)
            s.update(dt, starHeat, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0)
            t += dt
            if (cond(s)) return t
        }
        return Double.NaN
    }

    @Test
    fun scoopingAtTheStarSurfaceWarnsAfter7SecondsAndKillsAfter25() {
        // dh/dt = 0.178 - 0.075 h: 100 % after 7.3 s, fatal 200 % after 24.7 s
        near("to 100 %", 7.3, timeUntil(1.0, 60.0) { it.heat >= 1.0 }, 0.1)
        near("to 200 %", 24.7, timeUntil(1.0, 60.0) { it.heat >= FlightConfig.Systems.FATAL_HEAT }, 0.1)
    }

    @Test
    fun atHalfTheStarsHeatTheEquilibriumIsAWarningNotDeath() {
        val s = ShipSystems()
        repeat(60 * 200) {
            s.setFuelFraction(0.5)
            s.update(dt, 0.5, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0)
        }
        near("equilibrium", 1.1067, s.heat, 1e-3)
        assertTrue(s.scooping)
    }

    @Test
    fun thrustAloneNeverHeats() {
        val s = ShipSystems()
        repeat(600) { s.update(dt, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 1.0) }
        assertEquals(0.0, s.heat, "equilibrium -0.16 is clamped at 0")
        s.addHeat(0.5)
        repeat(60 * 60) { s.update(dt, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0) }
        assertTrue(s.heat < 0.01, "cools down: ${s.heat}")
        s.addHeat(5.0)
        assertEquals(2.2, s.heat, "clamped at HeatMax")
    }

    @Test
    fun aFullTankLasts455SecondsAtFullThrottle() {
        fun endurance(fuelBurn: Double): Double {
            val s = ShipSystems()
            var t = 0.0
            while (s.fuel > 0.0) {
                s.update(dt, 0.0, 1.0, 0.0, 0.0, 0.0, fuelBurn, 1.0)
                t += dt
            }
            return t
        }
        near("VARROW", 454.5, endurance(1.0), 0.1)
        near("LOCHRAN (FuelBurn 0.6)", 757.6, endurance(ShipProfiles.LOCHRAN.fuelBurn), 0.1)
        near("TORRIN (FuelBurn 0.85)", 534.8, endurance(ShipProfiles.TORRIN.fuelBurn), 0.1)
        // a reverse lever burns as much as a forward one
        val s = ShipSystems()
        s.update(1.0, 0.0, -0.25, 0.0, 0.0, 0.0, 1.0, 1.0)
        near("reverse burn", 100.0 - 0.22 * 0.25, s.fuel, 1e-9)
    }

    @Test
    fun scoopingRefillsAnEmptyTankIn29Seconds() {
        fun refill(scoopRate: Double): Double {
            val s = ShipSystems()
            s.setFuelFraction(0.0)
            var t = 0.0
            while (s.fuel < FlightConfig.Systems.FUEL_CAPACITY) {
                s.update(dt, 0.5, 0.0, 0.0, 0.0, 0.0, 1.0, scoopRate)
                t += dt
            }
            assertFalse(s.scooping && s.fuel >= 100.0 && t < 1.0)
            return t
        }
        near("VARROW", 28.6, refill(1.0), 0.05)
        near("LOCHRAN (ScoopRate 1.5)", 19.05, refill(ShipProfiles.LOCHRAN.scoopRate), 0.05)
        // no scooping at a quarter of the star's heat or less, nor with a full tank
        val s = ShipSystems()
        s.setFuelFraction(0.5)
        s.update(dt, 0.25, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0)
        assertFalse(s.scooping)
        val full = ShipSystems()
        full.update(dt, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 1.0)
        assertFalse(full.scooping)
    }

    /** The settled strain and redout for a constant felt acceleration in ship axes (m/s²). */
    private fun settle(ax: Double, ay: Double, az: Double): ShipSystems {
        val s = ShipSystems()
        repeat(600) { s.update(dt, 0.0, 0.0, ax, ay, az, 1.0, 1.0) }
        return s
    }

    @Test
    fun gLoadIsWeightedByDirection() {
        val g = FlightConfig.Handling.FELT_PER_G
        // +Gz 5 g (pulling up): strain 5, no redout
        val up = settle(0.0, 5.0 * g, 0.0)
        near("+Gz strain", 5.0, up.strainG, 1e-6)
        near("+Gz redout", 0.0, up.redout, 1e-6)
        near("+Gz g", 5.0, up.gForce, 1e-6)
        // -Gz 5 g (pushing): strain 7.5, all redout
        val down = settle(0.0, -5.0 * g, 0.0)
        near("-Gz strain", 7.5, down.strainG, 1e-6)
        near("-Gz redout", 1.0, down.redout, 1e-6)
        // 7.85 g forward (boost: 2.2 x 35 m/s²): into the seat, 0.35 -> 2.75
        val fwd = settle(0.0, 0.0, -7.85 * g)
        near("+Gx strain", 2.7475, fwd.strainG, 1e-6)
        // braking into the belts: 0.5; sideways 0.5
        near("-Gx strain", 2.0, settle(0.0, 0.0, 4.0 * g).strainG, 1e-6)
        near("Gy strain", 2.0, settle(4.0 * g, 0.0, 0.0).strainG, 1e-6)
    }

    @Test
    fun theBlackoutStartsAt6_5GAndPeaksAt82Percent() {
        val g = FlightConfig.Handling.FELT_PER_G
        assertEquals(0.0, settle(0.0, 6.0 * g, 0.0).darkness, "below 6.5 g strain")
        near("half way", (8.75 - 6.5) / 4.5 * 0.82, settle(0.0, 8.75 * g, 0.0).darkness, 1e-4)
        near("full", 0.82, settle(0.0, 20.0 * g, 0.0).darkness, 1e-4)
        // the response is smoothed (0.35 s): one frame of 20 g barely darkens
        val s = ShipSystems()
        s.update(dt, 0.0, 0.0, 0.0, 20.0 * g, 0.0, 1.0, 1.0)
        assertTrue(s.darkness < 0.01)
    }

    @Test
    fun resetIsAFullColdTank() {
        val s = ShipSystems()
        s.setFuelFraction(0.1)
        s.addHeat(1.5)
        s.reset()
        assertEquals(1.0, s.fuelFraction)
        assertEquals(0.0, s.heat)
    }
}
