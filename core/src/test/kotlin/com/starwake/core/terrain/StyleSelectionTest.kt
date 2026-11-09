package com.starwake.core.terrain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Surface profiles, palettes, colour sets and the style selection tables (04 §2.2.7, §2.6). */
class StyleSelectionTest {
    private fun rgb(c: Rgb) = c.to255()

    /** 04 §2.2.7 "What every Sol landable body gets". */
    @Test
    fun solBodies() {
        data class Row(val name: String, val cls: String, val seed: Int, val title: String, val family: String, val key: String)
        val rows = listOf(
            Row("Mercury", "Rocky Body", 1, "SCORCHED CRATER FIELDS", "cratered", "Mercury"),
            Row("Venus", "Greenhouse World", 2, "SCORCHED BASALT PLAINS", "volcanic", "Venus"),
            Row("Earth", "Terrestrial World", 3, "TEMPERATE WORLD", "temperate", "Earth"),
            Row("Mars", "Rocky Body", 4, "RED DESERT", "desert", "Mars"),
            Row("Luna", "Moon", 94, "AIRLESS REGOLITH", "cratered", "Luna"),
            Row("Phobos", "Moon", 125, "BOULDER FIELDS", "cratered", "BOULDER FIELDS"),
            Row("Deimos", "Moon", 126, "AIRLESS REGOLITH", "cratered", "AIRLESS REGOLITH"),
            Row("Io", "Moon", 156, "SULFUR VOLCANIC PLAINS", "volcanic", "Io"),
            Row("Europa", "Moon", 157, "CRACKED ICE SHELL", "icecrack", "Europa"),
            Row("Ganymede", "Moon", 158, "GROOVED ICE TERRAIN", "grooved", "Ganymede"),
            Row("Callisto", "Moon", 159, "ANCIENT CRATER SHIELD", "cratered", "Callisto"),
            Row("Titan", "Moon", 187, "DUNE SEAS UNDER ORANGE HAZE", "titan", "Titan"),
            Row("Triton", "Moon", 249, "FROZEN NITROGEN PLAINS", "nitrogen", "Triton"),
        )
        for (r in rows) {
            val p = SurfaceProfiles.forBody(r.name, r.cls, r.seed, "Sol")
            assertEquals(r.title, p.title, r.name)
            val s = StyleCatalog.chooseStyle(p)!!
            assertEquals(r.family, s.family, r.name)
            assertEquals(r.key, s.key, r.name)
        }
        // water levels: Earth 8, Titan 8; parameters of the hand-made styles
        val earth = StyleCatalog.chooseStyle(SurfaceProfiles.forBody("Earth", "Terrestrial World", 3, "Sol"))!!
        assertEquals(8.0, earth.water)
        assertTrue(earth.usesTone)
        val mars = StyleCatalog.chooseStyle(SurfaceProfiles.forBody("Mars", "Rocky Body", 4, "Sol")) as DesertStyle
        assertEquals(0.8, mars.craters)
        assertEquals(0.7, mars.mesas)
        assertEquals(1.0, mars.dunes)
        val phobos = StyleCatalog.chooseStyle(SurfaceProfiles.forBody("Phobos", "Moon", 125, "Sol")) as CrateredStyle
        assertEquals(3.0, phobos.boulders)
        assertEquals(0.7, phobos.dense)
        assertEquals(5, phobos.layers!!.size, "boulders > 1 adds the 140 m boulder layer")
        val mercury = StyleCatalog.chooseStyle(SurfaceProfiles.forBody("Mercury", "Rocky Body", 1, "Sol")) as CrateredStyle
        assertEquals(1.0, mercury.scarps)
        assertEquals(1.2, mercury.dense)
    }

    @Test
    fun solProfilesOnlyInSol() {
        // a generated body named Earth gets the generated rule, not the Sol profile
        val p = SurfaceProfiles.forBody("Earth", "Rocky Body", 5, "Kepler")
        assertEquals("CANYON MESAS", p.title, "titles[(5 mod 4) + 1]")
        assertEquals("desert", StyleCatalog.chooseStyle(p)!!.family)
        assertTrue(!p.sky.air)
    }

