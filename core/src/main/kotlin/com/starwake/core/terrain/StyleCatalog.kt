package com.starwake.core.terrain

/**
 * Colour sets and style selection (ShipSurfaceTerrain.lua:986-1150, §2.2.7): the Sol sets by hand,
 * the sets derived from the class palettes, `TITLE_STYLES`, `CLASS_STYLES`, `SOL_STYLES` and
 * `chooseStyle`. Every factory builds a fresh style (construction-time allocation only).
 */
object StyleCatalog {
    private fun c(r: Int, g: Int, b: Int) = Rgb.of(r, g, b)

    val EARTH = mapOf(
        "grass" to c(84, 124, 58), "lush" to c(62, 102, 44), "dirt" to c(104, 86, 64), "sand" to c(204, 190, 146),
        "mud" to c(84, 72, 56), "rock" to c(112, 108, 102), "cliff" to c(96, 92, 88), "snow" to c(238, 242, 248),
        "dry" to c(140, 140, 82),
    )
    val MARS = mapOf(
        "dust" to c(150, 80, 48), "sand" to c(176, 94, 54), "rock" to c(116, 66, 44), "dark" to c(66, 44, 36),
        "layered" to c(170, 102, 66), "pale" to c(194, 134, 96),
    )
    val TITAN = mapOf(
        "sand" to c(96, 64, 38), "plain" to c(86, 62, 42), "mud" to c(52, 40, 28), "ice" to c(196, 176, 136),
        "rock" to c(128, 108, 82), "dune" to c(122, 84, 48),
    )
    val LUNA = mapOf(
        "regolith" to c(132, 132, 128), "dust" to c(126, 126, 122), "rock" to c(98, 98, 96),
        "bright" to c(166, 166, 162), "mare" to c(100, 100, 98),
    )
    val MERCURY = mapOf(
        "regolith" to c(120, 112, 102), "dust" to c(114, 108, 100), "rock" to c(90, 86, 80),
        "bright" to c(152, 146, 138), "mare" to c(88, 84, 78),
    )
    val CALLISTO = mapOf(
        "regolith" to c(84, 76, 68), "dust" to c(78, 70, 62), "rock" to c(90, 84, 78),
        "bright" to c(196, 196, 192), "mare" to c(60, 54, 48),
    )
    val IO = mapOf(
        "sulfur" to c(214, 194, 84), "orange" to c(198, 118, 52), "pale" to c(232, 224, 168),
        "basalt" to c(44, 36, 32), "lava" to c(164, 66, 30), "rock" to c(96, 80, 60),
    )
    val EUROPA = mapOf(
        "ice" to c(212, 224, 232), "snow" to c(238, 242, 246), "glacier" to c(172, 198, 214),
        "crack" to c(150, 102, 80), "stain" to c(128, 98, 84),
    )
    val GANYMEDE = mapOf("ice" to c(196, 200, 204), "frost" to c(226, 228, 230), "dark" to c(98, 92, 86), "dust" to c(112, 104, 96))
    val TRITON = mapOf("frost" to c(232, 218, 214), "nitro" to c(212, 204, 208), "ice" to c(196, 204, 214), "streak" to c(90, 80, 78))
    val ASH = mapOf(
        "dust" to c(96, 94, 92), "sand" to c(116, 112, 108), "rock" to c(70, 66, 64), "dark" to c(40, 38, 38),
        "layered" to c(84, 80, 78), "pale" to c(150, 146, 140),
    )
    val VENUS = mapOf(
        "sulfur" to c(150, 118, 76), "orange" to c(128, 92, 58), "pale" to c(182, 156, 112),
        "basalt" to c(72, 58, 46), "lava" to c(150, 70, 34), "rock" to c(104, 86, 66),
    )

    fun temperateSet(p: ClassPalette) = mapOf(
        "grass" to p.low, "lush" to p.low.shade(0.82), "dirt" to p.low.lerp(p.high, 0.55), "sand" to p.shore,
        "mud" to p.high.shade(0.7), "rock" to p.high, "cliff" to p.high.shade(0.85), "snow" to p.ice,
        "dry" to p.low.lerp(p.shore, 0.45),
    )

    fun desertSet(p: ClassPalette) = mapOf(
        "dust" to p.low, "sand" to p.shore, "rock" to p.high, "dark" to p.high.shade(0.6),
        "layered" to p.shore.lerp(p.high, 0.5), "pale" to p.peak,
    )

