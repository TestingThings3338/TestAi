package com.starwake.core.terrain

/** `sky` of a surface profile (§2.6.1). Colours are sRGB 0..1 (Color3). */
data class SkyProfile(
    val air: Boolean = false,
    val colour: Rgb = Rgb.of(190, 214, 244),
    val decay: Rgb = Rgb.of(92, 120, 160),
    val density: Double = 0.3,
    val haze: Double = 1.6,
    val glare: Double = 0.25,
    val skybox: String = "space",
    val thickness: Double = 24000.0,
    val brightness: Double = 2.6,
    val ambient: Rgb = Rgb.of(64, 64, 70),
    val nightAmbient: Rgb = Rgb.of(14, 15, 20),
    val stars: Int = 3000,
    val zenith: Rgb = Rgb.of(70, 128, 214),
    val dusk: Rgb = Rgb.of(232, 126, 80),
)

/** A cloud deck: bottom and top in metres above ground, cover 0..1, colour, share of shadow underneath. */
data class CloudDeck(val bottom: Double, val top: Double, val cover: Double, val colour: Rgb, val dark: Double)

data class EntryGlow(val heat: Double = 0.0, val glow: Rgb = Rgb.of(255, 140, 60))

/** ShipSurfaceProfiles.forBody result: how a body looks from below. Always complete. */
data class SurfaceProfile(
    val title: String = "ROCKY SURFACE",
    val sky: SkyProfile = SkyProfile(),
    val clouds: CloudDeck? = null,
    val entry: EntryGlow = EntryGlow(),
    val name: String = "",
    val bodyClass: String = "Rocky Body",
    val seed: Int = 1,
)

/**
 * ShipSurfaceProfiles (ShipCore/ShipSurfaceProfiles.lua) ported 1:1 as data (§2.6): the hand-made
 * Sol profiles, the generated titles by class and seed, the cloud decks by class and `airFor`.
 */
object SurfaceProfiles {
    private fun c(r: Int, g: Int, b: Int) = Rgb.of(r, g, b)

    private fun scaled(colour: Rgb, k: Double) = colour.shade(k)

    val SOL: Map<String, (SurfaceProfile) -> SurfaceProfile> = mapOf(
        "Earth" to { p ->
            p.copy(
                title = "TEMPERATE WORLD",
                sky = p.sky.copy(
                    air = true, colour = c(190, 214, 244), decay = c(92, 120, 160), density = 0.32, haze = 1.6, glare = 0.3,
                    skybox = "day", thickness = 26000.0, brightness = 2.6, ambient = c(108, 124, 146), nightAmbient = c(16, 20, 30),
                    stars = 2600, zenith = c(70, 128, 214), dusk = c(236, 128, 84),
                ),
                clouds = CloudDeck(2100.0, 2900.0, 0.56, c(255, 255, 255), 0.3),
                entry = EntryGlow(1.0, c(255, 136, 56)),
            )
        },
        "Mars" to { p ->
            p.copy(
                title = "RED DESERT",
                sky = p.sky.copy(
                    air = true, colour = c(222, 166, 124), decay = c(124, 70, 44), density = 0.4, haze = 6.0, glare = 0.12,
                    skybox = "haze", thickness = 22000.0, brightness = 2.3, ambient = c(120, 86, 68), nightAmbient = c(24, 16, 14),
                    stars = 2400, zenith = c(212, 152, 106), dusk = c(120, 150, 196),
                ),
                clouds = CloudDeck(1900.0, 2500.0, 0.2, c(236, 214, 200), 0.2),
                entry = EntryGlow(0.6, c(255, 150, 90)),
            )
        },
        "Titan" to { p ->
            p.copy(
                title = "DUNE SEAS UNDER ORANGE HAZE",
                sky = p.sky.copy(
                    air = true, colour = c(214, 146, 62), decay = c(120, 70, 22), density = 0.6, haze = 7.5, glare = 0.0,
                    skybox = "haze", thickness = 36000.0, brightness = 1.4, ambient = c(118, 86, 50), nightAmbient = c(22, 14, 8),
                    stars = 600, zenith = c(198, 130, 58), dusk = c(146, 76, 34),
                ),
                clouds = CloudDeck(2300.0, 3300.0, 0.72, c(226, 166, 96), 0.35),
                entry = EntryGlow(0.75, c(255, 150, 70)),
            )
        },
        "Luna" to { p -> p.copy(title = "AIRLESS REGOLITH", sky = p.sky.copy(ambient = c(62, 62, 66))) },
        "Mercury" to { p -> p.copy(title = "SCORCHED CRATER FIELDS", sky = p.sky.copy(brightness = 3.0, ambient = c(70, 66, 62))) },
        "Io" to { p -> p.copy(title = "SULFUR VOLCANIC PLAINS", sky = p.sky.copy(ambient = c(72, 66, 50))) },
        "Europa" to { p -> p.copy(title = "CRACKED ICE SHELL", sky = p.sky.copy(brightness = 2.2, ambient = c(70, 78, 86))) },
        "Ganymede" to { p -> p.copy(title = "GROOVED ICE TERRAIN", sky = p.sky.copy(ambient = c(66, 66, 70))) },
        "Callisto" to { p -> p.copy(title = "ANCIENT CRATER SHIELD", sky = p.sky.copy(ambient = c(60, 58, 56))) },
        "Triton" to { p -> p.copy(title = "FROZEN NITROGEN PLAINS", sky = p.sky.copy(brightness = 1.8, ambient = c(64, 62, 70))) },
        "Venus" to { p ->
            p.copy(
                title = "SCORCHED BASALT PLAINS",
                sky = p.sky.copy(
                    air = true, colour = c(232, 196, 128), decay = c(140, 96, 44), density = 0.55, haze = 8.0, glare = 0.0,
                    skybox = "haze", thickness = 40000.0, brightness = 1.5, ambient = c(128, 100, 60), nightAmbient = c(30, 20, 10),
                    stars = 0, zenith = c(214, 170, 96), dusk = c(170, 96, 40),
                ),
                clouds = CloudDeck(2600.0, 4200.0, 0.92, c(236, 206, 140), 0.45),
                entry = EntryGlow(1.0, c(255, 150, 60)),
            )
        },
    )

