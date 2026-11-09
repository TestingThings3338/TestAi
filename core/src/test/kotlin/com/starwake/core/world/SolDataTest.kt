package com.starwake.core.world

import com.starwake.core.game.StarSystem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sol against the Lua (`ShipSolData.lua`, 03-world.md 2.1 and 9.4 SolDataTest): the definition in
 * studs verbatim, and the built system at S = 3000 (radii, orbits, periods, barriers, shadows,
 * rings, classes, flags, seeds).
 */
class SolDataTest {
    private val S = WorldScale.S
    private val sol = SolData.SOL
    private val system = WorldBuilder.sol()

    /** name, parent, class, radius, orbit, speed, gravityG, colour, period (s) from the 2.1 tables. */
    private class Row(
        val name: String, val parent: String?, val cls: String, val radius: Double, val orbit: Double,
        val speed: Double, val g: Double, val colour: Int, val period: Double, val collision: Double,
    )

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private val rows = listOf(
        Row("Mercury", null, "Rocky Body", 810.0, 460000.0, 5.00, 0.3770, rgb(140, 130, 122), 578_053.0, 902.0),
        Row("Venus", null, "Greenhouse World", 2020.0, 677710.0, 4.12, 0.9040, rgb(216, 190, 140), 1_033_538.0, 2239.0),
        Row("Earth", null, "Terrestrial World", 2120.0, 828660.0, 3.72, 1.0, rgb(52, 96, 152), 1_399_630.0, 2349.0),
        Row("Luna", "Earth", "Moon", 580.0, 128000.0, 1.60, 0.1650, rgb(158, 154, 148), 502_655.0, 647.0),
        Row("Mars", null, "Rocky Body", 1130.0, 1076040.0, 3.27, 0.3780, rgb(168, 96, 62), 2_067_571.0, 1255.0),
        Row("Phobos", "Mars", "Moon", 150.0, 3600.0, 1.10, 0.0006, rgb(120, 110, 100), 20_563.0, 172.0),
        Row("Deimos", "Mars", "Moon", 150.0, 7800.0, 0.90, 0.0003, rgb(126, 116, 106), 54_454.0, 172.0),
        Row("Jupiter", null, "Gas Giant", 9320.0, 2303850.0, 2.23, 2.5280, rgb(206, 172, 132), 6_491_263.0, 10305.0),
        Row("Io", "Jupiter", "Moon", 610.0, 55000.0, 2.40, 0.1830, rgb(216, 196, 116), 143_990.0, 681.0),
        Row("Europa", "Jupiter", "Moon", 520.0, 87600.0, 2.10, 0.1340, rgb(212, 206, 190), 262_099.0, 581.0),
        Row("Ganymede", "Jupiter", "Moon", 880.0, 140000.0, 1.90, 0.1460, rgb(150, 140, 130), 462_972.0, 979.0),
        Row("Callisto", "Jupiter", "Moon", 800.0, 245000.0, 1.60, 0.1260, rgb(110, 104, 98), 962_113.0, 890.0),
        Row("Saturn", null, "Gas Giant", 7764.0, 3354380.0, 1.85, 1.0650, rgb(216, 194, 148), 11_392_536.0, 8586.0),
        Row("Titan", "Saturn", "Moon", 860.0, 157600.0, 1.70, 0.1380, rgb(196, 150, 96), 582_488.0, 957.0),
        Row("Uranus", null, "Ice Giant", 3380.0, 5174850.0, 1.49, 0.8860, rgb(150, 214, 220), 21_821_840.0, 3741.0),
        Row("Neptune", null, "Ice Giant", 3284.0, 6836280.0, 1.30, 1.1370, rgb(62, 96, 200), 33_041_242.0, 3635.0),
        Row("Triton", "Neptune", "Moon", 450.0, 47300.0, 1.40, 0.0790, rgb(200, 196, 186), 212_282.0, 504.0),
    )

    @Test
    fun theStarIsVerbatim() {
        val st = sol.star
        assertEquals("Sol", st.name)
        assertEquals(4800.0, st.radius)
        assertEquals(4000.0, st.skyRadius)
        assertEquals(37000.0, st.heatRadius)
        assertEquals(92000.0, st.massShadowRadius)
        assertEquals(1.35, st.coronaScale)
        assertEquals(rgb(255, 246, 224), st.colour)
        assertEquals(rgb(255, 190, 110), st.corona)
        assertEquals("G", st.cls)
        // built: S applied once
        val b = system.bodies[system.star]
        assertEquals("Star G", b.cls)
        assertTrue(b.isStar)
        assertEquals(4800.0 * S, b.radius)
        assertEquals(14_400_000.0, b.radius)
        assertEquals(37000.0 * S, b.heatRadius)
        assertEquals(92000.0 * S, b.massShadowRadius)
        assertEquals(40f, b.minApproachSpeed)
        assertEquals(4.0, b.massShadowFactor)
        assertTrue(!b.landable)
    }