    @Test
    fun generatedTitlesBySeed() {
        val rocky = SurfaceProfiles.TITLES.getValue("Rocky Body")
        for (seed in 0 until 12) {
            val p = SurfaceProfiles.forBody("X", "Rocky Body", seed, "Kepler")
            assertEquals(rocky[seed % 4], p.title)
        }
        assertEquals("CRATERED HIGHLANDS", SurfaceProfiles.forBody("X", "Unknown Class", 4, "Kepler").title, "unknown class → Rocky list")
        assertEquals("ROCKY SURFACE", SurfaceProfile().title)
    }

    @Test
    fun selectionTables() {
        assertEquals(23, StyleCatalog.TITLE_STYLES.size)
        assertEquals(13, StyleCatalog.CLASS_STYLES.size)
        assertEquals(11, StyleCatalog.SOL_STYLES.size)
        val expect = mapOf(
            "CRATERED HIGHLANDS" to "cratered", "CANYON MESAS" to "desert", "WIND-SWEPT DUNES" to "desert",
            "JAGGED RIDGELANDS" to "ridges", "CRACKED ICE SHELL" to "icecrack", "GLACIER RANGES" to "ridges",
            "PITTED ICE PLAINS" to "nitrogen", "LAVA FIELDS" to "volcanic", "ASH PLAINS" to "desert", "BASALT FLATS" to "desert",
            "ARCHIPELAGO WORLD" to "ocean", "ENDLESS SEAS" to "ocean", "REEF SHALLOWS" to "ocean", "TEMPERATE WORLD" to "temperate",
            "GREEN LOWLANDS" to "temperate", "AIRLESS REGOLITH" to "cratered", "DUSTY BASINS" to "cratered",
            "BOULDER FIELDS" to "cratered", "SCORCHED BASALT PLAINS" to "volcanic", "DRIFTING ICE FLOES" to "floes",
            "AMMONIA ICE SHELVES" to "floes", "METHANE ICE FLOES" to "floes", "FROZEN SLUSH SHELVES" to "floes",
        )
        for ((title, family) in expect) {
            val s = StyleCatalog.chooseStyle("Nobody", "Rocky Body", title)!!
            assertEquals(family, s.family, title)
            assertEquals(title, s.key)
        }
        // parameters
        val canyon = StyleCatalog.chooseStyle("N", "Rocky Body", "CANYON MESAS") as DesertStyle
        assertEquals(1.0, canyon.canyons); assertEquals(1.3, canyon.mesas); assertEquals(0.2, canyon.dunes); assertEquals(0.4, canyon.craters)
        val glacier = StyleCatalog.chooseStyle("N", "Ice World", "GLACIER RANGES") as RidgesStyle
        assertTrue(glacier.icy); assertEquals(160.0, glacier.snowline)
        val seas = StyleCatalog.chooseStyle("N", "Ocean World", "ENDLESS SEAS") as OceanStyle
        assertTrue(seas.deep); assertEquals(-14.0, seas.bias); assertEquals(22.0, seas.amp)
        val reef = StyleCatalog.chooseStyle("N", "Ocean World", "REEF SHALLOWS") as OceanStyle
        assertEquals(8.0, reef.water)
        assertEquals(12.0, StyleCatalog.chooseStyle("N", "Terrestrial World", "GREEN LOWLANDS")!!.water)
        // by class when the title matches nothing; stars none; default CRATERED HIGHLANDS
        assertEquals("volcanic", StyleCatalog.chooseStyle("N", "Greenhouse World", "NO SUCH TITLE")!!.family)
        assertEquals("Greenhouse World", StyleCatalog.chooseStyle("N", "Greenhouse World", null)!!.key)
        assertEquals("cratered", StyleCatalog.chooseStyle("N", "Metal-Rich Body", null)!!.family)
        assertEquals("cratered", StyleCatalog.chooseStyle("N", "Weird", null)!!.family)
        assertNull(StyleCatalog.chooseStyle("Sol", "Star G", null, isStar = true))
        // the Sol style only when the title is the Sol one
        assertEquals("cratered", StyleCatalog.chooseStyle("Earth", "Moon", "AIRLESS REGOLITH")!!.family)
    }

