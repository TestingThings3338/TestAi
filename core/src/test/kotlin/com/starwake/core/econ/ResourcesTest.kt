package com.starwake.core.econ

import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.ScriptedFactory
import com.starwake.core.econ.EconFixtures.ScriptedRandom
import com.starwake.core.math.DVec3
import com.starwake.core.station.BodyInfo
import com.starwake.core.station.SolWorld
import com.starwake.core.station.StationCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ShipResources: Appendix A.3 (Sol deposits) and A.5 (slots), the build rules, storage, names. */
class ResourcesTest {
    private fun body(name: String): BodyInfo = SolWorld.BODIES.first { it.name == name }

    private fun list(vararg d: Pair<String, Int>) = d.map { Deposit(it.first, it.second) }

    // ---------------------------------------------------------------- A.3
    @Test
    fun solDepositsA3() {
        // body: sorted deposits, CR/h, letter, trait, economy, best by hand
        val table = listOf(
            listOf("Mercury", "PreciousOre 2, MetalOre 5, MineralOre 3", "7640", "A", "METAL-RICH", "Extraction", "PreciousOre"),
            listOf("Venus", "MineralOre 3, MetalOre 2", "1720", "D", "MINERAL DEPOSITS", "Extraction", "MineralOre"),
            listOf("Earth", "", "0", "-", "INHABITED", "Service", "-"),
            listOf("Luna", "Helium3 2, MineralOre 3, MetalOre 1", "4140", "B", "MIXED DEPOSITS", "Refinery", "Helium3"),
            listOf("Mars", "MetalOre 3, MineralOre 3, WaterIce 2", "2460", "D", "MIXED DEPOSITS", "Extraction", "MetalOre"),
            listOf("Phobos", "MetalOre 2, MineralOre 2", "1400", "D", "METAL DEPOSITS", "Extraction", "MetalOre"),
            listOf("Deimos", "MineralOre 2, WaterIce 1", "820", "E", "POOR DEPOSITS", "Extraction", "MineralOre"),
            listOf("Jupiter", "Helium3 4", "6300", "A", "HELIUM-3 RICH", "Refinery", "Helium3"),
            listOf("Io", "PreciousOre 1, MineralOre 4, MetalOre 2", "4400", "B", "MINERAL-RICH", "Extraction", "PreciousOre"),
            listOf("Europa", "WaterIce 5, MineralOre 1", "1400", "D", "ICE-RICH", "Extraction", "WaterIce"),
            listOf("Ganymede", "WaterIce 4, MineralOre 2, MetalOre 1", "1830", "D", "ICE-RICH", "Extraction", "MineralOre"),
            listOf("Callisto", "MineralOre 2, WaterIce 3", "1180", "E", "POOR DEPOSITS", "Extraction", "MineralOre"),
            listOf("Saturn", "Helium3 4", "6300", "A", "HELIUM-3 RICH", "Refinery", "Helium3"),
            listOf("Titan", "Helium3 1, MineralOre 2, WaterIce 3", "2580", "C", "MIXED DEPOSITS", "Refinery", "Helium3"),
            listOf("Uranus", "Helium3 4, WaterIce 3", "6840", "A", "HELIUM-3 RICH", "Refinery", "Helium3"),
            listOf("Neptune", "Helium3 3, WaterIce 3", "4740", "B", "HELIUM-3 CLOUDS", "Refinery", "Helium3"),
            listOf("Triton", "WaterIce 4, MineralOre 1", "1130", "E", "ICE-RICH", "Extraction", "WaterIce"),
        )
        for (row in table) {
            val name = row[0]
            val deposits = Resources.roll(1, name, body(name))
            assertEquals(row[1], deposits.joinToString(", "), name)
            val hourly = Resources.hourly(deposits)
            assertEquals(row[2].toInt(), hourly, name)
            assertEquals(row[3], Resources.letter(hourly), name)
            assertEquals(row[4], Resources.trait(deposits, Resources.isInhabited(1, name)), name)
            assertEquals(row[5], Resources.economyFor(deposits), name)
            assertEquals(row[6], Resources.bestToMine(deposits)?.id ?: "-", name)
        }
        assertTrue(Resources.isInhabited(1, "Earth"))
        assertFalse(Resources.isInhabited(2, "Earth"))
        assertFalse(Resources.isInhabited(1, "Mars"))
    }

