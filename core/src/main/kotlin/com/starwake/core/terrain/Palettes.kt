package com.starwake.core.terrain

/** An sRGB colour, components 0..1 as Roblox's Color3 stores them. Immutable. */
class Rgb(@JvmField val r: Double, @JvmField val g: Double, @JvmField val b: Double) {
    /** Color3:Lerp (linear in the stored values). */
    fun lerp(o: Rgb, t: Double) = Rgb(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t)

    /** Roblox `shade(c, k)` = clamp(c·k) per channel. */
    fun shade(k: Double) = Rgb(TerrainMath.shade(r, k), TerrainMath.shade(g, k), TerrainMath.shade(b, k))

    fun to255(): String = "%d,%d,%d".format(Math.round(r * 255).toInt(), Math.round(g * 255).toInt(), Math.round(b * 255).toInt())

    override fun toString() = "rgb(${to255()})"

    companion object {
        fun of(r: Int, g: Int, b: Int) = Rgb(r / 255.0, g / 255.0, b / 255.0)
    }
}

/**
 * ShipPalettes.colours (ShipCore/ShipPalettes.lua:126): the colour ramp per body class that feeds
 * the derived terrain colour sets (§2.2.7) and the planet-scale layer (DECISIONS 17a). Values
 * verbatim; `DEFAULT` is the Rocky Body ramp.
 */
class ClassPalette(
    val shore: Rgb,
    val low: Rgb,
    val high: Rgb,
    val peak: Rgb,
    val ice: Rgb,
    val iceLatitude: Double,
    val deep: Rgb? = null,
    val shallow: Rgb? = null,
    val banded: Boolean = false,
    /** ShipPalettes.PALETTES water flag (only the three water classes). */
    val water: Boolean = false,
)

object Palettes {
    private fun c(r: Int, g: Int, b: Int) = Rgb.of(r, g, b)

    val COLOURS: Map<String, ClassPalette> = mapOf(
        "Terrestrial World" to ClassPalette(
            c(196, 182, 138), c(74, 116, 56), c(108, 96, 74), c(150, 146, 140), c(238, 244, 250), 0.74,
            deep = c(14, 42, 84), shallow = c(38, 92, 140), water = true,
        ),
        "Ocean World" to ClassPalette(
            c(190, 180, 145), c(66, 110, 60), c(96, 92, 72), c(150, 150, 145), c(238, 244, 250), 0.78,
            deep = c(10, 36, 78), shallow = c(34, 100, 150), water = true,
        ),
        "Water World" to ClassPalette(
            c(190, 180, 145), c(66, 110, 60), c(96, 92, 72), c(150, 150, 145), c(238, 244, 250), 0.80,
            deep = c(10, 36, 78), shallow = c(34, 100, 150), water = true,
        ),
        "Rocky Body" to ClassPalette(c(150, 130, 108), c(122, 104, 86), c(96, 86, 76), c(140, 134, 128), c(210, 212, 216), 0.92),
        "Metal-Rich Body" to ClassPalette(c(120, 116, 112), c(138, 132, 124), c(96, 92, 88), c(168, 162, 152), c(200, 204, 210), 0.95),
        "High Metal Content" to ClassPalette(c(126, 108, 94), c(140, 116, 96), c(102, 88, 78), c(160, 150, 138), c(200, 204, 210), 0.95),
        "Volcanic Body" to ClassPalette(c(58, 38, 34), c(74, 44, 38), c(120, 52, 30), c(178, 74, 32), c(90, 60, 52), 0.99),
        "Ice World" to ClassPalette(c(176, 196, 210), c(198, 218, 230), c(214, 232, 242), c(240, 248, 255), c(248, 252, 255), 0.45),
        "Ice Giant" to ClassPalette(c(70, 140, 180), c(90, 170, 200), c(120, 200, 220), c(180, 230, 240), c(220, 245, 250), 0.85),
        "Ammonia World" to ClassPalette(c(160, 156, 120), c(184, 176, 128), c(200, 200, 180), c(226, 228, 214), c(236, 240, 232), 0.70),
        "Moon" to ClassPalette(c(126, 122, 116), c(148, 144, 138), c(104, 100, 96), c(172, 168, 162), c(206, 208, 212), 0.96),
        "Greenhouse World" to ClassPalette(c(196, 168, 108), c(214, 186, 124), c(168, 132, 86), c(226, 204, 156), c(230, 214, 176), 0.99),
        "Gas Giant" to ClassPalette(c(196, 164, 118), c(214, 188, 148), c(164, 128, 90), c(232, 214, 184), c(238, 228, 208), 0.99, banded = true),
    )

    val DEFAULT = ClassPalette(c(150, 130, 108), c(122, 104, 86), c(96, 86, 76), c(140, 134, 128), c(210, 212, 216), 0.92)

    /** `ShipPalettes.colours(class)`. */
    fun colours(bodyClass: String?): ClassPalette = COLOURS[bodyClass ?: ""] ?: DEFAULT
}
