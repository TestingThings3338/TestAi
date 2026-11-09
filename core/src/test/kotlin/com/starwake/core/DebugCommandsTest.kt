package com.starwake.core

import com.starwake.core.world.WorldBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The argument parsing of the debug commands (01-core-loop.md 9.6) that runs off the frame path. */
class DebugCommandsTest {
    private val system = WorldBuilder.sol()
    private val earth = system.bodyIndex("Earth")

    private fun near(cmd: String) = Game.nearTarget(cmd.split(' '), system)

    @Test
    fun nearTakesABodyIndexWithTheDefaultAltitude() {
        // "near 3" is body 3 at 20 km, not body 3 at 3 m
        assertEquals(earth to Game.NEAR_ALTITUDE, near("near $earth"))
        assertEquals(earth to 5_000.0, near("near $earth 5000"))
        assertEquals(0 to Game.NEAR_ALTITUDE, near("near 0"))
    }

    @Test
    fun nearTakesABodyNameWithOrWithoutAnAltitude() {
        assertEquals(earth to Game.NEAR_ALTITUDE, near("near EARTH"))
        assertEquals(earth to Game.NEAR_ALTITUDE, near("near Earth"))
        assertEquals(earth to 750.0, near("near earth 750"))
        assertEquals(earth to 750.0, near("near EARTH 750"))
        // a '-' stands for a space in a name
        val luna = system.bodyIndex("Luna")
        assertEquals(luna to 100.0, near("near LUNA 100"))
    }

    @Test
    fun nearIgnoresUnknownBodies() {
        assertNull(near("near"))
        assertNull(near("near ${system.bodies.size}"))
        assertNull(near("near -1"))
        assertNull(near("near NOWHERE 100"))
    }
}