    @Test
    fun ratingsEdgeCases() {
        assertEquals("NO DEPOSITS", Resources.trait(emptyList(), inhabited = false))
        assertEquals("E", Resources.letter(1))
        assertEquals("D", Resources.letter(1200))
        assertEquals("-", Resources.letter(0))
        assertNull(Resources.bestToMine(emptyList()))
        assertEquals(Deposit("MetalOre", 3), Resources.findDeposit(list("MetalOre" to 3), "MetalOre"))
        assertNull(Resources.findDeposit(list("MetalOre" to 3), "WaterIce"))
        // sort: by CR/h, then resource order on ties (MetalOre 3 = 1140 vs ...)
        val tie = Resources.sort(arrayListOf(Deposit("MineralOre", 1), Deposit("MetalOre", 1)))
        assertEquals("MetalOre 1, MineralOre 1", tie.joinToString(", "))
        assertEquals("POOR", Resources.gradeName(1))
        assertEquals("EXCEPTIONAL", Resources.gradeName(5))
        assertEquals("", Resources.gradeName(6))
    }

    @Test
    fun seedOfIsDjb2() {
        assertEquals(193_485_963L, Resources.seedOf("abc"))
        assertEquals(1_787_268_212L, Resources.seedOf("2/Kepler b"))
    }

    // ---------------------------------------------------------------- rolled deposits
    @Test
    fun rollDrawsInTheLuaOrder() {
        // a moon: type first, then one draw per range (even a missing one), then the bonanza
        val r = ScriptedRandom(0.0, 0.0, 0.0, 0.5, 0.01)
        val f = ScriptedFactory(r)
        val moon = BodyInfo("Kepler b I", BodyInfo.MOON, landable = true, radius = 300.0, parent = "Kepler b")
        val deposits = Resources.roll(2, "Kepler b I", moon, f)
        assertEquals(listOf(Resources.seedOf("2/Kepler b I")), f.seeds)
        assertEquals(listOf("NextInteger(1,3)", "NextNumber()", "NextNumber()", "NextNumber()"), r.log)
        // type 1 (ice): WaterIce 1..5 with u 0 -> 1, MineralOre 0..2 with u 0 -> 0 (missing); bonanza 0.5 >= 0.08: none
        assertEquals("WaterIce 1", deposits.joinToString(", "))

        // a terrestrial world: three ranges and a bonanza (u < 0.08) on the top deposit.
        // 0.99^1.35 = 0.9865: MineralOre 3 (6·160 = 960 CR/h), MetalOre 2 (760), WaterIce 2 (360); the bonanza lifts MineralOre
        val r2 = ScriptedRandom(0.99, 0.99, 0.99, 0.0)
        val world = BodyInfo("Kepler b", "Terrestrial World", landable = true, radius = 1500.0)
        val d2 = Resources.roll(2, "Kepler b", world, ScriptedFactory(r2))
        assertEquals(listOf("NextNumber()", "NextNumber()", "NextNumber()", "NextNumber()"), r2.log)
        assertEquals("MineralOre 4, MetalOre 2, WaterIce 2", d2.joinToString(", "))

        // the bonanza never goes past EXCEPTIONAL
        val r3 = ScriptedRandom(0.999, 0.0)
        val giant = Resources.roll(2, "Kepler c", BodyInfo("Kepler c", "Gas Giant", radius = 7000.0), ScriptedFactory(r3))
        assertEquals(listOf("NextNumber()", "NextNumber()"), r3.log)
        assertEquals("Helium3 5", giant.joinToString(", "))

        // stars have none; Sol comes from the table and never uses the dice
        val unused = ScriptedFactory(ScriptedRandom())
        assertTrue(Resources.roll(2, "Kepler", BodyInfo("Kepler", BodyInfo.STAR, radius = 1.0), unused).isEmpty())
        assertTrue(Resources.roll(1, "Earth", body("Earth"), unused).isEmpty())
        assertTrue(unused.seeds.isEmpty())
    }

