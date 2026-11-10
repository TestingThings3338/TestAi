package com.starwake.core.world

/**
 * A star system definition in Roblox units (studs), the schema `ShipSystemBuilder.build` consumes
 * (03-world.md 2.1, "The definition schema"): hand-made ([SolData]) and generated systems share it.
 * Colours are 0xRRGGBB ints (Roblox `Color3.fromRGB`), materials the Roblox material names. Optional
 * Lua fields are nullable; `WorldBuilder` applies the Roblox defaults.
 */
class SystemDef(
    val name: String,
    /** Seed of a generated system; null for Sol. */
    val seed: Int?,
    val star: StarDef,
    val planets: List<PlanetDef>,
)

class StarDef(
    val name: String,
    /** Radius in space, studs. */
    val radius: Double,
    val colour: Int,
    val corona: Int,
    /** Spectral class letter; the body class becomes "Star <class>" (default "G"). */
    val cls: String? = null,
    /** Size in a planet's sky (the Roblox landing scene; unused in the seamless port). */
    val skyRadius: Double? = null,
    /** Overheating inward from here, studs (default 1.6 R). */
    val heatRadius: Double? = null,
    /** Jump-arrival distance, studs (default 4 R). */
    val massShadowRadius: Double? = null,
    /** Corona sphere / radius (default 1.12). */
    val coronaScale: Double? = null,
)

class RingDef(
    /** Inner and outer edge in planet radii. */
    val inner: Double,
    val outer: Double,
    /** Segment parts of the Roblox fallback ring (unused by the port's ring shader). */
    val segments: Int,
    /** Tilt about +Z, degrees; it also tilts the planet's axis (Uranus 82). */
    val tiltDeg: Double? = null,
    val colour: Int,
    /** Part transparency of the fallback ring; RingDensity = 1 - this. */
    val transparency: Double,
)

class MoonDef(
    val name: String,
    val radius: Double,
    /** Orbit radius around the planet, studs. */
    val orbit: Double,
    /** Orbit speed, studs per second (period = 2π·orbit/speed). */
    val speed: Double,
    val colour: Int,
    val landable: Boolean = false,
    val gravityG: Double? = null,
    val material: String? = null,
    val cls: String? = null,
    val phase: Double? = null,
    val tilt: Double? = null,
)

class PlanetDef(
    val name: String,
    val radius: Double,
    /** Orbit radius around the star, studs. */
    val orbit: Double,
    /** Orbit speed, studs per second. */
    val speed: Double,
    val colour: Int,
    val material: String,
    val cls: String,
    /** "For economy and hand mining" only; landing follows the class (WorldBuilder). */
    val landable: Boolean = false,
    val atmosphere: Boolean = false,
    val atmosphereColour: Int? = null,
    val gravityG: Double? = null,
    val ring: RingDef? = null,
    val moons: List<MoonDef> = emptyList(),
    val seed: Int? = null,
    val phase: Double? = null,
    val tilt: Double? = null,
    /** Template colour of a generated planet (the class image tint). */
    val classColour: Int? = null,
    val landingBlocked: String? = null,
)
