package com.starwake.core.world

/**
 * Sol, verbatim from `ShipCore/ShipSolData.lua` (03-world.md 2.1): every number in Roblox studs
 * (1 stud = 1 m in Roblox), so this file diffs line by line against the Lua. [WorldBuilder]
 * multiplies the lengths by [WorldScale.S] (3000): Earth 6,360 km, Luna 1,740 km at 384,000 km.
 *
 * The Lua's two scales are deliberate: rocky bodies ×10 of "1 stud = 30 km" (radius ≥ 150), giants
 * ×4, the Sun ×3; orbits `828,660·AU^0.62` with the orbit speeds kept (each period 10× longer);
 * moon orbits in the real ratio to their planet's radius. Phases, orbit tilts and spin yaws are
 * not data: `build()` draws them from seeded random streams.
 */
object SolData {
    /** Informational only in the Lua ("echter Massstab, nur noch Richtwert"). */
    const val STUDS_PER_KM = 1.0 / 30.0

    private fun rgb(r: Int, g: Int, b: Int) = (r shl 16) or (g shl 8) or b

    val SOL = SystemDef(
        name = "Sol",
        seed = null,
        star = StarDef(
            name = "Sol",
            radius = 4800.0, // x3 (was 1600); 0.66 degrees from Earth
            skyRadius = 4000.0,
            heatRadius = 37000.0, // overheating inward from here
            massShadowRadius = 92000.0, // only the jump-arrival distance now
            coronaScale = 1.35,
            colour = rgb(255, 246, 224),
            corona = rgb(255, 190, 110),
            cls = "G",
        ),
        planets = listOf(
            PlanetDef(
                name = "Mercury", gravityG = 0.3770, radius = 810.0, orbit = 460000.0, speed = 5.00,
                colour = rgb(140, 130, 122), material = "Slate",
                cls = "Rocky Body", landable = true, atmosphere = false,
            ),
            PlanetDef(
                name = "Venus", gravityG = 0.9040, radius = 2020.0, orbit = 677710.0, speed = 4.12,
                // "LANDING BLOCKED - SURFACE PRESSURE" until 2026-11-04 ("land on every planet")
                landable = true,
                colour = rgb(216, 190, 140), material = "Sandstone",
                cls = "Greenhouse World", atmosphere = true,
                atmosphereColour = rgb(240, 220, 170),
            ),
            PlanetDef(
                name = "Earth", gravityG = 1.0000, radius = 2120.0, orbit = 828660.0, speed = 3.72,
                colour = rgb(52, 96, 152), material = "Marble",
                cls = "Terrestrial World", landable = true, atmosphere = true,
                atmosphereColour = rgb(120, 180, 255),
                moons = listOf(
                    MoonDef(
                        name = "Luna", landable = true, gravityG = 0.1650, radius = 580.0, orbit = 128000.0, speed = 1.6,
                        colour = rgb(158, 154, 148),
                    ),
                ),
            ),
            PlanetDef(
                name = "Mars", gravityG = 0.3780, radius = 1130.0, orbit = 1076040.0, speed = 3.27,
                colour = rgb(168, 96, 62), material = "Basalt",
                cls = "Rocky Body", landable = true, atmosphere = true,
                atmosphereColour = rgb(215, 150, 110),
                moons = listOf(
                    MoonDef(
                        name = "Phobos", landable = true, gravityG = 0.0006, radius = 150.0, orbit = 3600.0, speed = 1.1,
                        colour = rgb(120, 110, 100),
                    ),
                    MoonDef(
                        name = "Deimos", landable = true, gravityG = 0.0003, radius = 150.0, orbit = 7800.0, speed = 0.9,
                        colour = rgb(126, 116, 106),
                    ),
                ),
            ),
            PlanetDef(
                name = "Jupiter", gravityG = 2.5280, radius = 9320.0, orbit = 2303850.0, speed = 2.23,
                colour = rgb(206, 172, 132), material = "Sand",
                cls = "Gas Giant", atmosphere = true,
                atmosphereColour = rgb(230, 200, 160),
                ring = RingDef(
                    inner = 1.45, outer = 1.72, segments = 72,
                    colour = rgb(150, 130, 108), transparency = 0.72,
                ),
                moons = listOf(
                    MoonDef(
                        name = "Io", gravityG = 0.1830, landable = true, radius = 610.0, orbit = 55000.0, speed = 2.4,
                        colour = rgb(216, 196, 116),
                    ),
                    MoonDef(
                        name = "Europa", landable = true, gravityG = 0.1340, radius = 520.0, orbit = 87600.0, speed = 2.1,
                        colour = rgb(212, 206, 190),
                    ),
                    MoonDef(
                        name = "Ganymede", gravityG = 0.1460, landable = true, radius = 880.0, orbit = 140000.0, speed = 1.9,
                        colour = rgb(150, 140, 130),
                    ),
                    MoonDef(
                        name = "Callisto", landable = true, gravityG = 0.1260, radius = 800.0, orbit = 245000.0, speed = 1.6,
                        colour = rgb(110, 104, 98),
                    ),
                ),
            ),
            PlanetDef(
                name = "Saturn", gravityG = 1.0650, radius = 7764.0, orbit = 3354380.0, speed = 1.85,
                colour = rgb(216, 194, 148), material = "Sand",
                cls = "Gas Giant", atmosphere = true,
                atmosphereColour = rgb(238, 218, 176),
                ring = RingDef(
                    inner = 1.24, outer = 2.27, segments = 108,
                    colour = rgb(210, 194, 164), transparency = 0.32,
                ),
                moons = listOf(
                    MoonDef(
                        name = "Titan", landable = true, gravityG = 0.1380, radius = 860.0, orbit = 157600.0, speed = 1.7,
                        colour = rgb(196, 150, 96),
                    ),
                ),
            ),
            PlanetDef(
                name = "Uranus", gravityG = 0.8860, radius = 3380.0, orbit = 5174850.0, speed = 1.49,
                colour = rgb(150, 214, 220), material = "Glacier",
                cls = "Ice Giant", atmosphere = true,
                atmosphereColour = rgb(170, 230, 235),
                // the rings stand almost upright: the planet lies on its side
                ring = RingDef(
                    inner = 1.64, outer = 2.00, segments = 64, tiltDeg = 82.0,
                    colour = rgb(120, 150, 160), transparency = 0.78,
                ),
            ),
            PlanetDef(
                name = "Neptune", gravityG = 1.1370, radius = 3284.0, orbit = 6836280.0, speed = 1.30,
                colour = rgb(62, 96, 200), material = "Glacier",
                cls = "Ice Giant", atmosphere = true,
                atmosphereColour = rgb(110, 150, 255),
                moons = listOf(
                    MoonDef(
                        name = "Triton", landable = true, gravityG = 0.0790, radius = 450.0, orbit = 47300.0, speed = 1.4,
                        colour = rgb(200, 196, 186),
                    ),
                ),
            ),
        ),
    )
}
