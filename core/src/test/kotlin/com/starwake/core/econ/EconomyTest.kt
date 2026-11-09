package com.starwake.core.econ

import com.starwake.core.content.ShipCatalog
import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.station.SolWorld
import com.starwake.core.ui.TextBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ShipEconomy: Appendix A.1 (hashes, drift, prices), A.2 (survey values), A.6 (formatting), the ship catalogue. */
class EconomyTest {
    // ---------------------------------------------------------------- A.1 hashes and drift
    @Test
    fun hash01Vectors() {
        assertEquals(0.7119186424407268, Economy.hash01("SOL-MERIDIAN|Water|1500000"))
        assertEquals(0.8015859524214274, Economy.hash01("abc"))
        assertEquals(0.05380838574842755, Economy.hash01(""))
    }

    @Test
    fun hashesStreamUtf8Bytes() {
        // "·" (U+00B7) is two UTF-8 bytes C2 B7, as Lua's string.byte sees it
        var h = 5381L
        for (b in byteArrayOf(0xC2.toByte(), 0xB7.toByte())) h = (h * 33 + (b.toInt() and 0xFF)) % 4294967291L
        assertEquals((h % 100003).toDouble() / 100003, Economy.hash01("·"))
        // integers print without ".0" (Lua tostring of a whole number)
        assertEquals(Economy.hash01("SOL-MERIDIAN|Water|1500000"), Economy.hash01(StringBuilder("SOL-MERIDIAN|Water|").append(1500000L)))
    }

    @Test
    fun driftVectors() {
        assertEquals(0.025430237092887213, Economy.drift("SOL-MERIDIAN", "Water", 1.8e9), 1e-15)
        // within a step the drift blends with smoothstep; the values from the Python transcription of the Lua
        assertEquals(0.025430837074887754, Economy.drift("SOL-MERIDIAN", "Water", 1.8e9 + 600), 1e-15)
        assertEquals(-0.005744989953449861, Economy.drift("SOL-MERIDIAN", "Water", 1_700_000_123.0), 1e-15)
        // never more than ±6 %
        for (t in 0 until 200) {
            val d = Economy.drift("SOL-ARES", "Copper", 1.7e9 + t * 977.0)
            assertTrue(d >= -0.06 && d <= 0.06, "drift $d")
        }
    }

    @Test
    fun roundingIsHalfAwayFromZero() {
        assertEquals(1.0, Lua.round(0.5))
        assertEquals(2.0, Lua.round(1.5))
        assertEquals(3.0, Lua.round(2.5)) // kotlin.math.round would give 2
        assertEquals(-1.0, Lua.round(-0.5))
        assertEquals(-3.0, Lua.round(-2.5))
        assertEquals(0.0, Lua.round(0.49999999999999994)) // floor(x + 0.5) would give 1
        assertEquals(68.0, Lua.round(67.5))
        assertEquals(4.0, Lua.round(4.4999))
    }

    // ---------------------------------------------------------------- A.1 prices at now = 1,800,000,000
    private val meridian = listOf(
        "Water" to "169 156 LOW HIGH",
        "HydrogenFuel" to "146 134 MED MED",
        "Grain" to "357 329 LOW HIGH",
        "SyntheticMeat" to "417 385 LOW HIGH",
        "ConstructionAlloys" to "545 503 MED MED",
        "Copper" to "869 802 LOW HIGH",
        "ConsumerGoods" to "892 824 LOW HIGH",
        "MedicalSupplies" to "812 750 HIGH LOW",
        "Titanium" to "1499 1383 LOW HIGH",
        "RoboticsParts" to "1171 1080 HIGH LOW",
        "Microprocessors" to "1405 1297 HIGH LOW",
        "RareIsotopes" to "3315 3060 LOW HIGH",
        "WaterIce" to "92 85 MED MED",
        "MineralOre" to "163 151 MED MED",
        "MetalOre" to "195 180 MED MED",
        "Helium3" to "899 830 LOW HIGH",
        "PreciousOre" to "1422 1313 LOW HIGH",
    )

