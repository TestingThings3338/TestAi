package com.starwake.core.station

/**
 * A body as the station and economy rules see it (ShipStations.getBodies): name -> class, star
 * class, ring, landable, radius, parent. Sizes are Roblox studs, NOT the scaled world metres: the
 * Roblox formulas (station distance `r·2.1 + 2200`, slots, survey ranges) are written for them, and
 * the world multiplies the results by S (DECISIONS 1, 26).
 */
class BodyInfo(
    val name: String,
    /** "Star", "Moon", or the planet class ("Terrestrial World", "Gas Giant" ..). */
    val cls: String,
    /** Stars: spectral class ("G"); null for planets and moons. */
    val starClass: String? = null,
    val ring: Boolean = false,
    val landable: Boolean = false,
    /** Radius in Roblox studs. */
    val radius: Double,
    /** Moons: the planet they orbit; null for planets and stars. */
    val parent: String? = null,
    /** Stars: heat radius in Roblox studs (0 = not given; the survey then uses 1.6 radii). */
    val heatRadius: Double = 0.0,
) {
    val isStar: Boolean get() = cls == STAR
    val isMoon: Boolean get() = parent != null

    /** A planet: neither the star nor a moon (ShipSystemBuilder's Planets list). */
    val isPlanet: Boolean get() = !isStar && parent == null

    override fun toString(): String = "BodyInfo($name, $cls)"

    companion object {
        const val STAR = "Star"
        const val MOON = "Moon"
    }
}

/**
 * The world data the economy needs, as a small interface so the world port (SolData, the star
 * catalogue, ShipSystemBuilder) plugs in with an adapter. Systems are numbered 1..[systemCount]
 * as in Roblox (1 = Sol, ShipConfig.StarMap.SystemCount = 180).
 */
interface WorldInfo {
    /** Number of systems (valid indices are 1..systemCount). */
    val systemCount: Int

    /** ShipStations.getSystemName: "Sol", the catalogue name, or "Unknown". */
    fun systemName(index: Int): String

    /** ShipStarCatalog seed of system [index] (Sol: 0); generated stations and deposits derive from it. */
    fun systemSeed(index: Int): Long

    /**
     * The bodies of system [index] in ShipSystemBuilder definition order: the star first, then each
     * planet followed by its moons. Empty when the system is unknown. Called rarely (cached by the
     * station catalogue).
     */
    fun bodies(index: Int): List<BodyInfo>
}

/**
 * Sol only, as a table of this module's own (ShipCore/ShipSolData.lua, values verbatim): what the
 * economy runs on until the world port provides the full galaxy through an adapter, and the
 * reference data for the Appendix A vectors. Radii and the heat radius in Roblox studs.
 */
object SolWorld : WorldInfo {
    override val systemCount: Int get() = 1

    override fun systemName(index: Int): String = if (index == 1) "Sol" else "Unknown"

    override fun systemSeed(index: Int): Long = 0L

    override fun bodies(index: Int): List<BodyInfo> = if (index == 1) BODIES else emptyList()

    private fun planet(name: String, cls: String, radius: Double, landable: Boolean, ring: Boolean = false) =
        BodyInfo(name, cls, ring = ring, landable = landable, radius = radius)

    private fun moon(name: String, radius: Double, parent: String) =
        BodyInfo(name, BodyInfo.MOON, landable = true, radius = radius, parent = parent)

    /** ShipSolData: the star, the planets in order, each followed by its moons (all Sol moons are landable). */
    val BODIES: List<BodyInfo> = listOf(
        BodyInfo("Sol", BodyInfo.STAR, starClass = "G", radius = 4800.0, heatRadius = 37000.0),
        planet("Mercury", "Rocky Body", 810.0, landable = true),
        planet("Venus", "Greenhouse World", 2020.0, landable = true),
        planet("Earth", "Terrestrial World", 2120.0, landable = true),
        moon("Luna", 580.0, "Earth"),
        planet("Mars", "Rocky Body", 1130.0, landable = true),
        moon("Phobos", 150.0, "Mars"),
        moon("Deimos", 150.0, "Mars"),
        planet("Jupiter", "Gas Giant", 9320.0, landable = false, ring = true),
        moon("Io", 610.0, "Jupiter"),
        moon("Europa", 520.0, "Jupiter"),
        moon("Ganymede", 880.0, "Jupiter"),
        moon("Callisto", 800.0, "Jupiter"),
        planet("Saturn", "Gas Giant", 7764.0, landable = false, ring = true),
        moon("Titan", 860.0, "Saturn"),
        planet("Uranus", "Ice Giant", 3380.0, landable = false, ring = true),
        planet("Neptune", "Ice Giant", 3284.0, landable = false),
        moon("Triton", 450.0, "Neptune"),
    )
}
