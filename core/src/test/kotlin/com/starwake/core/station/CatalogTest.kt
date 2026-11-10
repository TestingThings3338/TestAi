package com.starwake.core.station

import com.starwake.core.content.StationModel
import com.starwake.core.econ.EconFixtures
import com.starwake.core.econ.EconFixtures.ScriptedFactory
import com.starwake.core.econ.EconFixtures.ScriptedRandom
import com.starwake.core.econ.EconFixtures.TestWorld
import com.starwake.core.math.DVec3
import com.starwake.core.math.Pcg32Random
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ShipStations and ShipStationLore: Sol's stations, A.4 hashes, A.5 offsets, the generated-station rng order. */
class CatalogTest {
    // ---------------------------------------------------------------- Sol
    @Test
    fun solStationsAsInTheLua() {
        val cat = StationCatalog()
        val sol = cat.forSystem(1)
        assertEquals(6, sol.size)
        // id, name, host, economy, shipyard, model, distance, angle, elevation, distance / host radius
        val table = listOf(
            listOf("SOL-MERIDIAN", "MERIDIAN STATION", "Earth", "HighTech", "true", "SpireStation", "6600", "90", "24", "3.11"),
            listOf("SOL-TRANQUILITY", "TRANQUILITY YARDS", "Luna", "Industrial", "true", "FoundryStation", "3400", "200", "-32", "5.86"),
            listOf("SOL-ARES", "ARES DEEP", "Mars", "Extraction", "false", "HollowStation", "4600", "60", "28", "4.07"),
            listOf("SOL-GALILEAN", "GALILEAN REFINERY", "Jupiter", "Refinery", "false", "RefineryStation", "20000", "120", "30", "2.15"),
            listOf("SOL-TITAN", "TITAN HYDROPONICS", "Titan", "Agricultural", "false", "GardenStation", "4100", "300", "34", "4.77"),
            listOf("SOL-HELIOS", "HELIOS FORGE", "Mercury", "Industrial", "false", "RingStation", "4400", "270", "-26", "5.43"),
        )
        for (i in table.indices) {
            val row = table[i]
            val d = sol[i]
            assertEquals(row[0], d.id)
            assertEquals(row[1], d.name)
            assertEquals(row[2], d.host)
            assertEquals(row[3], d.economy)
            assertEquals(row[4].toBoolean(), d.shipyard)
            assertEquals(row[5], d.model)
            assertEquals(row[6].toDouble(), d.distance)
            assertEquals(row[7].toDouble(), d.angle)
            assertEquals(row[8].toDouble(), d.elevation)
            val host = cat.body(1, d.host)!!
            assertEquals(row[9].toDouble(), d.distance / host.radius, 0.005, d.id)
            assertEquals(1, d.systemIndex)
            assertEquals("Sol", d.systemName)
            assertTrue(d.model in StationModel.TYPES, "a converted .stm model")
            assertTrue(d.description.isNotEmpty())
        }
        assertEquals("SOL-MERIDIAN", StationCatalog.HOME_STATION)
        assertEquals("HIGH TECH", sol[0].economyName)
        assertTrue(StationCatalog.DEFAULT_MODEL in StationModel.TYPES)
        assertEquals(
            "THE BUSIEST PORT IN SOL. MERIDIAN BUILDS THE SHIP COMPUTERS HALF THE GALAXY FLIES WITH AND FEEDS ITSELF ON IMPORTED FOOD AND RAW METAL.",
            sol[0].description,
        )
    }

    @Test
    fun meridianOffsetA5() {
        val meridian = StationCatalog.SOL[0]
        val p = DVec3()
        StationCatalog.offset(meridian, 828_660.0, 0.0, 0.0, 0.0, 0.0, 0.0, p)
        assertEquals(0.0, p.x, 1e-6)
        assertEquals(2684.46, p.y, 0.005)
        assertEquals(6029.40, p.z, 0.005)
        // the world's placement (merged StationPlacement) gives the same offset for the same distance
        val q = DVec3()
        StationPlacement.offset(828_660.0, 0.0, 0.0, 0.0, 0.0, 0.0, meridian.angle, meridian.elevation, meridian.distance, q)
        assertEquals(p.x, q.x, 1e-9)
        assertEquals(p.y, q.y, 1e-9)
        assertEquals(p.z, q.z, 1e-9)
        // a star straight above the host: the base direction is -Z
        StationCatalog.offset(Resources0(0.0, 0.0, 1000.0), 0.0, 0.0, 0.0, 0.0, 5.0, 0.0, p)
        assertEquals(-1000.0, p.z, 1e-9)
    }