    private fun priceText(station: String, economy: String, id: String, now: Long = NOW): String {
        val p = Price()
        assertTrue(Economy.price(station, economy, id, now.toDouble(), p))
        return "${p.buy} ${p.sell} ${Economy.LEVEL_NAMES[p.supply]} ${Economy.LEVEL_NAMES[p.demand]}"
    }

    @Test
    fun meridianPricesA1() {
        for ((id, expected) in meridian) assertEquals(expected, priceText("SOL-MERIDIAN", "HighTech", id), id)
    }

    @Test
    fun aresPricesA1() {
        val ares = listOf(
            "MetalOre" to "145 134", "MineralOre" to "119 110", "WaterIce" to "70 65",
            "Helium3" to "698 644", "PreciousOre" to "909 839", "Titanium" to "781 721",
        )
        for ((id, expected) in ares) assertEquals(expected, priceText("SOL-ARES", "Extraction", id).substringBeforeLast(' ').substringBeforeLast(' '), id)
        assertEquals("698 644 MED MED", priceText("SOL-ARES", "Extraction", "Helium3"))
        assertEquals("145 134 HIGH LOW", priceText("SOL-ARES", "Extraction", "MetalOre"))
    }

    @Test
    fun otherMomentsAndFallbacks() {
        assertEquals("169 156 LOW HIGH", priceText("SOL-MERIDIAN", "HighTech", "Water", NOW + 600))
        assertEquals("164 151 LOW HIGH", priceText("SOL-MERIDIAN", "HighTech", "Water", 1_700_000_123L))
        // an unknown economy prices like Industrial, an unknown commodity has no price
        assertEquals(priceText("X", "Industrial", "Copper"), priceText("X", "Nonsense", "Copper"))
        assertFalse(Economy.price("X", "Industrial", "Unobtainium", NOW.toDouble(), Price()))
        // a round trip at one station always loses money
        for (c in Economy.COMMODITIES) {
            val p = Price()
            Economy.price("SOL-TITAN", "Agricultural", c, NOW.toDouble(), p)
            assertTrue(p.buy > p.sell && p.sell >= 1, c.id)
        }
    }

    @Test
    fun marketFillsAllRowsInOrder() {
        val m = Market()
        assertNull(m.bestSell())
        Economy.market("SOL-MERIDIAN", "HighTech", NOW, m)
        assertTrue(m.valid)
        assertEquals(17, m.rows.size)
        for (i in meridian.indices) {
            val r = m.rows[i]
            assertEquals(meridian[i].first, r.commodity.id)
            assertEquals(meridian[i].second, "${r.buy} ${r.sell} ${Economy.LEVEL_NAMES[r.supply]} ${Economy.LEVEL_NAMES[r.demand]}")
        }
        // RARE ISOTOPES pays most at MERIDIAN (3060)
        assertEquals("RareIsotopes", m.bestSell()!!.commodity.id)
        assertEquals("Water", m.row("Water")!!.commodity.id)
        assertNull(m.row("Nope"))
    }

    @Test
    fun commoditiesAndEconomiesAsInTheLua() {
        val bases = intArrayOf(120, 140, 260, 340, 540, 680, 720, 920, 1060, 1420, 1820, 2650, 90, 160, 190, 700, 1100)
        for (i in bases.indices) {
            assertEquals(bases[i], Economy.COMMODITIES[i].base)
            assertEquals(i, Economy.COMMODITIES[i].index)
        }
        assertEquals("CONSUMER ITEMS", Economy.commodity("ConsumerGoods")!!.category)
        assertEquals("HIGH TECH", Economy.economyName("HighTech"))
        assertEquals("TOURISM", Economy.economyName("Service"))
        assertEquals("INDUSTRIAL", Economy.economyName("whatever"))
        assertEquals(1.0, Economy.economy("Service")!!.factor(Economy.commodityIndex("Copper")))
        assertEquals(0.86, Economy.economy("Service")!!.factor(Economy.commodityIndex("ConsumerGoods")))
        assertEquals(1.32, Economy.economy("Refinery")!!.factor(Economy.commodityIndex("MetalOre")))
    }