    /** 04 §2.2.7 resolved colours of the derived sets (computed values, RGB). */
    @Test
    fun derivedColourSets() {
        val terr = StyleCatalog.temperateSet(Palettes.colours("Terrestrial World"))
        assertEquals("74,116,56", rgb(terr.getValue("grass")))
        assertEquals("61,95,46", rgb(terr.getValue("lush")))
        assertEquals("93,105,66", rgb(terr.getValue("dirt")))
        assertEquals("76,67,52", rgb(terr.getValue("mud")))
        assertEquals("92,82,63", rgb(terr.getValue("cliff")))
        assertEquals("129,146,93", rgb(terr.getValue("dry")))
        val moon = StyleCatalog.moonSet(Palettes.colours("Moon"))
        assertEquals("148,144,138", rgb(moon.getValue("regolith")))
        assertEquals("141,137,131", rgb(moon.getValue("dust")))
        assertEquals("92,88,84", rgb(moon.getValue("mare")))
        val ice = StyleCatalog.iceSet(Palettes.colours("Ice World"))
        assertEquals("176,153,145", rgb(ice.getValue("crack")))
        assertEquals("153,148,147", rgb(ice.getValue("stain")))
        val volc = StyleCatalog.volcanicSet(Palettes.colours("Volcanic Body"))
        assertEquals("96,42,24", rgb(volc.getValue("rock")))
        val nitro = StyleCatalog.nitrogenSet(Palettes.colours("Ice World"))
        assertEquals("97,108,116", rgb(nitro.getValue("streak")))
        val floe = StyleCatalog.floeSet(Palettes.colours("Gas Giant"))
        assertEquals("82,64,45", rgb(floe.getValue("sea")))
        assertEquals("189,158,119", rgb(floe.getValue("glacier")))
        val ocean = StyleCatalog.oceanSet(Palettes.colours("Ocean World"))
        assertEquals("202,188,155", rgb(ocean.getValue("reef")))
        assertEquals("53,88,48", rgb(ocean.getValue("lush")))
    }

    @Test
    fun profiles() {
        val earth = SurfaceProfiles.forBody("Earth", "Terrestrial World", 3, "Sol")
        assertTrue(earth.sky.air)
        assertEquals(26000.0, earth.sky.thickness)
        assertEquals(0.32, earth.sky.density)
        assertEquals(2100.0, earth.clouds!!.bottom)
        assertEquals(2900.0, earth.clouds!!.top)
        val luna = SurfaceProfiles.forBody("Luna", "Moon", 94, "Sol")
        assertTrue(!luna.sky.air)
        assertEquals("62,62,66", rgb(luna.sky.ambient))
        assertNull(luna.clouds)
        val venus = SurfaceProfiles.forBody("Venus", "Greenhouse World", 2, "Sol")
        assertEquals(0, venus.sky.stars)
        assertEquals(40000.0, venus.sky.thickness)
        // airFor: a blueish atmosphere gets the day sky, others the haze sky
        val blue = SurfaceProfiles.forBody("X", "Terrestrial World", 7, "Kepler", true, Rgb.of(110, 150, 255))
        assertEquals("day", blue.sky.skybox)
        assertEquals(1.8, blue.sky.haze)
        val red = SurfaceProfiles.forBody("X", "Rocky Body", 7, "Kepler", true, Rgb.of(215, 150, 110))
        assertEquals("haze", red.sky.skybox)
        assertEquals(5.5, red.sky.haze)
        assertEquals(1900.0, red.clouds!!.bottom)
        val volcanic = SurfaceProfiles.forBody("X", "Volcanic Body", 7, "Kepler", true, Rgb.of(215, 150, 110))
        assertEquals("120,104,96", rgb(volcanic.clouds!!.colour), "volcanic decks keep their colour")
    }
}
