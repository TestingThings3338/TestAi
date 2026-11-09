package com.starwake.core.save

import com.starwake.core.econ.Deposit
import com.starwake.core.econ.EconFixtures
import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.TestWorld
import com.starwake.core.econ.Resources
import com.starwake.core.econ.StationRecord
import com.starwake.core.station.BodyInfo
import com.starwake.core.station.StationCatalog
import com.starwake.core.station.WorldInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** ShipGalaxy as the local galaxy store: ensure/freeze, Sol pre-discovered, discover and visit once, sites, galaxy.json. */
class GalaxyStoreTest {
    private val wall = EconFixtures.FakeWall(NOW)
    private val clock = Clock(wall, EconFixtures.FakeMono())

    private fun store(world: WorldInfo = TestWorld()): GalaxyStore = GalaxyStore(StationCatalog(world), clock)

    @Test
    fun solIsPreDiscoveredBySolRegistry() {
        val g = store()
        assertNull(g.get(1))
        val sol = g.ensure(1)!!
        assertSame(sol, g.get(1))
        assertEquals("Sol", sol.name)
        assertEquals(18, sol.bodies.size)
        for ((name, body) in sol.bodies) {
            val d = body.discovered!!
            assertEquals("SOL REGISTRY", d.by, name)
            assertEquals(0L, d.userId)
            assertEquals(0L, d.time)
        }
        assertEquals("SOL REGISTRY", sol.firstVisit!!.by)
        // the frozen Sol deposits are the sorted hand-set ones (A.3)
        assertEquals("PreciousOre 2, MetalOre 5, MineralOre 3", sol.bodies["Mercury"]!!.deposits.joinToString(", "))
        assertTrue(sol.bodies["Earth"]!!.deposits.isEmpty())
        assertTrue(sol.bodies["Sol"]!!.deposits.isEmpty())
        assertTrue(g.dirty)
        assertNull(g.ensure(0))
        assertNull(g.ensure(4))
    }

    @Test
    fun otherSystemsRollOnceAndStayFrozen() {
        val g = store()
        val kepler = g.ensure(2)!!
        assertEquals("Kepler", kepler.name)
        assertNull(kepler.firstVisit)
        assertEquals(4, kepler.bodies.size)
        val world = BodyInfo("Kepler b", "Terrestrial World", landable = true, radius = 1500.0)
        assertEquals(Resources.roll(2, "Kepler b", world), kepler.bodies["Kepler b"]!!.deposits)
        assertTrue(kepler.bodies.values.all { it.discovered == null })
        // frozen: the stored deposits win over a new roll, through a save and a load
        val w = JsonWriter()
        val custom = BodyRecord(listOf(Deposit("PreciousOre", 5)))
        kepler.bodies["Kepler b"] = custom
        g.write(w)
        val g2 = store()
        g2.read(Json.obj(Json.parse(w.toString()))!!)
        assertEquals("PreciousOre 5", g2.ensure(2)!!.bodies["Kepler b"]!!.deposits.joinToString(", "))
        // a body the world adds later is rolled and added; the others stay
        val bigger = object : WorldInfo by TestWorld() {
            override fun bodies(index: Int): List<BodyInfo> =
                if (index == 2) EconFixtures.KEPLER + BodyInfo("Kepler d", "Ice World", radius = 900.0) else TestWorld().bodies(index)
        }
        val g3 = store(bigger)
        g3.read(Json.obj(Json.parse(w.toString()))!!)
        g3.dirty = false
        val r3 = g3.ensure(2)!!
        assertEquals(5, r3.bodies.size)
        assertNotNull(r3.bodies["Kepler d"])
        assertEquals("PreciousOre 5", r3.bodies["Kepler b"]!!.deposits.joinToString(", "))
        assertTrue(g3.dirty)
    }

    @Test
    fun discoverAndVisitOnce() {
        val g = store()
        val out = DiscoverResult()
        g.discover(2, "Kepler b", "PILOT", 1, out)
        assertTrue(out.first)
        assertEquals("PILOT", out.discovered!!.by)
        assertEquals(1L, out.discovered!!.userId)
        assertEquals(NOW, out.discovered!!.time)
        wall.t += 60
        g.discover(2, "Kepler b", "PILOT NOVA", 1, out)
        assertFalse(out.first)
        assertEquals("PILOT", out.discovered!!.by, "the first discovery stays on the chart")
        assertEquals(NOW, out.discovered!!.time)
        // Sol: always the registry's
        g.discover(1, "Earth", "PILOT", 1, out)
        assertFalse(out.first)
        assertEquals("SOL REGISTRY", out.discovered!!.by)
        // unknown body or system: nothing
        g.discover(2, "Kepler z", "PILOT", 1, out)
        assertFalse(out.first)
        assertNull(out.discovered)
        g.discover(9, "Kepler b", "PILOT", 1, out)
        assertNull(out.discovered)
        // first visits
        assertTrue(g.visit(3, "PILOT", 1))
        assertFalse(g.visit(3, "PILOT", 1))
        assertFalse(g.visit(1, "PILOT", 1))
        assertFalse(g.visit(7, "PILOT", 1))
        assertTrue(g.isFreshFirstVisit(3, 1, NOW + 60 + 299))
        assertFalse(g.isFreshFirstVisit(3, 1, NOW + 60 + 300))
        assertFalse(g.isFreshFirstVisit(3, 2, NOW + 61))
        assertFalse(g.isFreshFirstVisit(1, 0, NOW), "the registry's visit is ancient")
        assertTrue(g.urgentRequested)
    }

