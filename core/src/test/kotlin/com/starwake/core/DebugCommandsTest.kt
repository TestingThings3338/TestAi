package com.starwake.core

import com.starwake.core.game.StarSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The argument parsing of the debug commands (01-core-loop.md 9.6) that runs off the frame path. */
class DebugCommandsTest {
    private val system = StarSystem()
    private val planet2 = system.bodies.indexOfFirst { it.name == "PLANET II" }

    private fun near(cmd: String) = Game.nearTarget(cmd.split(' '), system)

    @Test
    fun nearTakesABodyIndexWithTheDefaultAltitude() {
        // "near 3" is body 3 at 20 km, not body 3 at 3 m
        assertEquals(planet2 to Game.NEAR_ALTITUDE, near("near $planet2"))
        assertEquals(planet2 to 5_000.0, near("near $planet2 5000"))
        assertEquals(0 to Game.NEAR_ALTITUDE, near("near 0"))
    }

    @Test
    fun nearTakesABodyNameWithOrWithoutAnAltitude() {
        assertEquals(planet2 to Game.NEAR_ALTITUDE, near("near PLANET-II"))
        assertEquals(planet2 to Game.NEAR_ALTITUDE, near("near PLANET II"))
        assertEquals(planet2 to 750.0, near("near planet ii 750"))
        assertEquals(planet2 to 750.0, near("near PLANET-II 750"))
    }

    @Test
    fun nearIgnoresUnknownBodies() {
        assertNull(near("near"))
        assertNull(near("near ${system.bodies.size}"))
        assertNull(near("near -1"))
        assertNull(near("near NOWHERE 100"))
    }
}