    private class Resources0(override val angle: Double, override val elevation: Double, override val distance: Double) : OrbitSlot

    @Test
    fun solBodiesForTheSurvey() {
        val cat = StationCatalog()
        assertEquals(18, cat.bodies(1).size)
        assertEquals(listOf("Mercury", "Venus", "Earth", "Mars", "Jupiter", "Saturn", "Uranus", "Neptune"), cat.planets(1).map { it.name })
        val sol = cat.body(1, "Sol")!!
        assertTrue(sol.isStar)
        assertEquals("G", sol.starClass)
        assertEquals(37_000.0, sol.heatRadius)
        val luna = cat.body(1, "Luna")!!
        assertEquals("Moon", luna.cls)
        assertEquals("Earth", luna.parent)
        assertTrue(luna.landable)
        assertTrue(cat.body(1, "Earth")!!.landable)
        assertTrue(!cat.body(1, "Jupiter")!!.landable && cat.body(1, "Jupiter")!!.ring)
        assertTrue(!cat.body(1, "Neptune")!!.ring)
        assertNull(cat.body(1, "Vulcan"))
        assertNull(cat.body(2, "Kepler b"), "SolWorld knows Sol only")
        assertEquals("Unknown", cat.systemName(2))
        assertTrue(cat.forSystem(2).isEmpty())
    }

    // ---------------------------------------------------------------- A.4 hashes
    @Test
    fun loreAndModelHashesA4() {
        assertEquals(176_118L, StationLore.hash("S12345-1"))
        assertEquals(0x50AAFF, StationLore.accent("S12345-1")) // accent 1
        assertEquals("DEEP RANGE CHARTER", StationLore.operator("S12345-1")) // operator 2
        assertEquals(9256L, StationLore.hash("P1"))
        assertEquals(0x46DCDC, StationLore.accent("P1")) // accent 5
        assertEquals("KESSLER LANE AUTHORITY", StationLore.operator("P1")) // operator 3
        assertEquals(88_290L, StationLore.hash("S987654-2"))
        assertEquals(0x50AAFF, StationLore.accent("S987654-2"))
        assertEquals("FRONTIER HAULERS UNION", StationLore.operator("S987654-2"))
        assertEquals("RingStation", StationCatalog.pickModel("S12345-1", "HighTech"))
        assertEquals("HollowStation", StationCatalog.pickModel("S12345-2", "Extraction"))
        assertEquals("RefineryStation", StationCatalog.pickModel("S1-1", "Refinery"))
        assertNull(StationCatalog.pickModel("S1-1", "Nope"))
    }

    @Test
    fun solLoreAndRenames() {
        assertEquals(0x50AAFF, StationLore.accent("SOL-MERIDIAN"))
        assertEquals("HALDEN-MERIDIAN TRUST", StationLore.operator("SOL-MERIDIAN"))
        assertEquals(0xFF6046, StationLore.accent("SOL-ARES"))
        assertEquals("HELIOS FOUNDRY GROUP", StationLore.operator("SOL-HELIOS"))
        assertEquals(255, StationLore.red(StationLore.accent("SOL-HELIOS")))
        assertEquals(130, StationLore.green(StationLore.accent("SOL-HELIOS")))
        assertEquals(40, StationLore.blue(StationLore.accent("SOL-HELIOS")))
        // DECISIONS 30: no "OUTER RIM"
        assertEquals("FAR REACH SURVEY TRUST", StationLore.OPERATORS[5])
        assertTrue(StationLore.OPERATORS.none { it.contains("OUTER RIM") })
        // a missing id hashes "?"
        assertEquals(StationLore.hash("?"), StationLore.hash(null))
    }