    private fun site(stage: Int = 1) = StationRecord("P1").apply {
        name = "RAVEN HOLD"
        owner = "PILOT"
        ownerId = 1
        host = "Mars"
        economy = "Extraction"
        slot = 2
        angle = 67.5
        elevation = -28.0
        distance = 4573.0
        founded = NOW
        this.stage = stage
    }

    @Test
    fun sitesStationsAndTheTick() {
        val catalog = StationCatalog(TestWorld())
        val g = GalaxyStore(catalog, clock)
        val sol = g.ensure(1)!!
        val st = site(stage = 4)
        st.buildEnds = NOW + 3600
        sol.stations["P1"] = st
        val sites = ArrayList<StationRecord>()
        assertEquals(listOf(st), g.sites(1, NOW, sites))
        assertTrue(g.stationDefs(1, NOW).isEmpty())
        assertFalse(g.refresh(), "nothing due")
        assertNull(catalog.find(1, "P1"))
        // the build finishes: the tick advances it and registers the station
        wall.t = NOW + 3600
        assertTrue(g.sites(1, NOW + 3600, sites).isEmpty(), "operational at that time, before the record knows")
        assertTrue(g.refresh())
        assertTrue(st.operational)
        assertEquals(NOW + 3600, st.operationalAt)
        val def = catalog.find(1, "P1")!!
        assertEquals("RAVEN HOLD", def.name)
        assertEquals(1, def.systemIndex)
        assertSame(def, catalog.findAnywhere("P1"))
        assertFalse(g.refresh(), "already registered")
        // removing it unregisters it
        assertTrue(g.removeStation(1, "P1"))
        assertNull(catalog.find(1, "P1"))
        assertFalse(g.removeStation(1, "P1"))
    }

    @Test
    fun galaxyJsonRoundTrip() {
        val g = store()
        val sol = g.ensure(1)!!
        g.ensure(2)
        g.visit(2, "PILOT", 1)
        val out = DiscoverResult()
        g.discover(2, "Kepler b", "PILOT", 1, out)
        val st = site()
        st.delivered["MetalOre"] = 10
        st.buildEnds = NOW + 1800
        sol.stations["P1"] = st
        val done = site().apply {
            id = "P2"
            operational = true
            operationalAt = NOW
            storage = linkedMapOf("MetalOre" to 12.5, "WaterIce" to 0.25)
            storageAt = NOW + 10
        }
        sol.stations["P2"] = done
        val w = JsonWriter()
        g.write(w)
        val text = w.toString()
        val g2 = store()
        g2.read(Json.obj(Json.parse(text))!!)
        val w2 = JsonWriter()
        g2.write(w2)
        assertEquals(text, w2.toString(), "write -> read -> write is stable")
        val sol2 = g2.get(1)!!
        val p1 = sol2.stations["P1"]!!
        assertEquals("RAVEN HOLD", p1.name)
        assertEquals(10, p1.delivered["MetalOre"])
        assertEquals(NOW + 1800, p1.buildEnds)
        assertFalse(p1.operational)
        assertEquals(StationRecord.NONE, p1.operationalAt)
        assertNull(p1.storage)
        val p2 = sol2.stations["P2"]!!
        assertTrue(p2.operational)
        assertEquals(12.5, p2.storage!!["MetalOre"])
        assertEquals(NOW + 10, p2.storageAt)
        assertEquals(67.5, p2.angle)
        assertEquals(4573.0, p2.distance)
        val kepler = g2.get(2)!!
        assertEquals("PILOT", kepler.firstVisit!!.by)
        assertEquals("PILOT", kepler.bodies["Kepler b"]!!.discovered!!.by)
        assertNull(kepler.bodies["Kepler c"]!!.discovered)
        assertEquals(sol.bodies["Mars"]!!.deposits, sol2.bodies["Mars"]!!.deposits)
        // records of systems the world does not know (yet) are kept, never rolled
        val solOnly = GalaxyStore(StationCatalog(), clock)
        solOnly.read(Json.obj(Json.parse(text))!!)
        assertNull(solOnly.ensure(2))
        assertNotNull(solOnly.get(2))
        val w3 = JsonWriter()
        solOnly.write(w3)
        assertTrue(w3.toString().contains("\"Kepler b\""))
        // junk is skipped
        val junk = store()
        junk.read(Json.obj(Json.parse("""{"x": 1, "-3": {}, "2": 5, "1": {"Bodies": {"Mars": {"Deposits": [{"Id": "MetalOre"}, 7, {"Id": "MetalOre", "Grade": 3}]}}, "Stations": {"P1": {"Stage": "x"}}}}"""))!!)
        val r = junk.ensure(1)!!
        assertEquals(listOf(Deposit("MetalOre", 3)), r.bodies["Mars"]!!.deposits)
        assertEquals(1, r.stations["P1"]!!.stage)
        assertEquals(StationCatalog.DEFAULT_DISTANCE, r.stations["P1"]!!.distance)
        assertEquals(18, r.bodies.size, "completed with the missing bodies")
    }
}