    @Test
    fun rollWithThePortDice() {
        // regression vectors of the best-effort PCG32 (DECISIONS 10: possibly not Roblox's dice)
        val world = BodyInfo("Kepler b", "Terrestrial World", landable = true, radius = 1500.0)
        assertEquals("MineralOre 2, MetalOre 1, WaterIce 1", Resources.roll(2, "Kepler b", world).joinToString(", "))
        val moon = BodyInfo("Kepler b I", BodyInfo.MOON, landable = true, radius = 300.0, parent = "Kepler b")
        assertEquals("MineralOre 1", Resources.roll(2, "Kepler b I", moon).joinToString(", "))
        // same input, same deposits
        assertEquals(Resources.roll(3, "Tarsis a", world), Resources.roll(3, "Tarsis a", world))
    }

    // ---------------------------------------------------------------- build stages
    private fun site(now: Long = NOW) = StationRecord("P1").apply {
        name = "RAVEN HOLD"
        host = "Mars"
        economy = "Extraction"
        founded = now
    }

    @Test
    fun stagesAsInTheLua() {
        assertEquals(listOf("FRAME", "CORE", "HABITAT RING", "DOCKING BAY"), Resources.STAGES.map { it.name })
        assertEquals(listOf(1800L, 2400L, 3000L, 3600L), Resources.STAGES.map { it.seconds })
        assertEquals(3 * 3600L, Resources.STAGES.sumOf { it.seconds })
        assertEquals(200_000L, Resources.CHARTER)
        val rows = Resources.needRows()
        val st = site()
        assertEquals(2, Resources.needs(st, rows))
        assertEquals("MetalOre", rows[0].id)
        assertEquals(16, rows[0].need)
        assertEquals(16, rows[0].missing)
        assertEquals("ConstructionAlloys", rows[1].id)
        assertEquals(8, rows[1].need)
        st.stage = 4
        assertEquals(4, Resources.needs(st, rows))
        assertEquals(listOf("Copper", "Microprocessors", "MedicalSupplies", "Grain"), (0 until 4).map { rows[it].id })
    }

    @Test
    fun deliverPartialThenCompleteStartsTheClock() {
        val st = site()
        val taken = LinkedHashMap<String, Int>()
        Resources.deliver(st, mapOf("MetalOre" to 10, "Water" to 5), NOW, taken)
        assertEquals(mapOf("MetalOre" to 10), taken)
        assertFalse(st.building)
        // over-delivery: only what is missing is taken
        Resources.deliver(st, mapOf("MetalOre" to 20, "ConstructionAlloys" to 8), NOW + 5, taken)
        assertEquals(mapOf("MetalOre" to 6, "ConstructionAlloys" to 8), taken)
        assertEquals(NOW + 5 + 1800, st.buildEnds)
        assertTrue(Resources.allDelivered(st))
        // nothing is taken while building
        Resources.deliver(st, mapOf("MetalOre" to 5), NOW + 6, taken)
        assertTrue(taken.isEmpty())
        // an empty offer takes nothing and starts nothing
        val other = site()
        Resources.deliver(other, emptyMap(), NOW, taken)
        assertTrue(taken.isEmpty())
        assertFalse(other.building)
    }

    @Test
    fun advanceOneStepAtATimeAndTheFinalStage() {
        val st = site()
        val taken = LinkedHashMap<String, Int>()
        Resources.deliver(st, mapOf("MetalOre" to 16, "ConstructionAlloys" to 8), NOW, taken)
        assertFalse(Resources.advance(st, NOW + 1799))
        assertEquals(0, Resources.builtStages(st, NOW + 1799))
        assertEquals(1, Resources.builtStages(st, NOW + 1800))
        assertTrue(Resources.advance(st, NOW + 5000))
        assertEquals(2, st.stage)
        assertFalse(st.building)
        assertTrue(st.delivered.isEmpty())
        assertFalse(Resources.advance(st, NOW + 9999), "no deliveries, no clock: nothing to advance")
        // jump to the last stage
        st.stage = 4
        st.buildEnds = NOW + 100
        assertFalse(Resources.isOperational(st, NOW + 99))
        assertTrue(Resources.isOperational(st, NOW + 100), "operational once the clock ran out, before the record knows")
        assertEquals(4, Resources.builtStages(st, NOW + 100))
        assertTrue(Resources.advance(st, NOW + 7000))
        assertTrue(st.operational)
        assertEquals(4, st.stage)
        assertEquals(NOW + 100, st.operationalAt, "OperationalAt is when the build finished, not when it was noticed")
        assertEquals(NOW + 100, st.storageAt)
        assertNotNull(st.storage)
        assertEquals(0, Resources.needs(st, Resources.needRows()))
        assertFalse(Resources.advance(st, NOW + 9999))
        assertEquals(4, Resources.builtStages(st, NOW))
    }

