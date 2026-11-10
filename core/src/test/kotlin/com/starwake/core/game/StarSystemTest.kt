package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import com.starwake.core.world.WorldBuilder
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The runtime star system's own rules on Sol: the field and the sunlight. The speed law and the
 * zones moved to the flight port (`flight/GravityFieldTest`, `flight/SpeedLawTest`).
 */
class StarSystemTest {
    private val system = WorldBuilder.sol()
    private val earth = system.bodies[system.bodyIndex("Earth")]

    private fun at(x: Double, y: Double, z: Double) = DVec3(x, y, z)

    @Test
    fun systemEdgeIsTheScaledFieldRadius() {
        // Ship.FieldRadius 9,000,000 studs x 3000 = 27 Gm
        assertEquals(2.7e10, system.edgeRadius, 1.0)
    }

    @Test
    fun planetsCastShadows() {
        val dir = Vec3()
        // MERIDIAN stands a quarter turn from the Sun's direction: in daylight
        assertTrue(system.sunlight(system.stations[0].pos, dir) > 0.99f)
        // directly behind Earth (as seen from the star)
        val g = earth.pos
        val d = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
        val behind = at(g.x / d * (d + earth.radius * 3), g.y / d * (d + earth.radius * 3), g.z / d * (d + earth.radius * 3))
        assertEquals(0f, system.sunlight(behind, dir))
        // the direction points at the star
        val toStar = Vec3(-behind.x.toFloat(), -behind.y.toFloat(), -behind.z.toFloat()).normalize()
        assertTrue(dir.dot(toStar) > 0.9999f)
    }
}
