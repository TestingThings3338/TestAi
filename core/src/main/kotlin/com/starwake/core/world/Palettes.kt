package com.starwake.core.world

/**
 * The class colour ramps of `ShipCore/ShipPalettes.lua` (`COLOURS`, 03-world.md 2.6): one source for
 * the look from orbit and the ground. RGB ints; `deep`/`shallow` only for water worlds, `ice` only
 * beyond [iceLatitude] (polar fraction). The classes no body uses (Metal-Rich Body, High Metal
 * Content, Ammonia World, Water World) are dropped (DECISIONS 30). Material palettes belong to the
 * terrain area.
 */
class ColourRamp(
    val deep: Int,
    val shallow: Int,
    val shore: Int,
    val low: Int,
    val high: Int,
    val peak: Int,
    val ice: Int,
    val iceLatitude: Float,
    val water: Boolean,
    val banded: Boolean = false,
)

object Palettes {
    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    private val TERRESTRIAL = ColourRamp(
        deep = rgb(14, 42, 84), shallow = rgb(38, 92, 140), shore = rgb(196, 182, 138), low = rgb(74, 116, 56),
        high = rgb(108, 96, 74), peak = rgb(150, 146, 140), ice = rgb(238, 244, 250), iceLatitude = 0.74f, water = true,
    )
    private val OCEAN = ColourRamp(
        deep = rgb(10, 36, 78), shallow = rgb(34, 100, 150), shore = rgb(190, 180, 145), low = rgb(66, 110, 60),
        high = rgb(96, 92, 72), peak = rgb(150, 150, 145), ice = rgb(238, 244, 250), iceLatitude = 0.78f, water = true,
    )

    /** `DEFAULT_COLOURS` = the Rocky Body ramp. */
    val ROCKY = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(150, 130, 108), low = rgb(122, 104, 86),
        high = rgb(96, 86, 76), peak = rgb(140, 134, 128), ice = rgb(210, 212, 216), iceLatitude = 0.92f, water = false,
    )
    private val VOLCANIC = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(58, 38, 34), low = rgb(74, 44, 38),
        high = rgb(120, 52, 30), peak = rgb(178, 74, 32), ice = rgb(90, 60, 52), iceLatitude = 0.99f, water = false,
    )
    private val ICE_WORLD = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(176, 196, 210), low = rgb(198, 218, 230),
        high = rgb(214, 232, 242), peak = rgb(240, 248, 255), ice = rgb(248, 252, 255), iceLatitude = 0.45f, water = false,
    )
    private val ICE_GIANT = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(70, 140, 180), low = rgb(90, 170, 200),
        high = rgb(120, 200, 220), peak = rgb(180, 230, 240), ice = rgb(220, 245, 250), iceLatitude = 0.85f, water = false,
    )
    private val MOON = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(126, 122, 116), low = rgb(148, 144, 138),
        high = rgb(104, 100, 96), peak = rgb(172, 168, 162), ice = rgb(206, 208, 212), iceLatitude = 0.96f, water = false,
    )
    private val GREENHOUSE = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(196, 168, 108), low = rgb(214, 186, 124),
        high = rgb(168, 132, 86), peak = rgb(226, 204, 156), ice = rgb(230, 214, 176), iceLatitude = 0.99f, water = false,
    )
    private val GAS_GIANT = ColourRamp(
        deep = 0, shallow = 0, shore = rgb(196, 164, 118), low = rgb(214, 188, 148),
        high = rgb(164, 128, 90), peak = rgb(232, 214, 184), ice = rgb(238, 228, 208), iceLatitude = 0.99f, water = false,
        banded = true,
    )

    /** `ShipPalettes.colours(class)`: the ramp of a body class, the Rocky Body ramp for anything else. */
    fun colours(cls: String): ColourRamp = when (cls) {
        "Terrestrial World" -> TERRESTRIAL
        "Ocean World" -> OCEAN
        "Rocky Body" -> ROCKY
        "Volcanic Body" -> VOLCANIC
        "Ice World" -> ICE_WORLD
        "Ice Giant" -> ICE_GIANT
        "Moon" -> MOON
        "Greenhouse World" -> GREENHOUSE
        "Gas Giant" -> GAS_GIANT
        else -> ROCKY
    }
}