    // ---------------------------------------------------------------- A.2 survey values
    @Test
    fun solSurveyValuesA2() {
        val expected = mapOf(
            "Sol" to 680L, "Mercury" to 320L, "Venus" to 1150L, "Earth" to 4320L, "Mars" to 320L, "Jupiter" to 1050L,
            "Saturn" to 1050L, "Uranus" to 930L, "Neptune" to 690L,
        )
        val lochran = mapOf(
            "Sol" to 1020L, "Mercury" to 480L, "Venus" to 1730L, "Earth" to 6480L, "Mars" to 480L, "Jupiter" to 1580L,
            "Saturn" to 1580L, "Uranus" to 1400L, "Neptune" to 1040L,
        )
        for (b in SolWorld.BODIES) {
            val v = Economy.surveyValue(b.cls, b.starClass, b.ring, b.landable, home = true)
            val want = expected[b.name] ?: 180L // every Sol moon
            assertEquals(want, v, b.name)
            assertEquals(lochran[b.name] ?: 270L, Economy.scaleValue(v, Economy.perk("Lochran", Economy.PERK_SURVEY_VALUE)), b.name)
        }
    }

    @Test
    fun surveyValuesOutsideSolA2() {
        val terrestrial = Economy.surveyValue("Terrestrial World", null, ring = false, landable = true, home = false)
        assertEquals(14_400L, terrestrial)
        assertEquals(21_600L, Economy.scaleValue(terrestrial, Economy.SURVEY_FIRST_BONUS))
        assertEquals(32_400L, Economy.scaleValue(Economy.scaleValue(terrestrial, 1.5), Economy.SURVEY_FIRST_BONUS))
        assertEquals(7_200L, Economy.surveyValue("Star", "O", ring = false, landable = false, home = false))
        assertEquals(3_510L, Economy.surveyValue("Gas Giant", null, ring = true, landable = false, home = false))
        // unknown class: 800; an unknown spectral class: factor 1; a star without one: G
        assertEquals(800L, Economy.surveyValue("Ammonia Swamp", null, ring = false, landable = false, home = false))
        assertEquals(1800L, Economy.surveyValue("Star", "X", ring = false, landable = false, home = false))
        assertEquals(2250L, Economy.surveyValue("Star", null, ring = false, landable = false, home = false))
        // rounded to tens: a bare Sol moon is 500 · 0.3
        assertEquals(150L, Economy.surveyValue("Moon", null, ring = false, landable = false, home = true))
    }