    @Test
    fun accrueRateCapAndLostTime() {
        val st = site().apply {
            operational = true
            storage = LinkedHashMap()
            storageAt = NOW
        }
        val mars = Resources.roll(1, "Mars", body("Mars")) // MetalOre 3, MineralOre 3, WaterIce 2: 6 + 6 + 4 = 16 t/h
        assertFalse(Resources.accrue(st, mars, NOW), "no time passed")
        assertTrue(Resources.accrue(st, mars, NOW + 3600))
        assertEquals(6.0, st.storage!!["MetalOre"]!!, 1e-9)
        assertEquals(6.0, st.storage!!["MineralOre"]!!, 1e-9)
        assertEquals(4.0, st.storage!!["WaterIce"]!!, 1e-9)
        assertEquals(NOW + 3600, st.storageAt)
        // 30 h later: the 184 t of room fill in 11.5 h, the other 18.5 h are lost
        assertTrue(Resources.accrue(st, mars, NOW + 3600 + 30 * 3600))
        assertEquals(200.0, Resources.storageTotal(st), 1e-9)
        assertEquals(6.0 + 6 * 11.5, st.storage!!["MetalOre"]!!, 1e-9)
        assertEquals(4.0 + 4 * 11.5, st.storage!!["WaterIce"]!!, 1e-9)
        // full: stays full, the clock moves on
        assertTrue(Resources.accrue(st, mars, NOW + 40 * 3600))
        assertEquals(200.0, Resources.storageTotal(st), 1e-9)
        assertEquals(NOW + 40 * 3600, st.storageAt)
        // not operational: nothing
        assertFalse(Resources.accrue(site(), mars, NOW + 99_999))
    }

    @Test
    fun takeWholeTonsMostValuableFirst() {
        val st = site().apply {
            operational = true
            storage = linkedMapOf("WaterIce" to 3.9, "MetalOre" to 2.5, "MineralOre" to 5.0000001)
        }
        val taken = LinkedHashMap<String, Int>()
        Resources.take(st, 6.0, taken)
        // MetalOre (190) first, then MineralOre (160), then WaterIce (90)
        assertEquals(listOf("MetalOre" to 2, "MineralOre" to 4), taken.toList())
        assertEquals(0.5, st.storage!!["MetalOre"]!!, 1e-9)
        assertEquals(1.0000001, st.storage!!["MineralOre"]!!, 1e-9)
        Resources.take(st, Double.POSITIVE_INFINITY, taken)
        assertEquals(mapOf("MineralOre" to 1, "WaterIce" to 3), taken)
        // whole-ton remainders below 1e-6 disappear; fractions stay
        assertFalse(st.storage!!.containsKey("MineralOre"))
        assertEquals(0.9, st.storage!!["WaterIce"]!!, 1e-9)
        Resources.take(st, 10.0, taken)
        assertTrue(taken.isEmpty())
    }

    // ---------------------------------------------------------------- A.5 slots
    @Test
    fun slotsA5() {
        val expected = listOf(
            doubleArrayOf(22.5, 28.0, -1545.2, 2146.9, -3730.4),
            doubleArrayOf(67.5, -28.0, -3730.4, -2146.9, -1545.2),
            doubleArrayOf(112.5, 28.0, -3730.4, 2146.9, 1545.2),
            doubleArrayOf(157.5, -28.0, -1545.2, -2146.9, 3730.4),
            doubleArrayOf(202.5, 28.0, 1545.2, 2146.9, 3730.4),
            doubleArrayOf(247.5, -28.0, 3730.4, -2146.9, 1545.2),
            doubleArrayOf(292.5, 28.0, 3730.4, 2146.9, -1545.2),
            doubleArrayOf(337.5, -28.0, 1545.2, -2146.9, -3730.4),
        )
        val p = DVec3()
        for (i in 1..8) {
            val s = Resources.slot(i, 1130.0, isMoon = false)
            val e = expected[i - 1]
            assertEquals(e[0], s.angle)
            assertEquals(e[1], s.elevation)
            assertEquals(4573.0, s.distance)
            StationCatalog.offset(s, 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, p)
            assertEquals(e[2], p.x, 0.05, "slot $i x")
            assertEquals(e[3], p.y, 0.05, "slot $i y")
            assertEquals(e[4], p.z, 0.05, "slot $i z")
        }
        val moon = Resources.slot(2, 580.0, isMoon = true)
        assertEquals(-55.0, moon.elevation)
        assertEquals(2818.0, moon.distance)
    }

