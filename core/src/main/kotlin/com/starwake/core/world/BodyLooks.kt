package com.starwake.core.world

import com.starwake.core.game.StarSystem

/**
 * How each body is painted by the procedural surface bake (`BodyShaders.bakeFs`), standing in for the
 * Roblox body images (`ShipVisualAssets.Bodies`, 4096² cube crosses that are not in the export,
 * 03-world.md Q5) until the terrain engine generates the looks on the device (DECISIONS 17a). Every
 * Sol body gets its own recipe in its Roblox colour and class palette ([Palettes]); generated bodies
 * fall back to their class.
 *
 * Also the limb of the air seen from space (DECISIONS 17b: the visible limb is 3× the profile
 * thickness, Earth 78 km; Titan gets its orange limb).
 */
object BodyLooks {
    const val GENERIC_ROCK = 0
    const val EARTH = 1
    const val MERCURY = 2
    const val VENUS = 3
    const val MARS = 4
    const val LUNA = 5
    const val SMALL_MOON = 6
    const val IO = 7
    const val EUROPA = 8
    const val GANYMEDE = 9
    const val CALLISTO = 10
    const val TITAN = 11
    const val TRITON = 12
    const val JUPITER = 13
    const val SATURN = 14
    const val URANUS = 15
    const val NEPTUNE = 16
    const val GENERIC_GAS = 17
    const val GENERIC_ICE_GIANT = 18
    const val GENERIC_ICE = 19
    const val GENERIC_LAVA = 20
    const val GENERIC_OCEAN = 21
    const val GENERIC_MOON = 22
    const val GENERIC_CLOUDY = 23

    /** The recipe for a body called [name] of class [cls]. */
    fun look(name: String, cls: String): Int = when (name) {
        "Earth" -> EARTH
        "Mercury" -> MERCURY
        "Venus" -> VENUS
        "Mars" -> MARS
        "Luna" -> LUNA
        "Phobos", "Deimos" -> SMALL_MOON
        "Io" -> IO
        "Europa" -> EUROPA
        "Ganymede" -> GANYMEDE
        "Callisto" -> CALLISTO
        "Titan" -> TITAN
        "Triton" -> TRITON
        "Jupiter" -> JUPITER
        "Saturn" -> SATURN
        "Uranus" -> URANUS
        "Neptune" -> NEPTUNE
        else -> when (cls) {
            "Gas Giant" -> GENERIC_GAS
            "Ice Giant" -> GENERIC_ICE_GIANT
            "Ice World" -> GENERIC_ICE
            "Volcanic Body" -> GENERIC_LAVA
            "Ocean World", "Terrestrial World" -> GENERIC_OCEAN
            "Moon" -> GENERIC_MOON
            "Greenhouse World" -> GENERIC_CLOUDY
            else -> GENERIC_ROCK
        }
    }

    /**
     * The render family (03-world.md 7 #4): Terrestrial and Ocean → TERRAN; Rocky, Moon and
     * Greenhouse → ROCK; Volcanic → LAVA; Ice World → ICE; Gas and Ice Giant → GAS (banded).
     */
    fun type(cls: String): StarSystem.BodyType = when {
        cls.startsWith("Star") -> StarSystem.BodyType.STAR
        cls == "Terrestrial World" || cls == "Ocean World" -> StarSystem.BodyType.TERRAN
        cls == "Volcanic Body" -> StarSystem.BodyType.LAVA
        cls == "Ice World" -> StarSystem.BodyType.ICE
        cls == "Gas Giant" || cls == "Ice Giant" -> StarSystem.BodyType.GAS
        else -> StarSystem.BodyType.ROCK
    }

    /**
     * Height of the visible limb (m) above the surface: 3 × the sky profile's thickness (05
     * 8.7: Earth 26 km → 78 km, Mars 22, Venus 40, Titan 36), 0 without air. The giants have no
     * landing profile; their cloud-top haze is the world area's choice (60 km of air → 180 km).
     */
    fun limbHeight(name: String, cls: String, hasAtmosphere: Boolean): Double = when (name) {
        "Earth" -> 78_000.0
        "Mars" -> 66_000.0
        "Venus" -> 120_000.0
        "Titan" -> 108_000.0
        else -> when {
            cls == "Gas Giant" || cls == "Ice Giant" -> 180_000.0
            hasAtmosphere -> 78_000.0 // generated bodies: thickness 26 km
            else -> 0.0
        }
    }

    /** Titan's air colour (the sky profile's, 214,146,62): the moon has no `AtmosphereColour`. */
    const val TITAN_AIR = (214 shl 16) or (146 shl 8) or 62

    /**
     * Vertical optical depth of the limb's air at its strongest channel: how much the air dims and
     * tints the ground seen from orbit. Thick hazes (Venus, Titan) hide the ground, Mars barely.
     */
    fun airDepth(name: String, cls: String): Float = when (name) {
        "Earth" -> 0.10f
        "Mars" -> 0.05f
        "Venus" -> 0.30f
        "Titan" -> 0.35f
        else -> if (cls == "Gas Giant" || cls == "Ice Giant") 0.12f else 0.08f
    }
}