    @Test
    fun everyPlanetAndMoonMatchesTheLuaTimesS() {
        assertEquals(1 + rows.size, system.bodies.size, "1 star, 8 planets, 9 moons")
        assertEquals(8, sol.planets.size)
        assertEquals(9, sol.planets.sumOf { it.moons.size })
        for (row in rows) {
            val i = system.bodyIndex(row.name)
            assertTrue(i > 0, row.name)
            val b = system.bodies[i]
            assertEquals(row.cls, b.cls, row.name)
            assertEquals(row.radius * S, b.radius, 1e-6, "${row.name} radius")
            assertEquals(row.orbit * S, b.orbitRadius, 1e-6, "${row.name} orbit")
            assertEquals(row.period, b.orbitPeriod, 1.0, "${row.name} period = 2π·orbit/speed")
            assertEquals(row.g, b.gravityG, 1e-9, "${row.name} gravity")
            assertEquals(row.colour, b.colour, "${row.name} colour")
            assertEquals(row.collision * S, b.collisionRadius, 1e-6, "${row.name} barrier ceil(1.105 R) + 6")
            val parent = if (row.parent == null) system.star else system.bodyIndex(row.parent)
            assertEquals(parent, b.parent, "${row.name} orbits ${row.parent ?: "Sol"}")
            // orbital speed x S with the period kept
            assertEquals(row.speed * S, 2.0 * PI * b.orbitRadius / b.orbitPeriod, 1e-6, "${row.name} speed at S")
            // the moon side of the attribute contract
            val moon = row.parent != null
            assertEquals(if (moon) 12f else 14f, b.minApproachSpeed, row.name)
            assertEquals(if (moon) 4.0 else 5.0, b.massShadowFactor, row.name)
        }
        // the headline numbers of 03 0 and B.1
        assertEquals(6.36e6, system.bodies[system.bodyIndex("Earth")].radius, 1e-3)
        assertEquals(3.84e8, system.bodies[system.bodyIndex("Luna")].orbitRadius, 1e-3)
        assertEquals(1.74e6, system.bodies[system.bodyIndex("Luna")].radius, 1e-3)
        assertEquals(2.486e9, system.bodies[system.bodyIndex("Earth")].orbitRadius, 1e6)
        assertEquals(20.509e9, system.bodies[system.bodyIndex("Neptune")].orbitRadius, 1e6)
        // Earth's orbital speed: 3.72 studs/s x 3000 = 11.16 km/s
        val e = system.bodies[system.bodyIndex("Earth")]
        assertEquals(11_160.0, 2.0 * PI * e.orbitRadius / e.orbitPeriod, 1e-6)
    }

    @Test
    fun theMassShadowAndInfluenceRadiiOfTheTable() {
        // shadow altitude above the barrier = Rc x factor (planets 5, moons 4); influence = 2.2 Rc
        val shadow = mapOf("Earth" to 11745.0, "Luna" to 2588.0, "Jupiter" to 51525.0, "Phobos" to 688.0, "Triton" to 2016.0)
        for ((name, alt) in shadow) {
            val b = system.bodies[system.bodyIndex(name)]
            assertEquals(alt * S, b.collisionRadius * b.massShadowFactor, 1e-6, "$name shadow")
        }
        assertEquals(5168.0 * S, system.bodies[system.bodyIndex("Earth")].collisionRadius * 2.2, 0.5 * S)
    }

    @Test
    fun landingFollowsTheClass() {
        for (b in system.bodies) {
            val gas = b.cls == "Gas Giant" || b.cls == "Ice Giant"
            when {
                b.isStar -> assertTrue(!b.landable)
                gas -> {
                    assertTrue(!b.landable, b.name)
                    assertEquals(StarSystem.NO_SOLID_SURFACE, b.landingBlocked, b.name)
                }
                else -> {
                    assertTrue(b.landable, b.name)
                    assertNull(b.landingBlocked, b.name)
                }
            }
        }
        // Venus lands since 2026-11-04; Mercury has no air, Mars has
        assertTrue(system.bodies[system.bodyIndex("Venus")].landable)
        assertTrue(!system.bodies[system.bodyIndex("Mercury")].hasAtmosphere)
        assertTrue(system.bodies[system.bodyIndex("Mars")].hasAtmosphere)
        assertEquals(rgb(215, 150, 110), system.bodies[system.bodyIndex("Mars")].atmosphereColour)
        assertEquals(rgb(120, 180, 255), system.bodies[system.bodyIndex("Earth")].atmosphereColour)
        // moons have no air in Roblox (Titan's orange limb is the port's, DECISIONS 17b)
        for (b in system.bodies) if (b.cls == "Moon") assertTrue(!b.hasAtmosphere, b.name)
    }

