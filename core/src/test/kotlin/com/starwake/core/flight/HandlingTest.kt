package com.starwake.core.flight

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `ShipHandling` (02-flight.md 3.2) against the derived table of 4.13 and the agility table of 3.2. */
class HandlingTest {
    /** One row of 4.13: ship, tank, mass, ratio, top, boost, accel F/R/L/V, turn P/Y/R (°/s at agility 1), turn accel P/Y/R. */
    private class Row(val id: String, val fuel: Double, val mass: Double, val ratio: Double, val v: DoubleArray)

    private fun row(id: String, fuel: Double, mass: Double, ratio: Double, vararg v: Double) = Row(id, fuel, mass, ratio, v)

    private val table = listOf(
        row("varrow", 1.0, 370.0, 1.0, 260.0, 340.0, 35.0, 25.0, 20.0, 22.0, 38.0, 20.0, 100.0, 120.0, 60.0, 170.0),
        row("varrow", 0.0, 338.0, 0.91351, 263.55, 344.64, 37.46, 26.76, 21.40, 23.54, 38.69, 20.37, 101.83, 128.42, 64.21, 181.93),
        row("brannock", 1.0, 760.0, 1.0, 205.0, 285.0, 24.0, 17.0, 12.0, 14.0, 24.0, 13.0, 60.0, 70.0, 36.0, 100.0),
        row("brannock", 0.0, 696.0, 0.91579, 207.72, 288.79, 25.64, 18.16, 12.82, 14.96, 24.43, 13.23, 61.07, 74.77, 38.46, 106.82),
        row("morven", 1.0, 1638.0, 0.99878, 170.03, 230.04, 16.02, 11.01, 7.01, 9.01, 15.00, 9.00, 38.01, 42.04, 22.02, 64.06),
        row("morven", 0.0, 1510.0, 0.92073, 172.12, 232.87, 17.02, 11.70, 7.45, 9.58, 15.25, 9.15, 38.63, 44.68, 23.41, 68.09),
        row("kessock", 1.0, 930.0, 1.0, 190.0, 255.0, 20.0, 15.0, 13.0, 16.0, 20.0, 12.0, 50.0, 56.0, 30.0, 84.0),
        row("kessock", 0.0, 850.0, 0.91398, 192.58, 258.46, 21.40, 16.05, 13.91, 17.12, 20.36, 12.22, 50.91, 59.91, 32.09, 89.86),
        row("lochran", 1.0, 356.0, 1.0, 280.0, 365.0, 33.0, 23.0, 18.0, 21.0, 34.0, 18.0, 90.0, 104.0, 52.0, 150.0),
        row("lochran", 0.0, 308.0, 0.86517, 286.15, 373.02, 36.79, 25.64, 20.07, 23.41, 35.00, 18.53, 92.65, 115.93, 57.97, 167.21),
        row("calder", 1.0, 363.0, 1.0, 270.0, 352.0, 34.0, 24.0, 19.0, 22.0, 36.0, 19.0, 95.0, 112.0, 56.0, 160.0),
        row("calder", 0.0, 323.0, 0.88981, 274.77, 358.22, 37.11, 26.20, 20.74, 24.01, 36.85, 19.45, 97.24, 122.25, 61.13, 174.64),
        row("torrin", 1.0, 275.0, 1.0, 300.0, 400.0, 38.0, 27.0, 21.0, 24.0, 40.0, 21.0, 110.0, 128.0, 63.0, 192.0),
        row("torrin", 0.0, 247.0, 0.89818, 304.87, 406.50, 41.19, 29.26, 22.76, 26.01, 40.87, 21.46, 112.39, 138.74, 68.28, 208.10),
        row("dornoch", 1.0, 1086.0, 1.0, 225.0, 295.0, 22.0, 16.0, 12.0, 14.0, 22.0, 13.0, 52.0, 62.0, 33.0, 88.0),
        row("dornoch", 0.0, 990.0, 0.91160, 228.15, 299.12, 23.58, 17.15, 12.86, 15.01, 22.41, 13.24, 52.97, 66.46, 35.37, 94.33),
    )

    private fun near(what: String, expected: Double, actual: Double, eps: Double) {
        if (abs(expected - actual) > eps) fail("$what: expected $expected ± $eps, was $actual")
    }