    /** Little contrast: the image around is evenly grey from above. */
    fun moonSet(p: ClassPalette) = mapOf(
        "regolith" to p.low, "dust" to p.low.shade(0.95), "rock" to p.high, "bright" to p.peak, "mare" to p.high.shade(0.88),
    )

    fun volcanicSet(p: ClassPalette) = mapOf(
        "sulfur" to p.low, "orange" to p.high, "pale" to p.peak, "basalt" to p.shore, "lava" to c(190, 82, 34),
        "rock" to p.high.shade(0.8),
    )

    fun iceSet(p: ClassPalette) = mapOf(
        "ice" to p.low, "snow" to p.peak, "glacier" to p.shore, "crack" to p.high.lerp(c(150, 100, 80), 0.6),
        "stain" to p.shore.lerp(c(130, 100, 84), 0.5),
    )

    fun ridgeSet(p: ClassPalette) = mapOf("low" to p.low, "rock" to p.high, "peak" to p.ice, "scree" to p.high.shade(0.85), "sand" to p.shore)

    fun nitrogenSet(p: ClassPalette) = mapOf("frost" to p.peak, "nitro" to p.high, "ice" to p.low, "streak" to p.shore.shade(0.55))

    fun oceanSet(p: ClassPalette) = mapOf(
        "sand" to p.shore, "reef" to p.shore.lerp(c(220, 200, 170), 0.4), "grass" to p.low, "rock" to p.high, "lush" to p.low.shade(0.8),
    )

    fun floeSet(p: ClassPalette) = mapOf(
        "sea" to p.high.shade(0.5), "ice" to p.ice, "snow" to p.peak, "glacier" to p.low.lerp(p.high, 0.5), "slush" to p.shore,
    )

    /** By arrival title (ShipSurfaceProfiles): what the title says is what stands there. */
    val TITLE_STYLES: Map<String, (ClassPalette) -> TerrainStyle> = linkedMapOf(
        "CRATERED HIGHLANDS" to { p -> CrateredStyle(moonSet(p), dense = 1.1) },
        "CANYON MESAS" to { p -> DesertStyle(desertSet(p), dunes = 0.2, mesas = 1.3, canyons = 1.0, craters = 0.4) },
        "WIND-SWEPT DUNES" to { p -> DesertStyle(desertSet(p), dunes = 1.6, mesas = 0.2, craters = 0.3) },
        "JAGGED RIDGELANDS" to { p -> RidgesStyle(ridgeSet(p)) },
        "CRACKED ICE SHELL" to { p -> IcecrackStyle(iceSet(p)) },
        "GLACIER RANGES" to { p -> RidgesStyle(ridgeSet(p), icy = true, snowline = 160.0) },
        "PITTED ICE PLAINS" to { p -> NitrogenStyle(nitrogenSet(p)) },
        "LAVA FIELDS" to { p -> VolcanicStyle(volcanicSet(p), flows = 1.0) },
        "ASH PLAINS" to { _ -> DesertStyle(ASH, dunes = 0.8, mesas = 0.3, craters = 0.3) },
        "BASALT FLATS" to { p -> DesertStyle(desertSet(p), dunes = 0.0, mesas = 0.8, craters = 0.5) },
        "ARCHIPELAGO WORLD" to { p -> OceanStyle(oceanSet(p), bias = -4.0, amp = 34.0, hills = 90.0) },
        "ENDLESS SEAS" to { p -> OceanStyle(oceanSet(p), bias = -14.0, amp = 22.0, hills = 40.0, deep = true) },
        "REEF SHALLOWS" to { p -> OceanStyle(oceanSet(p), level = 8.0, bias = -3.0, amp = 10.0, hills = 20.0) },
        "TEMPERATE WORLD" to { p -> TemperateStyle(temperateSet(p)) },
        "GREEN LOWLANDS" to { p -> TemperateStyle(temperateSet(p), 12.0) },
        "AIRLESS REGOLITH" to { p -> CrateredStyle(moonSet(p)) },
        "DUSTY BASINS" to { p -> CrateredStyle(moonSet(p), basins = 1.0, dense = 0.6) },
        "BOULDER FIELDS" to { p -> CrateredStyle(moonSet(p), boulders = 3.0, dense = 0.7) },
        "SCORCHED BASALT PLAINS" to { p -> VolcanicStyle(volcanicSet(p), lava = 0.0, flows = 0.6) },
        "DRIFTING ICE FLOES" to { p -> FloesStyle(floeSet(p)) },
        "AMMONIA ICE SHELVES" to { p -> FloesStyle(floeSet(p), cover = 0.7) },
        "METHANE ICE FLOES" to { p -> FloesStyle(floeSet(p)) },
        "FROZEN SLUSH SHELVES" to { p -> FloesStyle(floeSet(p), cover = 0.7) },
    )