    @Test
    fun newsTicker() {
        assertEquals(7, StationLore.NEWS.size)
        // the sender is everything before the first colon (`^([^:]+):%s*(.+)$`)
        val first = StationLore.NEWS[0]
        val n = StationLore.newsSenderLength(first)
        assertEquals("SOL TRANSIT AUTHORITY REMINDS PILOTS", first.substring(0, n))
        assertEquals("SLOW DOWN INSIDE THE DOCKING ZONE.", first.substring(StationLore.newsBodyStart(first)))
        val guild = StationLore.NEWS[1]
        assertEquals("HELIX CARTOGRAPHIC GUILD", guild.substring(0, StationLore.newsSenderLength(guild)))
        assertEquals(0, StationLore.newsSenderLength("THE LONG ORBIT HOSTS OPEN MIC NIGHT EVERY TENTH CYCLE."))
        assertEquals(0, StationLore.newsSenderLength(":NO SENDER"))
        assertEquals(0, StationLore.newsSenderLength("NO TEXT:"))
        // "%s*" gives way so "(.+)" keeps one character
        assertEquals(1, StationLore.newsSenderLength("A:  "))
        assertEquals(3, StationLore.newsBodyStart("A:  "))
        // Fisher-Yates from the end with math.random(i)
        val r = ScriptedRandom(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val out = arrayOfNulls<String>(7)
        StationLore.shuffleNews(r, out)
        assertEquals(listOf("NextInteger(1,7)", "NextInteger(1,6)", "NextInteger(1,5)", "NextInteger(1,4)", "NextInteger(1,3)", "NextInteger(1,2)"), r.log)
        assertEquals(StationLore.NEWS.toSet(), out.filterNotNull().toSet())
    }

    // ---------------------------------------------------------------- generated stations
    @Test
    fun generatedStationsKeepTheRngCallOrder() {
        // 2 planets (Kepler b terrestrial r 1500, Kepler c gas giant r 7000)
        val r = ScriptedRandom(
            0.85, // roll: 2 stations
            0.0, 0.1, 0.5, 0.0, 0.5, 0.2, 0.3, 0.5, 0.25, // station 1: host b, HighTech (0.1 < 0.15, 0.5 < 0.6), ARDEN HUB, -38, shipyard, 90
            0.0, 0.9, 0.99, 0.0, 0.0, 0.7, 0.5, // station 2: host b taken -> c, Refinery, WINTER REFINERY, +35, (no shipyard draw), 180
        )
        val f = ScriptedFactory(r)
        val cat = StationCatalog(TestWorld(), f)
        val list = cat.baseForSystem(2)
        assertEquals(listOf(EconFixtures.SEED_2 * 31 + 7), f.seeds)
        assertEquals(
            listOf(
                "NextNumber()",
                "NextInteger(1,2)", "NextNumber()", "NextNumber()", "NextInteger(1,45)", "NextInteger(1,3)",
                "NextNumber(35.0,50.0)", "NextNumber()", "NextNumber()", "NextNumber(0.0,360.0)",
                "NextInteger(1,2)", "NextNumber()", "NextInteger(1,45)", "NextInteger(1,3)",
                "NextNumber(35.0,50.0)", "NextNumber()", "NextNumber(0.0,360.0)",
            ),
            r.log,
        )
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("S${EconFixtures.SEED_2}-1", a.id)
        assertEquals("ARDEN HUB", a.name)
        assertEquals("Kepler b", a.host)
        assertEquals("HighTech", a.economy)
        assertTrue(a.shipyard)
        assertEquals(-38.0, a.elevation, 1e-9)
        assertEquals(90.0, a.angle, 1e-9)
        assertEquals(floor(1500 * 2.1 + 2200), a.distance)
        assertEquals(StationCatalog.pickModel(a.id, "HighTech"), a.model)
        assertEquals("LABS, CLEAN ROOMS AND CHIP FOUNDRIES. IT SELLS TECHNOLOGY AND BUYS FOOD AND METAL.", a.description)
        assertEquals("Kepler", a.systemName)
        val b = list[1]
        assertEquals("S${EconFixtures.SEED_2}-2", b.id)
        assertEquals("WINTER REFINERY", b.name)
        assertEquals("Kepler c", b.host)
        assertEquals("Refinery", b.economy)
        assertTrue(!b.shipyard)
        assertEquals("RefineryStation", b.model)
        assertEquals(35.0, b.elevation, 1e-9)
        assertEquals(180.0, b.angle, 1e-9)
        assertEquals(16_900.0, b.distance)
        // cached: no second generation
        assertSame(list, cat.baseForSystem(2))
        assertEquals(1, f.seeds.size)
    }

    @Test
    fun stationCountRules() {
        fun count(roll: Double, world: WorldInfo = TestWorld(), index: Int = 2): Int =
            StationCatalog(world, ScriptedFactory(ScriptedRandom(roll, 0.0, 0.9))).baseForSystem(index).size
        assertEquals(0, count(0.34))
        assertEquals(1, count(0.35))
        assertEquals(1, count(0.79))
        assertEquals(2, count(0.8))
        // never more than the planets: Tarsis has one
        assertEquals(1, count(0.95, index = 3))
        // the industrial branches: pickEconomy by class and roll
        val rocky = ScriptedRandom(0.6)
        assertEquals("Industrial", StationCatalog.pickEconomy("Rocky Body", rocky))
        assertEquals("Extraction", StationCatalog.pickEconomy("Rocky Body", ScriptedRandom(0.5)))
        assertEquals("Industrial", StationCatalog.pickEconomy("Volcanic Body", ScriptedRandom(0.5)))
        assertEquals("Refinery", StationCatalog.pickEconomy("Ice Giant", ScriptedRandom(0.59)))
        assertEquals("Extraction", StationCatalog.pickEconomy("Ice Giant", ScriptedRandom(0.6)))
        assertEquals("Agricultural", StationCatalog.pickEconomy("Ocean World", ScriptedRandom(0.9)))
        assertEquals("Extraction", StationCatalog.pickEconomy("Ice World", ScriptedRandom(0.9)))
        assertEquals("Service", StationCatalog.pickEconomy("Gas Giant", ScriptedRandom(0.1, 0.6)))
    }

    @Test
    fun generatedStationsWithThePortDice() {
        val cat = StationCatalog(TestWorld())
        val a = cat.baseForSystem(2)
        val b = StationCatalog(TestWorld()).baseForSystem(2)
        assertEquals(a.map { "${it.id} ${it.name} ${it.host} ${it.economy} ${it.angle}" }, b.map { "${it.id} ${it.name} ${it.host} ${it.economy} ${it.angle}" })
        for (d in a) {
            assertTrue(d.model in StationModel.TYPES)
            assertTrue(kotlin.math.abs(d.elevation) in 35.0..50.0)
            assertTrue(d.angle in 0.0..360.0)
        }
        // Pcg32 regression vectors (Luau's math.random algorithm)
        val r0 = Pcg32Random(0)
        assertEquals(listOf(3_362_212_412L, 2_368_484_155L, 462_281_250L), List(3) { r0.next32().toUInt().toLong() })
        val r1 = Pcg32Random(12345)
        assertEquals(0.4115758477484684, r1.nextNumber())
        assertEquals(0.4716660173434923, r1.nextNumber())
        val r2 = Pcg32Random(12345)
        assertEquals(listOf(6, 3, 1, 3, 5, 2, 1, 1), List(8) { r2.nextInteger(1, 6) })
        val r3 = Pcg32Random(-7)
        assertEquals(listOf(4_104_653_045L, 1_714_790_164L), List(2) { r3.next32().toUInt().toLong() })
        assertEquals(Math.scalb(1.0, -64), 5.421010862427522E-20)
    }

    @Test
    fun lookupAndPlayerStations() {
        val cat = StationCatalog(TestWorld())
        assertEquals("ARES DEEP", cat.find(1, "SOL-ARES")!!.name)
        assertNull(cat.find(2, "SOL-ARES"), "find searches one system")
        assertEquals("ARES DEEP", cat.findAnywhere("SOL-ARES")!!.name)
        assertNull(cat.findAnywhere("P1"))
        val generated = cat.baseForSystem(2).firstOrNull()
        if (generated != null) assertSame(generated, cat.findAnywhere(generated.id))
        assertNull(cat.findAnywhere("S99-1"))
        assertNull(cat.findAnywhere("garbage"))
        // a finished player station joins its system's list
        val own = StationDef("P1", "RAVEN HOLD", "Mars", "Extraction", false, StationCatalog.DEFAULT_MODEL, 4573.0, 22.5, 28.0, "X", 1, "Sol", player = true)
        cat.setPlayerStations(1, listOf(own))
        assertEquals(7, cat.forSystem(1).size)
        assertSame(own, cat.find(1, "P1"))
        assertSame(own, cat.findAnywhere("P1"))
        assertEquals(listOf(own), cat.playerStations(1))
        cat.setPlayerStations(1, emptyList())
        assertEquals(6, cat.forSystem(1).size)
        assertNotNull(cat.find(1, "SOL-MERIDIAN"))
        assertEquals(3, cat.systemCount)
        assertTrue(cat.isValidSystem(3) && !cat.isValidSystem(4) && !cat.isValidSystem(0))
    }
}