    @Test
    fun theRingsOfTheTable() {
        fun ring(name: String) = system.bodies[system.bodyIndex(name)]
        val jupiter = ring("Jupiter")
        assertTrue(jupiter.hasRings)
        assertEquals(13_514.0 * S, jupiter.ringInner, 0.5 * S)
        assertEquals(16_030.0 * S, jupiter.ringOuter, 0.5 * S)
        assertEquals(rgb(150, 130, 108), jupiter.ringColour)
        assertEquals(0.28f, jupiter.ringDensity, 1e-6f)
        assertEquals(RingProfiles.JUPITER, jupiter.ringProfile)
        val saturn = ring("Saturn")
        assertEquals(9_627.0 * S, saturn.ringInner, 0.5 * S)
        assertEquals(17_624.0 * S, saturn.ringOuter, 0.5 * S)
        assertEquals(0.68f, saturn.ringDensity, 1e-6f)
        assertEquals(RingProfiles.SATURN, saturn.ringProfile)
        val uranus = ring("Uranus")
        assertEquals(5_543.0 * S, uranus.ringInner, 0.5 * S)
        assertEquals(6_760.0 * S, uranus.ringOuter, 0.5 * S)
        assertEquals(0.22f, uranus.ringDensity, 1e-6f)
        // tilt 82 degrees: RingNormal = Rz(82°)·(0, 1, 0) = (-0.990, 0.139, 0), and the axis follows
        assertEquals(-0.990f, uranus.ringNormal.x, 5e-4f)
        assertEquals(0.139f, uranus.ringNormal.y, 5e-4f)
        assertEquals(0f, uranus.ringNormal.z, 1e-6f)
        val axis = uranus.rot.rotate(0f, 1f, 0f, com.starwake.core.math.Vec3())
        assertEquals(1f, axis.dot(uranus.ringNormal), 1e-5f)
        for (name in listOf("Mercury", "Venus", "Earth", "Mars", "Neptune")) assertTrue(!ring(name).hasRings, name)
    }

    @Test
    fun seedsAndTheirStreams() {
        // planet seeds 1..8, moon seeds planet·31 + moon index
        val seeds = mapOf(
            "Mercury" to 1, "Earth" to 3, "Neptune" to 8, "Luna" to 94, "Phobos" to 125, "Deimos" to 126,
            "Io" to 156, "Europa" to 157, "Ganymede" to 158, "Callisto" to 159, "Titan" to 187, "Triton" to 249,
        )
        for ((name, seed) in seeds) assertEquals(seed, system.bodies[system.bodyIndex(name)].seed, name)
        // phases in [0, 1), planet orbit tilts within ±1.5°, moon tilts within ±5°
        for (b in system.bodies) {
            if (b.isStar) continue
            assertTrue(b.orbitPhase >= 0.0 && b.orbitPhase < 1.0, "${b.name} phase ${b.orbitPhase}")
            val lim = if (b.cls == "Moon") 5.0 else 1.5
            assertTrue(abs(b.orbitTilt) <= lim, "${b.name} tilt ${b.orbitTilt}")
        }
        // the same seeds give the same system
        val again = WorldBuilder.sol()
        for (i in system.bodies.indices) {
            assertEquals(system.bodies[i].orbitPhase, again.bodies[i].orbitPhase)
            assertEquals(system.bodies[i].orbitTilt, again.bodies[i].orbitTilt)
        }
    }

    @Test
    fun theSixSolStationsAtTheirScaledDistances() {
        assertEquals(6, system.stations.size)
        val expect = listOf(
            Triple("SOL-MERIDIAN", "Earth", 6600.0), Triple("SOL-TRANQUILITY", "Luna", 3400.0),
            Triple("SOL-ARES", "Mars", 4600.0), Triple("SOL-GALILEAN", "Jupiter", 20000.0),
            Triple("SOL-TITAN", "Titan", 4100.0), Triple("SOL-HELIOS", "Mercury", 4400.0),
        )
        for ((k, e) in expect.withIndex()) {
            val st = system.stations[k]
            assertEquals(e.first, st.id)
            assertEquals(e.second, system.bodies[st.parent].name)
            val o = st.offset
            val d = kotlin.math.sqrt(o.x * o.x + o.y * o.y + o.z * o.z)
            assertEquals(e.third * S, d, 1e-3, "${st.name} distance")
            // the station hangs at host + offset
            val h = system.bodies[st.parent].pos
            assertEquals(h.x + o.x, st.pos.x, 1e-6)
            assertEquals(h.z + o.z, st.pos.z, 1e-6)
            assertNotNull(st.description)
        }
        // MERIDIAN: 19,800 km from Earth's centre, 13,440 km above the surface
        val meridian = system.stations[0]
        assertEquals("MERIDIAN STATION", meridian.name)
        assertEquals(19_800_000.0, 6600.0 * S)
        assertEquals("SpireStation", meridian.modelName)
        assertEquals("RingStation", system.stations[5].modelName)
    }
}