    @Test
    fun everyShipMatchesTheDerivedTable() {
        assertEquals(8, ShipProfiles.all.size)
        for (r in table) {
            val h = ShipHandling(ShipProfiles.get(r.id))
            h.update(0.0, 0.0, r.fuel, 0.0)
            // the turn rates of the table are at agility 1: half TopSpeed
            h.update(0.0, h.topSpeed * 0.5, r.fuel, 0.0)
            val tag = "${r.id} tank ${r.fuel}"
            near("$tag mass", r.mass, h.mass, 1e-9)
            near("$tag ratio", r.ratio, h.massRatio, 1e-5)
            near("$tag agility", 1.0, h.agility, 1e-12)
            val got = doubleArrayOf(
                h.topSpeed, h.boostSpeed, h.accelForward, h.accelReverse, h.accelLateral, h.accelVertical,
                deg(h.turnRatePitch), deg(h.turnRateYaw), deg(h.turnRateRoll),
                deg(h.turnAccelPitch), deg(h.turnAccelYaw), deg(h.turnAccelRoll),
            )
            val names = arrayOf("top", "boost", "accel F", "accel R", "accel L", "accel V", "turn P", "turn Y", "turn R", "tacc P", "tacc Y", "tacc R")
            for (k in got.indices) near("$tag ${names[k]}", r.v[k], got[k], 0.0075)
            assertFalse(h.overloaded, tag)
        }
    }

    private fun deg(rad: Float) = rad.toDouble() * 180.0 / Math.PI

    @Test
    fun theMorvenIsTwoTonnesBelowItsDesignMass() {
        val p = ShipProfiles.MORVEN
        assertEquals(1638.0, p.fullMass)
        assertEquals(1640.0, p.designMass)
    }

    @Test
    fun agilityIsTheBlueZoneThenTheCruiseFloor() {
        val cases = doubleArrayOf(0.0, 0.65, 0.25, 0.825, 0.5, 1.0, 0.75, 0.85, 1.0, 0.70, 1.5, 0.549, 2.0, 0.462, 2.343, 0.42, 10.0, 0.42)
        var i = 0
        while (i < cases.size) {
            near("agility(${cases[i]})", cases[i + 1], ShipHandling.agilityAt(cases[i]), 6e-4)
            i += 2
        }
        // the agility is applied by speed: standing 0.65, half TopSpeed 1.0, TopSpeed 0.7
        val h = ShipHandling()
        h.update(0.0, 0.0, 1.0, 0.0)
        near("standing", 0.65, h.agility, 1e-12)
        h.update(0.0, 260.0, 1.0, 0.0)
        near("at TopSpeed", 0.7, h.agility, 1e-12)
    }

    @Test
    fun turnRatesFollowTheAgilityButTurnAccelerationsDoNot() {
        val h = ShipHandling()
        h.update(0.0, 130.0, 1.0, 0.0)
        val rate = h.turnRatePitch
        val acc = h.turnAccelPitch
        h.update(0.0, 0.0, 1.0, 0.0)
        near("pitch rate standing = 0.65 x", rate * 0.65, h.turnRatePitch.toDouble(), 1e-6)
        assertEquals(acc, h.turnAccelPitch, "turn acceleration has no agility factor")
        h.update(0.0, 5000.0, 1.0, 0.0)
        assertEquals(acc, h.turnAccelPitch)
        near("cruise pitch rate = 0.42 x", rate * 0.42, h.turnRatePitch.toDouble(), 1e-6)
    }

    @Test
    fun theVarrowOverloadsAbove480Tonnes() {
        val h = ShipHandling()
        // a full tank weighs 370 t: 110 t of cargo is the limit, anything above overloads
        h.update(0.0, 0.0, 1.0, 110.0)
        assertFalse(h.overloaded, "480 t is not overloaded")
        h.update(0.0, 0.0, 1.0, 110.5)
        assertTrue(h.overloaded, "480.5 t is overloaded")
        // and the mass slows it: (mass / design)^-0.15 on the speeds
        near("top at 480.5 t", 260.0 * Math.pow(480.5 / 370.0, -0.15), h.topSpeed, 1e-9)
    }

    @Test
    fun theMassRatioHasAFloor() {
        val h = ShipHandling()
        // an absurdly light ship (a negative cargo stands in for it) keeps the 0.5 floor
        h.update(0.0, 0.0, 0.0, -200.0)
        assertEquals(0.5, h.massRatio)
        near("top at the floor", 260.0 * Math.pow(0.5, -0.15), h.topSpeed, 1e-9)
    }

    @Test
    fun unknownShipsFlyAsTheVarrow() {
        assertEquals(ShipProfiles.VARROW, ShipProfiles.get("nothing"))
        assertEquals(ShipProfiles.LOCHRAN, ShipProfiles.get("LOCHRAN"))
        // perks: the LOCHRAN's frugal drive and big scoops, the KESSOCK's mining
        assertEquals(0.6, ShipProfiles.LOCHRAN.fuelBurn)
        assertEquals(1.5, ShipProfiles.LOCHRAN.scoopRate)
        assertEquals(2.0, ShipProfiles.KESSOCK.mineSpeed)
        assertEquals(1.0, ShipProfiles.VARROW.fuelBurn)
    }
}