    @Test
    fun pickSlotSkipsTakenOrbits() {
        assertEquals(1, Resources.pickSlot(1130.0, false, emptyList())!!.index)
        // a station in slot 1 blocks it; slot 2 is far enough
        val one = Resources.slot(1, 1130.0, false)
        assertEquals(2, Resources.pickSlot(1130.0, false, listOf(one))!!.index)
        // ARES DEEP (angle 60, elevation 28, 4600) blocks the slots near it on Mars
        val ares = StationCatalog.SOL.first { it.id == "SOL-ARES" }
        val withAres = Resources.pickSlot(1130.0, false, listOf(ares))!!
        val a = DVec3()
        val b = DVec3()
        StationCatalog.offset(ares, 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, a)
        StationCatalog.offset(withAres, 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, b)
        val d = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
        assertTrue(d >= Resources.SLOT_CLEARANCE)
        for (i in 1 until withAres.index) {
            StationCatalog.offset(Resources.slot(i, 1130.0, false), 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, b)
            val di = kotlin.math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y) + (a.z - b.z) * (a.z - b.z))
            assertTrue(di < Resources.SLOT_CLEARANCE, "slot $i is blocked")
        }
        // all eight taken: none left
        val all = (1..8).map { Resources.slot(it, 1130.0, false) }
        assertNull(Resources.pickSlot(1130.0, false, all))
    }

    // ---------------------------------------------------------------- names and player stations
    @Test
    fun twelveUniqueNames() {
        val names = Resources.nameOptions(Resources.nameSeed(1, 1, "Mars"))
        assertEquals(12, names.size)
        assertEquals(12, names.toSet().size)
        for (n in names) {
            val (first, last) = n.split(' ')
            assertTrue(first in Resources.NAME_FIRST && last in Resources.NAME_LAST, n)
        }
        assertEquals(names, Resources.nameOptions("1/1/Mars"), "same pilot, same place, same names")
        assertEquals("1/1/Mars", Resources.nameSeed(1, 1, "Mars"))
        // regression vector of the port's dice
        assertEquals("NORTHWIND WORKS", names[0])
        assertEquals("IRONWOOD STATION", names[11])
        assertTrue(Resources.nameOptions("1/1/Luna") != names)
    }

    @Test
    fun playerStationDefinitions() {
        val st = site().apply {
            owner = "PILOT"
            ownerId = 1
            angle = 22.5
            elevation = 28.0
            distance = 4573.0
        }
        val def = Resources.stationDef(st, 1, "Sol")
        assertEquals("P1", def.id)
        assertTrue(def.player)
        assertFalse(def.shipyard)
        assertEquals("RingStation", def.model)
        assertEquals("EXTRACTION", def.economyName)
        assertEquals(
            "A PRIVATE STATION FOUNDED BY PILOT. ITS DRILLS WORK THE DEPOSITS OF MARS AROUND THE CLOCK. ORE AND ICE GO OUT; FOOD AND MACHINERY COME IN.",
            def.description,
        )
        st.economy = "Refinery"
        st.owner = null
        assertEquals(
            "A PRIVATE STATION FOUNDED BY AN UNKNOWN PILOT. ITS SKIMMERS HARVEST HELIUM-3 FROM MARS. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN.",
            Resources.stationDef(st, 1, "Sol").description,
        )
        st.economy = null
        val service = Resources.stationDef(st, 1, "Sol")
        assertEquals("Extraction", service.economy, "no economy reads as Extraction")
        assertTrue(service.description.endsWith("A QUIET STOPOVER ABOVE MARS FOR PASSING CREWS."), "but with the Service blurb, as in Lua")
        // only operational records become stations, sorted by id
        val done = StationRecord("P2").apply {
            operational = true
            host = "Luna"
        }
        val defs = Resources.stationDefs(listOf(done, site()), 1, "Sol", NOW)
        assertEquals(listOf("P2"), defs.map { it.id })
        assertEquals("P7", Resources.stationId(7))
    }
}