    val TITLES: Map<String, List<String>> = mapOf(
        "Rocky Body" to listOf("CRATERED HIGHLANDS", "CANYON MESAS", "WIND-SWEPT DUNES", "JAGGED RIDGELANDS"),
        "Ice World" to listOf("CRACKED ICE SHELL", "GLACIER RANGES", "PITTED ICE PLAINS"),
        "Volcanic Body" to listOf("LAVA FIELDS", "ASH PLAINS", "BASALT FLATS"),
        "Ocean World" to listOf("ARCHIPELAGO WORLD", "ENDLESS SEAS", "REEF SHALLOWS"),
        "Terrestrial World" to listOf("TEMPERATE WORLD", "GREEN LOWLANDS"),
        "Moon" to listOf("AIRLESS REGOLITH", "DUSTY BASINS", "BOULDER FIELDS"),
        "Gas Giant" to listOf("DRIFTING ICE FLOES", "AMMONIA ICE SHELVES"),
        "Ice Giant" to listOf("METHANE ICE FLOES", "FROZEN SLUSH SHELVES"),
        "Greenhouse World" to listOf("SCORCHED BASALT PLAINS"),
    )

    val CLOUDS: Map<String, CloudDeck> = mapOf(
        "Ocean World" to CloudDeck(2200.0, 3000.0, 0.62, c(255, 255, 255), 0.3),
        "Terrestrial World" to CloudDeck(2100.0, 2900.0, 0.52, c(255, 255, 255), 0.3),
        "Ice World" to CloudDeck(1900.0, 2600.0, 0.42, c(236, 244, 250), 0.22),
        "Volcanic Body" to CloudDeck(2000.0, 2900.0, 0.5, c(120, 104, 96), 0.45),
        "Rocky Body" to CloudDeck(1900.0, 2500.0, 0.25, c(232, 222, 210), 0.2),
        "Gas Giant" to CloudDeck(2600.0, 4400.0, 0.88, c(236, 214, 178), 0.5),
        "Ice Giant" to CloudDeck(2400.0, 4000.0, 0.8, c(214, 238, 244), 0.4),
        "Greenhouse World" to CloudDeck(2600.0, 4200.0, 0.92, c(236, 206, 140), 0.45),
    )

    /** `airFor`: the air of a generated body with an atmosphere. */
    fun airFor(profile: SurfaceProfile, bodyClass: String, atmosphereColour: Rgb?): SurfaceProfile {
        val colour = atmosphereColour ?: c(150, 180, 230)
        val blueish = colour.b > colour.r * 1.15
        val light = colour.lerp(c(255, 255, 255), 0.45)
        val sky = profile.sky.copy(
            air = true, colour = light, decay = scaled(colour, 0.5), density = 0.34, haze = if (blueish) 1.8 else 5.5,
            glare = 0.2, skybox = if (blueish) "day" else "haze", thickness = 26000.0, brightness = 2.4,
            ambient = colour.lerp(c(0, 0, 0), 0.45), nightAmbient = scaled(colour, 0.1), stars = 2400,
            zenith = colour.lerp(c(0, 0, 0), 0.25), dusk = c(232, 126, 80),
        )
        val base = CLOUDS[bodyClass] ?: CLOUDS.getValue("Rocky Body")
        val clouds = if (bodyClass != "Volcanic Body") base.copy(colour = base.colour.lerp(colour, 0.15)) else base.copy()
        return profile.copy(sky = sky, entry = EntryGlow(0.85, c(255, 140, 64)), clouds = clouds)
    }

    /**
     * `forBody`: Sol by name (only in Sol: [systemName] null or "Sol"), everything else by class and
     * seed (`titles[(seed mod n) + 1]`), plus `airFor` when the body has an atmosphere.
     */
    fun forBody(
        name: String, bodyClass: String? = null, seed: Int = 1, systemName: String? = null,
        hasAtmosphere: Boolean = false, atmosphereColour: Rgb? = null,
    ): SurfaceProfile {
        val cls = bodyClass ?: "Rocky Body"
        var profile = SurfaceProfile()
        val own = SOL[name]
        if (own != null && (systemName == null || systemName == "Sol")) {
            profile = own(profile)
        } else {
            val titles = TITLES[cls] ?: TITLES.getValue("Rocky Body")
            profile = profile.copy(title = titles[Math.floorMod(seed, titles.size)])
            if (hasAtmosphere) profile = airFor(profile, cls, atmosphereColour)
        }
        return profile.copy(name = name, bodyClass = cls, seed = seed)
    }
}