    /** Without a matching title: by class. */
    val CLASS_STYLES: Map<String, (ClassPalette) -> TerrainStyle> = linkedMapOf(
        "Terrestrial World" to TITLE_STYLES.getValue("TEMPERATE WORLD"),
        "Ocean World" to TITLE_STYLES.getValue("ARCHIPELAGO WORLD"),
        "Water World" to TITLE_STYLES.getValue("ENDLESS SEAS"),
        "Rocky Body" to TITLE_STYLES.getValue("CRATERED HIGHLANDS"),
        "Metal-Rich Body" to { p -> CrateredStyle(moonSet(p), dense = 1.2) },
        "High Metal Content" to { p -> CrateredStyle(moonSet(p), dense = 1.2) },
        "Volcanic Body" to TITLE_STYLES.getValue("LAVA FIELDS"),
        "Ice World" to TITLE_STYLES.getValue("CRACKED ICE SHELL"),
        "Ammonia World" to TITLE_STYLES.getValue("PITTED ICE PLAINS"),
        "Moon" to TITLE_STYLES.getValue("AIRLESS REGOLITH"),
        "Greenhouse World" to { p -> VolcanicStyle(volcanicSet(p), lava = 0.0) },
        "Gas Giant" to TITLE_STYLES.getValue("DRIFTING ICE FLOES"),
        "Ice Giant" to TITLE_STYLES.getValue("METHANE ICE FLOES"),
    )

    /** Sol by hand; applies only when the Sol profile applies (the title matches). */
    val SOL_STYLES: Map<String, Pair<String, () -> TerrainStyle>> = linkedMapOf(
        "Earth" to ("TEMPERATE WORLD" to { TemperateStyle(EARTH) }),
        "Mars" to ("RED DESERT" to { DesertStyle(MARS, craters = 0.8, mesas = 0.7) }),
        "Titan" to ("DUNE SEAS UNDER ORANGE HAZE" to { TitanStyle(TITAN) }),
        "Luna" to ("AIRLESS REGOLITH" to { CrateredStyle(LUNA) }),
        "Mercury" to ("SCORCHED CRATER FIELDS" to { CrateredStyle(MERCURY, scarps = 1.0, dense = 1.2) }),
        "Io" to ("SULFUR VOLCANIC PLAINS" to { VolcanicStyle(IO) }),
        "Europa" to ("CRACKED ICE SHELL" to { IcecrackStyle(EUROPA) }),
        "Ganymede" to ("GROOVED ICE TERRAIN" to { GroovedStyle(GANYMEDE) }),
        "Callisto" to ("ANCIENT CRATER SHIELD" to { CrateredStyle(CALLISTO, dense = 1.5) }),
        "Triton" to ("FROZEN NITROGEN PLAINS" to { NitrogenStyle(TRITON) }),
        "Venus" to ("SCORCHED BASALT PLAINS" to { VolcanicStyle(VENUS, lava = 0.0, flows = 0.6) }),
    )

    /**
     * `chooseStyle(body, profile)`: stars get none; the Sol style when the body name has one and
     * the profile title is the Sol one; else by title, else by class, else CRATERED HIGHLANDS, all
     * with the class palette. The returned style's [TerrainStyle.key] is set as in Lua.
     */
    fun chooseStyle(name: String?, bodyClass: String?, title: String?, isStar: Boolean = false): TerrainStyle? {
        val cls = bodyClass ?: "Rocky Body"
        if (isStar) return null
        val sol = if (name != null) SOL_STYLES[name] else null
        if (sol != null && sol.first == title) {
            val style = sol.second()
            style.key = name!!
            return style
        }
        val make = (if (title != null) TITLE_STYLES[title] else null) ?: CLASS_STYLES[cls] ?: TITLE_STYLES.getValue("CRATERED HIGHLANDS")
        val style = make(Palettes.colours(cls))
        style.key = title ?: cls
        return style
    }

    /** chooseStyle for a profile (name, class and title from it). */
    fun chooseStyle(profile: SurfaceProfile): TerrainStyle? = chooseStyle(profile.name, profile.bodyClass, profile.title)
}