    // ---------------------------------------------------------------- ships
    @Test
    fun shipCatalogueMatchesTheLuaAndShipCatalog() {
        val ids = listOf("Varrow", "Torrin", "Calder", "Lochran", "Brannock", "Kessock", "Dornoch", "Morven")
        val prices = listOf(22_000, 26_000, 31_000, 39_000, 55_000, 95_000, 140_000, 265_000)
        val cargo = listOf(16, 4, 12, 8, 64, 96, 64, 224)
        val mass = listOf(370, 275, 363, 356, 760, 930, 1086, 1640)
        assertEquals(ShipCatalog.all.size, Economy.SHIPS.size)
        for (i in ids.indices) {
            val s = Economy.SHIPS[i]
            assertEquals(ids[i], s.id)
            assertEquals(ShipCatalog.all[i].id, s.id.lowercase(), "same order as ShipCatalog")
            assertEquals(ShipCatalog.all[i].name, s.name)
            assertEquals(prices[i], s.price)
            assertEquals(cargo[i], s.cargoCapacity)
            assertEquals(mass[i], s.designMass)
            assertFalse(s.testOnly, "DECISIONS 18: all eight are for sale")
            assertTrue(s.description.isNotEmpty() && s.description == s.description.uppercase())
            assertEquals(i, Economy.shipIndex(s.id))
        }
        assertEquals("LONG-RANGE EXPLORER", Economy.ship("Lochran")!!.role)
        assertEquals(1.5, Economy.perk("Lochran", Economy.PERK_SURVEY_VALUE))
        assertEquals(1.5, Economy.perk("Lochran", Economy.PERK_SCAN_RANGE))
        assertEquals(0.6, Economy.perk("Lochran", Economy.PERK_FUEL_BURN))
        assertEquals(2.0, Economy.perk("Kessock", Economy.PERK_MINE_SPEED))
        assertEquals(1.25, Economy.perk("Dornoch", Economy.PERK_SCAN_RANGE))
        assertEquals(0.85, Economy.perk("Torrin", Economy.PERK_FUEL_BURN))
        assertEquals(1.0, Economy.perk("Varrow", Economy.PERK_MINE_SPEED))
        assertEquals(1.0, Economy.perk("Nope", Economy.PERK_MINE_SPEED))
        assertEquals(50, Economy.perkPercent("Lochran", Economy.PERK_SURVEY_VALUE))
        assertEquals(-40, Economy.perkPercent("Lochran", Economy.PERK_FUEL_BURN))
        assertEquals(0, Economy.perkPercent("Varrow", Economy.PERK_SCAN_RANGE))
    }

    // ---------------------------------------------------------------- A.6 formatting and the refuel preview
    @Test
    fun groupDigitsA6() {
        assertEquals("1,500", Economy.groupDigits(1500.0))
        assertEquals("999,999", Economy.groupDigits(999_999.0))
        assertEquals("22,000", Economy.groupDigits(22_000.0))
        assertEquals("0", Economy.groupDigits(0.4))
        assertEquals("-1,234,567", Economy.groupDigits(-1_234_567.0))
        assertEquals("1,000", Economy.groupDigits(999.5))
        assertEquals("100", Economy.groupDigits(100L))
        assertEquals("0", Economy.groupDigits(-0.4), "DECISIONS 35: no \"-0\"")
        assertEquals("1,500 CR", Economy.formatCredits(1500L))
        assertEquals("265,000 CR", Economy.formatCredits(265_000.0))
        val buf = TextBuf(32)
        assertEquals("12,345,678 CR", Economy.appendCredits(buf, 12_345_678.4).toString())
    }

    @Test
    fun refuelPreviewA6() {
        val q = Economy.RefuelQuote()
        Economy.refuelQuote(0.37, 1000, q)
        assertEquals(63.0, q.missing, 1e-9)
        assertEquals(63.0, q.units, 1e-9)
        assertEquals(252L, q.cost)
        assertTrue(q.affordable)
        Economy.refuelQuote(0.0, 150, q)
        assertEquals(37.0, q.units)
        assertEquals(148L, q.cost)
        Economy.refuelQuote(0.996, 1000, q)
        assertTrue(q.full)
        assertFalse(q.affordable)
        Economy.refuelQuote(0.5, 3, q)
        assertFalse(q.full)
        assertFalse(q.affordable)
    }

    @Test
    fun refuelPreviewTopsUpTheLastUnitDecisions35() {
        // the review's case: 0.502 units missing, plenty of credits; Roblox showed "NO CREDITS"
        val q = Economy.RefuelQuote()
        Economy.refuelQuote(0.99498, 81_767, q)
        assertFalse(q.full)
        assertEquals(0.502, q.missing, 1e-9)
        assertTrue(q.affordable)
        assertEquals(3L, q.cost)
        Economy.refuelQuote(0.992, 4, q)
        assertTrue(q.affordable)
        assertEquals(4L, q.cost)
        Economy.refuelQuote(0.992, 3, q)
        assertFalse(q.affordable, "3 CR do not buy a unit")
        assertEquals(0L, q.cost)
    }
}
