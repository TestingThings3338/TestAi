package com.starwake.core.econ

import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.Rig
import com.starwake.core.save.GalaxyStore
import com.starwake.core.save.OwnStation
import com.starwake.core.station.SolWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ShipCommerce and ShipServer/ShipConstruction as local rules: every rule and reason of 2.9 and 2.14. */
class CommerceTest {
    private fun Reply.assertFails(reason: String) {
        assertFalse(ok, "expected \"$reason\"")
        assertEquals(reason, this.reason)
    }

    private fun Reply.assertOk(): Reply {
        assertTrue(ok, "failed: $reason")
        assertNull(reason)
        return this
    }

    // ---------------------------------------------------------------- start
    @Test
    fun newPilotStartsDockedAtMeridian() {
        val rig = Rig()
        val c = rig.commerce
        val r = c.startSession().assertOk()
        assertEquals("SOL-MERIDIAN", r.docked)
        assertTrue(r.pad in 1..6)
        assertEquals(NOW, r.now)
        assertEquals("SOL-MERIDIAN", c.docked)
        assertEquals(r.pad, c.pad)
        assertEquals(1, c.system)
        assertEquals(1500L, rig.profile.credits)
        assertEquals("Varrow", rig.profile.activeShip)
        assertEquals(1.0, rig.profile.fuel())
        // the galaxy record of Sol exists: registry discoveries
        assertEquals(GalaxyStore.SOL_REGISTRY, rig.galaxy.body(1, "Earth")!!.discovered!!.by)
        assertEquals("MERIDIAN STATION", c.dockedStation()!!.name)
    }

    @Test
    fun aStationThatIsGoneSendsThePilotHome() {
        val rig = Rig()
        rig.profile.location.setDocked(2, "S1-1")
        val r = rig.commerce.startSession().assertOk()
        assertEquals("SOL-MERIDIAN", r.docked)
        assertEquals(1, rig.commerce.system)
        assertEquals(1, rig.profile.location.system)
        assertTrue(rig.profile.dirty && rig.profile.urgentRequested)
        // in flight: no pad
        val rig2 = Rig()
        rig2.profile.location.setInFlight(1, "SOL-ARES")
        val r2 = rig2.commerce.startSession().assertOk()
        assertNull(r2.docked)
        assertEquals(0, r2.pad)
    }

    // ---------------------------------------------------------------- docking
    @Test
    fun requestCancelDockUndock() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.requestDocking("SOL-ARES").assertFails("ALREADY DOCKED")
        c.undock().assertOk()
        assertEquals("SOL-MERIDIAN", rig.profile.location.lastStation)
        assertFalse(rig.profile.location.docked)
        c.undock().assertFails("NOT DOCKED")
        c.requestDocking("SOL-NOWHERE").assertFails("UNKNOWN STATION")
        c.dock("SOL-ARES", 0.5).assertFails("NO DOCKING PERMISSION")

        val r = c.requestDocking("SOL-ARES").assertOk()
        val pad = r.pad
        assertTrue(pad in 1..6)
        assertEquals(600.0, r.seconds)
        // the same permit again: same pad, the time left
        rig.pass(100.0)
        val again = c.requestDocking("SOL-ARES").assertOk()
        assertEquals(pad, again.pad)
        assertEquals(500.0, again.seconds, 1e-9)
        // the wrong station
        c.dock("SOL-HELIOS", 0.5).assertFails("NO DOCKING PERMISSION")
        // cancel drops it
        c.cancelDocking().assertOk()
        c.dock("SOL-ARES", 0.5).assertFails("NO DOCKING PERMISSION")
        // an expired permit
        c.requestDocking("SOL-ARES").assertOk()
        rig.pass(601.0)
        c.dock("SOL-ARES", 0.5).assertFails("NO DOCKING PERMISSION")
        // docking saves location, fuel and a docking
        val p2 = c.requestDocking("SOL-ARES").assertOk().pad
        rig.profile.dirty = false
        val d = c.dock("SOL-ARES", 0.42).assertOk()
        assertEquals(p2, d.pad)
        assertEquals("SOL-ARES", c.docked)
        assertTrue(rig.profile.location.docked)
        assertEquals("SOL-ARES", rig.profile.location.station)
        assertEquals(0.42, rig.profile.fuel())
        assertEquals(1L, rig.profile.stats.dockings)
        assertTrue(rig.profile.dirty && rig.profile.urgentRequested)
        // cancel while docked does nothing
        c.cancelDocking().assertOk()
        assertEquals("SOL-ARES", c.docked)
        // bad arguments, as the Roblox guards drop them
        c.undock()
        c.requestDocking("SOL-ARES")
        c.dock("SOL-ARES", 1.5).assertFails("NO RESPONSE - TRY AGAIN")
    }

    // ---------------------------------------------------------------- market and trade
    @Test
    fun marketNeedsNoDocking() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.undock()
        val m = Market()
        c.market("SOL-MERIDIAN", m).assertOk()
        assertTrue(m.valid)
        assertEquals(169, m.row("Water")!!.buy)
        assertEquals(156, m.row("Water")!!.sell)
        assertEquals(0, m.cargoUsed)
        assertEquals(16, m.cargoCapacity)
        c.market("S5-1", m).assertFails("UNKNOWN STATION")
        assertFalse(m.valid)
    }

    @Test
    fun tradeBuyAndSellWater() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.trade("SOL-ARES", "Water", 4, true).assertFails("NOT DOCKED HERE")
        c.trade("SOL-MERIDIAN", "Unobtainium", 4, true).assertFails("UNKNOWN COMMODITY")
        c.trade("SOL-MERIDIAN", "Water", 0, true).assertFails("NO RESPONSE - TRY AGAIN")
        c.trade("SOL-MERIDIAN", "Water", 2001, true).assertFails("NO RESPONSE - TRY AGAIN")
        c.trade("SOL-MERIDIAN", "Water", 17, true).assertFails("CARGO HOLD FULL")
        c.trade("SOL-MERIDIAN", "Titanium", 2, true).assertFails("NOT ENOUGH CREDITS") // 2 × 1499 > 1500
        c.trade("SOL-MERIDIAN", "Water", 1, false).assertFails("NOT ENOUGH IN THE HOLD")

        // the 9.5 trade scenario: 4 t of Water at MERIDIAN -676 CR, sold back +624 CR
        val buy = c.trade("SOL-MERIDIAN", "Water", 4, true).assertOk()
        assertEquals(1500L - 676, buy.credits)
        assertEquals(4, buy.hold)
        assertEquals(169, buy.price)
        assertEquals(4, rig.profile.cargoUsed)
        assertEquals(676L, rig.profile.stats.spent)
        val sell = c.trade("SOL-MERIDIAN", "Water", 4, false).assertOk()
        assertEquals(1500L - 676 + 624, sell.credits)
        assertEquals(0, sell.hold)
        assertEquals(156, sell.price)
        assertEquals(0, rig.profile.cargo("Water"))
        assertEquals(0, rig.profile.cargoUsed)
        assertEquals(624L, rig.profile.stats.earned)
        assertEquals(2L, rig.profile.stats.trades)
        // the hold limit counts everything aboard
        c.debugSetCredits(100_000)
        c.trade("SOL-MERIDIAN", "Water", 10, true).assertOk()
        c.trade("SOL-MERIDIAN", "Grain", 7, true).assertFails("CARGO HOLD FULL")
        c.trade("SOL-MERIDIAN", "Grain", 1, true).assertOk()
        assertEquals(11, rig.profile.cargoUsed)
    }

    // ---------------------------------------------------------------- refuel and data
    @Test
    fun refuelRulesA6() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.refuel("SOL-MERIDIAN", 0.996).assertFails("TANK ALREADY FULL")
        c.refuel("SOL-ARES", 0.5).assertFails("NOT DOCKED HERE")
        c.refuel("SOL-MERIDIAN", -0.1).assertFails("NO RESPONSE - TRY AGAIN")
        // fuel 0.37 with 1,000 CR: 63 units for 252 CR, then full
        rig.profile.credits = 1000
        val a = c.refuel("SOL-MERIDIAN", 0.37).assertOk()
        assertEquals(252L, a.cost)
        assertEquals(1.0, a.fuel)
        assertEquals(748L, rig.profile.credits)
        assertEquals(1.0, rig.profile.fuel())
        assertEquals(252L, rig.profile.stats.spent)
        // fuel 0 with 150 CR: 37 units for 148 CR, 37 %
        rig.profile.credits = 150
        val b = c.refuel("SOL-MERIDIAN", 0.0).assertOk()
        assertEquals(148L, b.cost)
        assertEquals(0.37, b.fuel, 1e-12)
        assertEquals(2L, rig.profile.credits)
        assertEquals(0.37, rig.profile.fuel()!!, 1e-12)
        c.refuel("SOL-MERIDIAN", 0.37).assertFails("NOT ENOUGH CREDITS")
        // less than half a unit left over counts as full
        rig.profile.credits = 1000
        assertEquals(1.0, c.refuel("SOL-MERIDIAN", 0.004).assertOk().fuel)
    }

    @Test
    fun refuelTopsUpTheLastUnitDecisions35() {
        // Roblox answered NOT ENOUGH CREDITS for a tank missing 0.5..1 unit, whatever the credits
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        rig.profile.credits = 81_767
        val top = c.refuel("SOL-MERIDIAN", 0.99498).assertOk()
        assertEquals(3L, top.cost, "ceil(0.502 units x 4 CR)")
        assertEquals(1.0, top.fuel)
        assertEquals(81_764L, rig.profile.credits)
        assertEquals(1.0, rig.profile.fuel())
        // 0.8 units missing: 4 CR pay for it, 3 CR do not (whole units, as everywhere else)
        rig.profile.credits = 4
        assertEquals(4L, c.refuel("SOL-MERIDIAN", 0.992).assertOk().cost)
        assertEquals(0L, rig.profile.credits)
        rig.profile.credits = 3
        c.refuel("SOL-MERIDIAN", 0.992).assertFails("NOT ENOUGH CREDITS")
        // with 4 CR or more a tank that is not full is never refused, and the hub tile agrees
        val random = java.util.Random(35)
        val quote = Economy.RefuelQuote()
        var refusedBefore = 0
        repeat(5000) {
            val fuel = if (it % 2 == 0) 0.99 + random.nextDouble() * 0.01 else random.nextDouble()
            val credits = 4L + random.nextInt(100_000)
            rig.profile.credits = credits
            Economy.refuelQuote(fuel, credits, quote)
            val r = c.refuel("SOL-MERIDIAN", fuel)
            if (quote.full) {
                r.assertFails("TANK ALREADY FULL")
                return@repeat
            }
            if (quote.units < 1.0) refusedBefore++
            r.assertOk()
            assertTrue(quote.affordable, "fuel $fuel, $credits CR")
            assertEquals(quote.cost, r.cost)
            assertTrue(r.cost in 1L..credits)
            assertEquals(credits - r.cost, rig.profile.credits)
        }
        assertTrue(refusedBefore > 1000, "$refusedBefore of these tanks were refused in Roblox")
    }

    @Test
    fun surveyThenSellDataKeepsBodiesCharted() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.sellData("SOL-MERIDIAN").assertFails("NO SURVEY DATA ON BOARD")
        c.survey(1, "Earth").assertOk()
        c.survey(1, "Mars").assertOk()
        c.sellData("SOL-ARES").assertFails("NOT DOCKED HERE")
        val s = c.sellData("SOL-MERIDIAN").assertOk()
        assertEquals(4320L + 320L, s.earned)
        assertEquals(2, s.count)
        assertEquals(1500L + 4640L, rig.profile.credits)
        assertTrue(rig.profile.survey.isEmpty())
        assertTrue(rig.profile.isCharted(1, "Earth"))
        c.survey(1, "Earth").assertFails("ALREADY CHARTED")
    }

    // ---------------------------------------------------------------- shipyard
    @Test
    fun buyAndSwitchShips() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.buyShip("SOL-MERIDIAN", "Starfury").assertFails("UNKNOWN SHIP")
        c.buyShip("SOL-MERIDIAN", "Torrin").assertFails("NOT ENOUGH CREDITS")
        c.buyShip("SOL-MERIDIAN", "Varrow").assertFails("ALREADY OWNED")
        c.switchShip("SOL-MERIDIAN", "Torrin").assertFails("NOT OWNED")
        c.switchShip("SOL-MERIDIAN", "Varrow").assertFails("ALREADY ACTIVE")
        // cargo blocks the shipyard before anything else
        c.trade("SOL-MERIDIAN", "Water", 1, true).assertOk()
        c.buyShip("SOL-MERIDIAN", "Torrin").assertFails("SELL YOUR CARGO FIRST")
        c.switchShip("SOL-MERIDIAN", "Nope").assertFails("SELL YOUR CARGO FIRST")
        c.trade("SOL-MERIDIAN", "Water", 1, false).assertOk()
        // ARES has no shipyard
        rig.dockAt("SOL-ARES")
        c.buyShip("SOL-ARES", "Torrin").assertFails("NO SHIPYARD HERE")
        rig.dockAt("SOL-MERIDIAN", fuel = 0.6)
        c.debugSetCredits(30_000)
        val r = c.buyShip("SOL-MERIDIAN", "Torrin").assertOk()
        assertEquals("Torrin", r.ship)
        assertEquals("Torrin", rig.profile.activeShip)
        assertEquals(4000L, rig.profile.credits)
        assertEquals(1.0, rig.profile.fuel("Torrin"))
        assertEquals(169L + 26_000L, rig.profile.stats.spent, "1 t of Water, then the TORRIN")
        // switching back keeps each ship's own fuel
        c.switchShip("SOL-MERIDIAN", "Varrow").assertOk()
        assertEquals("Varrow", rig.profile.activeShip)
        assertEquals(0.6, rig.profile.fuel())
        assertEquals(2, rig.profile.shipCount())
        // TRANQUILITY has a shipyard too
        rig.dockAt("SOL-TRANQUILITY")
        c.switchShip("SOL-TRANQUILITY", "Torrin").assertOk()
    }

    // ---------------------------------------------------------------- survey
    @Test
    fun surveyValuesAndDiscoveries() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.survey(2, "Kepler b").assertFails("WRONG SYSTEM")
        c.survey(1, "Vulcan").assertFails("UNKNOWN BODY")
        c.survey(4, "Earth").assertFails("NO RESPONSE - TRY AGAIN")
        val earth = c.survey(1, "Earth").assertOk()
        assertEquals(4320L, earth.value)
        assertEquals("TERRESTRIAL WORLD", earth.cls)
        assertEquals("1/Earth", earth.key)
        assertFalse(earth.first, "Sol was surveyed by the registry")
        assertTrue(earth.hasDiscovery)
        assertEquals("SOL REGISTRY", earth.discoveredBy)
        assertEquals(0L, earth.discoveredAt)
        val again = c.survey(1, "Earth")
        again.assertFails("ALREADY CHARTED")
        assertEquals("1/Earth", again.key)
        val sun = c.survey(1, "Sol").assertOk()
        assertEquals("CLASS G STAR", sun.cls)
        assertEquals(680L, sun.value)
        assertEquals(2, rig.profile.survey.size)
        assertEquals(2L, rig.profile.stats.surveys)
        assertEquals("Sol", rig.profile.survey[0].systemName)
        assertEquals(5000L, rig.profile.surveyTotal)
        // a Lochran pays ×1.5 (rounded to tens: 1,150 -> 1,730)
        rig.profile.setShip("Lochran")
        rig.profile.activeShip = "Lochran"
        assertEquals(1730L, c.survey(1, "Venus").assertOk().value)
    }

    @Test
    fun firstSurveyOutsideSolPaysTheBonusOnce() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.undock()
        c.reportJump(2).assertOk()
        val first = c.survey(2, "Kepler b").assertOk()
        assertTrue(first.first)
        assertEquals(21_600L, first.value, "14,400 × 1.5")
        assertEquals("PILOT", first.discoveredBy)
        assertEquals(NOW, first.discoveredAt)
        assertTrue(rig.profile.survey.last().first)
        // lost in a crash: surveyed again, no longer the first
        c.reportDestroyed().assertOk()
        assertFalse(rig.profile.isCharted(2, "Kepler b"))
        val second = c.survey(2, "Kepler b").assertOk()
        assertFalse(second.first)
        assertEquals(14_400L, second.value)
        assertEquals("PILOT", second.discoveredBy)
        // a Lochran's first survey: 32,400
        rig.profile.setShip("Lochran")
        rig.profile.activeShip = "Lochran"
        c.undock()
        c.reportJump(3).assertOk()
        val star = c.survey(3, "Tarsis").assertOk()
        assertEquals("CLASS O STAR", star.cls)
        assertEquals(16_200L, star.value, "7,200 × 1.5 × 1.5")
        rig.profile.callsign = "Nova"
        val rocky = c.survey(3, "Tarsis a").assertOk()
        assertEquals(2430L, rocky.value, "1,080 × 1.5 × 1.5")
        assertEquals("ROCKY BODY", rocky.cls)
        assertEquals("PILOT NOVA", rocky.discoveredBy)
        assertEquals("PILOT NOVA", rig.galaxy.body(3, "Tarsis a")!!.discovered!!.by)

        // A.2: a landable terrestrial world, Lochran and first: 32,400
        val fresh = Rig()
        fresh.profile.setShip("Lochran")
        fresh.profile.activeShip = "Lochran"
        fresh.commerce.startSession()
        fresh.commerce.undock()
        fresh.commerce.reportJump(2)
        assertEquals(32_400L, fresh.commerce.survey(2, "Kepler b").assertOk().value)
    }

    // ---------------------------------------------------------------- reports
    @Test
    fun positionAndJumpReports() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        val rev = rig.profile.revision
        c.reportPosition(1, 1.0, 2.0, 3.0, 0.0, 0.0, -1.0, 0.5)
        assertTrue(rig.profile.location.docked, "ignored while docked")
        c.reportJump(2).assertFails("DOCKED")
        assertEquals(1, c.system)
        c.undock()
        c.reportPosition(2, 1.0, 2.0, 3.0, 0.0, 0.0, -1.0, 0.5)
        assertFalse(rig.profile.location.hasPose, "another system: ignored")
        rig.profile.dirty = false
        rig.profile.urgentRequested = false
        val revBefore = rig.profile.revision
        c.reportPosition(1, 10.0, 20.0, 30.0, 0.0, 1.0, 0.0, 0.25)
        val loc = rig.profile.location
        assertTrue(loc.hasPose)
        assertEquals(20.0, loc.position[1])
        assertEquals(1.0, loc.look[1])
        assertEquals(Commerce.POSITION_FRAME, loc.frame)
        assertEquals("SOL-MERIDIAN", loc.lastStation)
        assertEquals(0.25, rig.profile.fuel())
        assertTrue(rig.profile.dirty)
        assertFalse(rig.profile.urgentRequested)
        assertEquals(revBefore, rig.profile.revision, "a quiet change")
        assertTrue(rev <= revBefore)
        // a jump: first visit of Kepler, then not again; Sol is the registry's
        val j = c.reportJump(2).assertOk()
        assertTrue(j.firstVisit)
        assertEquals(2, c.system)
        assertEquals(1L, rig.profile.stats.jumps)
        assertFalse(rig.profile.location.hasPose)
        assertEquals("SOL-MERIDIAN", rig.profile.location.lastStation)
        assertTrue(rig.galaxy.isFreshFirstVisit(2, 1, NOW + 10))
        assertFalse(rig.galaxy.isFreshFirstVisit(2, 1, NOW + 300))
        assertTrue(c.reportJump(3).assertOk().firstVisit)
        assertFalse(c.reportJump(2).assertOk().firstVisit)
        assertFalse(c.reportJump(1).assertOk().firstVisit)
        c.reportJump(9).assertFails("NO RESPONSE - TRY AGAIN")
        // a permit does not survive a jump
        c.requestDocking("SOL-ARES").assertOk()
        c.reportJump(1)
        c.dock("SOL-ARES", 1.0).assertFails("NO DOCKING PERMISSION")
    }

    @Test
    fun destroyedShipCostsFeeCargoAndData() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.trade("SOL-MERIDIAN", "Water", 3, true).assertOk()
        c.survey(1, "Earth").assertOk()
        c.survey(1, "Mars").assertOk()
        c.sellData("SOL-MERIDIAN").assertOk() // Earth and Mars sold: they stay charted
        c.survey(1, "Venus").assertOk()
        c.undock()
        val credits = rig.profile.credits
        val r = c.reportDestroyed().assertOk()
        assertEquals(1100L, r.fee, "5 % of a VARROW")
        assertEquals(3, r.lostCargo)
        assertEquals(1150L, r.lostData)
        assertEquals(credits - 1100, rig.profile.credits)
        assertEquals(0, rig.profile.cargoUsed)
        assertTrue(rig.profile.survey.isEmpty())
        assertFalse(rig.profile.isCharted(1, "Venus"), "lost data can be surveyed again")
        assertTrue(rig.profile.isCharted(1, "Earth"), "sold data stays charted")
        assertEquals(1L, rig.profile.stats.losses)
        // the fee never exceeds the credits
        rig.profile.credits = 500
        assertEquals(500L, c.reportDestroyed().assertOk().fee)
        assertEquals(0L, rig.profile.credits)
    }

    @Test
    fun trainingAndDebugCommands() {
        val rig = Rig()
        val c = rig.commerce
        c.finishTraining(completed = false).assertOk()
        assertTrue(rig.profile.training.done && rig.profile.training.skipped && !rig.profile.training.completed)
        c.debugSetCredits(999_999).assertOk()
        assertEquals(999_999L, rig.profile.credits)
        c.debugCargo("MetalOre", 5).assertOk()
        assertEquals(5, rig.profile.cargo("MetalOre"))
        c.debugCargo("MetalOre", -9).assertOk()
        assertEquals(0, rig.profile.cargo("MetalOre"))
        c.debugCargo("Nope", 1).assertFails("UNKNOWN COMMODITY")
        c.debugSite("fill").assertFails("YOU DO NOT OWN A STATION")
    }

    // ---------------------------------------------------------------- mining by hand
    @Test
    fun mineRules() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.mine(1, "Mars", "MetalOre").assertFails("DOCKED")
        c.undock()
        c.mine(2, "Kepler b", "MineralOre").assertFails("WRONG SYSTEM")
        c.mine(1, "Earth", "MetalOre").assertFails("NO MINING ON INHABITED WORLDS")
        c.mine(1, "Jupiter", "Helium3").assertFails("LAND ON THE BODY TO MINE IT")
        c.mine(1, "Mars", "Helium3").assertFails("NO SUCH DEPOSIT HERE")
        val r = c.mine(1, "Mars", "MetalOre").assertOk()
        assertEquals("MetalOre", r.resource)
        assertEquals(1, r.hold)
        assertEquals(1, r.cargoUsed)
        assertEquals(16, r.capacity)
        assertEquals(1L, rig.profile.stats.mined)
        // MetalOre on Mars is grade 3: one ton per 14 s, accepted after 0.8 × 14 = 11.2 s
        rig.pass(11.0)
        c.mine(1, "Mars", "MetalOre").assertFails("TOO FAST")
        rig.pass(0.3)
        c.mine(1, "Mars", "MetalOre").assertOk()
        // the hold fills up
        rig.profile.setCargo("Water", 14)
        rig.pass(20.0)
        c.mine(1, "Mars", "MetalOre").assertFails("CARGO HOLD FULL")
        // a new system must be surveyed first
        c.reportJump(2)
        c.mine(2, "Kepler b", "MineralOre").assertFails("SURVEY THIS BODY FIRST")
    }

    @Test
    fun kessockMinesTwiceAsFast() {
        val rig = Rig()
        val c = rig.commerce
        rig.profile.setShip("Kessock")
        rig.profile.activeShip = "Kessock"
        c.startSession()
        c.undock()
        c.mine(1, "Mars", "MetalOre").assertOk()
        rig.pass(5.7) // 14 × 0.8 / 2 = 5.6 s
        c.mine(1, "Mars", "MetalOre").assertOk()
        assertEquals(96, c.reply.capacity)
    }

    // ---------------------------------------------------------------- own station
    @Test
    fun charterChecks() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.undock()
        c.foundStation(1, "Mars", 1).assertFails("NOT ENOUGH CREDITS")
        c.debugSetCredits(300_000)
        c.foundStation(1, "Mars", 13).assertFails("NO RESPONSE - TRY AGAIN")
        c.foundStation(2, "Kepler b", 1).assertFails("WRONG SYSTEM")
        c.foundStation(1, "Earth", 1).assertFails("INHABITED WORLD - NO CHARTERS")
        c.foundStation(1, "Sol", 1).assertFails("STATIONS ORBIT PLANETS AND MOONS")
        c.foundStation(1, "Vulcan", 1).assertFails("STATIONS ORBIT PLANETS AND MOONS")
        // a body without deposits
        rig.galaxy.ensure(1)!!.bodies["Phobos"] = com.starwake.core.save.BodyRecord(emptyList()).apply {
            discovered = rig.galaxy.body(1, "Mars")!!.discovered
        }
        c.foundStation(1, "Phobos", 1).assertFails("NO DEPOSITS TO WORK HERE")
        c.reportJump(2)
        c.foundStation(2, "Kepler b", 1).assertFails("SURVEY THIS BODY FIRST")
        assertEquals(300_000L, rig.profile.credits, "nothing was charged")
    }

    @Test
    fun charterAtMarsTakesTheFirstFreeSlot() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.undock()
        c.debugSetCredits(300_000)
        val names = c.nameOptions(1, "Mars")
        assertEquals(12, names.size)
        val r = c.foundStation(1, "Mars", 3).assertOk()
        assertEquals("P1", r.id)
        assertEquals(names[2], r.name)
        assertEquals(1, r.system)
        assertEquals("Mars", r.body)
        assertEquals(100_000L, rig.profile.credits)
        assertEquals(200_000L, rig.profile.stats.spent)
        val own = rig.profile.ownStation!!
        assertEquals("P1", own.id)
        assertEquals(1, own.system)
        assertEquals("Mars", own.body)
        val st = rig.galaxy.station(1, "P1")!!
        assertEquals("Extraction", st.economy, "Mars's top deposit is metal ore")
        assertEquals("PILOT", st.owner)
        assertEquals(1L, st.ownerId)
        assertEquals(1, st.stage)
        assertEquals(NOW, st.founded)
        // the first slot at least 2,400 studs from ARES DEEP
        val expected = Resources.pickSlot(1130.0, false, listOf(rig.catalog.find(1, "SOL-ARES")!!))!!
        assertEquals(expected.index, st.slot)
        assertEquals(expected.angle, st.angle)
        assertEquals(expected.elevation, st.elevation)
        assertEquals(4573.0, st.distance)
        assertTrue(rig.galaxy.dirty)
        c.foundStation(1, "Luna", 1).assertFails("YOU ALREADY OWN A STATION")
    }

    @Test
    fun noFreeOrbitRefundsTheCharter() {
        val rig = Rig()
        val c = rig.commerce
        c.startSession()
        c.undock()
        c.debugSetCredits(250_000)
        // eight other sites around Venus
        val record = rig.galaxy.ensure(1)!!
        for (i in 1..8) {
            val s = Resources.slot(i, 2020.0, false)
            record.stations["X$i"] = StationRecord("X$i").apply {
                host = "Venus"
                angle = s.angle
                elevation = s.elevation
                distance = s.distance
            }
        }
        c.foundStation(1, "Venus", 1).assertFails("NO FREE ORBIT LEFT AT THIS BODY")
        assertEquals(250_000L, rig.profile.credits)
        assertNull(rig.profile.ownStation)
        // a record with the pilot's id already there
        record.stations.clear()
        record.stations["P1"] = StationRecord("P1").apply { host = "Luna" }
        c.foundStation(1, "Venus", 1).assertFails("YOU ALREADY OWN A STATION")
        assertEquals(250_000L, rig.profile.credits)
    }

    /** A charter at Mars, as [charterAtMarsTakesTheFirstFreeSlot]. */
    private fun charteredRig(): Rig {
        val rig = Rig()
        rig.commerce.startSession()
        rig.commerce.undock()
        rig.commerce.debugSetCredits(300_000)
        rig.commerce.foundStation(1, "Mars", 1).assertOk()
        return rig
    }

    @Test
    fun deliverRules() {
        val rig = charteredRig()
        val c = rig.commerce
        c.deliver().assertFails("NOTHING IN YOUR HOLD FOR THIS STAGE")
        rig.profile.setCargo("MetalOre", 10)
        rig.profile.setCargo("Water", 3)
        val part = c.deliver().assertOk()
        assertEquals(mapOf("MetalOre" to 10), part.taken.toMap())
        assertEquals(1, part.stage)
        assertEquals(StationRecord.NONE, part.buildEnds)
        assertEquals(0, rig.profile.cargo("MetalOre"))
        assertEquals(3, rig.profile.cargo("Water"), "not needed: stays aboard")
        // docked: launch first
        rig.dockAt("SOL-ARES")
        c.deliver().assertFails("LAUNCH FIRST")
        c.undock()
        // another system
        c.reportJump(2)
        c.deliver().assertFails("YOUR SITE IS IN ANOTHER SYSTEM")
        c.reportJump(1)
        // the rest of the stage: only what is missing leaves the hold, the clock starts
        rig.profile.setCargo("MetalOre", 9)
        rig.profile.setCargo("ConstructionAlloys", 8)
        val full = c.deliver().assertOk()
        assertEquals(mapOf("MetalOre" to 6, "ConstructionAlloys" to 8), full.taken.toMap())
        assertEquals(3, rig.profile.cargo("MetalOre"))
        assertEquals(NOW + 1800, full.buildEnds)
        assertEquals(NOW, full.now)
        c.deliver().assertFails("CONSTRUCTION IN PROGRESS")
        // after the build time, a delivery goes to stage 2 (the probe advances first)
        rig.pass(1800.0)
        rig.profile.setCargo("MineralOre", 16)
        val second = c.deliver().assertOk()
        assertEquals(2, second.stage)
        // the 3 t of Water aboard are a CORE material now
        assertEquals(mapOf("MineralOre" to 16, "Water" to 3), second.taken.toMap())
        assertEquals(StationRecord.NONE, second.buildEnds)
    }

    @Test
    fun fullBuildThenDockCollectAndSell() {
        val rig = charteredRig()
        val c = rig.commerce
        for (stage in 1..4) {
            c.debugSite("fill").assertOk()
            assertTrue(c.reply.buildEnds != StationRecord.NONE, "stage $stage builds")
            c.debugSite("skip").assertOk()
        }
        val st = rig.galaxy.station(1, "P1")!!
        assertTrue(st.operational)
        assertEquals(NOW, st.operationalAt)
        c.deliver().assertFails("STATION ALREADY COMPLETE")
        c.abandon().assertFails("A FINISHED STATION STAYS")
        // a finished station is a station: in the catalogue, dockable
        val def = rig.catalog.find(1, "P1")
        assertNotNull(def)
        assertTrue(def.player)
        assertEquals("RingStation", def.model)
        c.collect().assertFails("DOCK AT YOUR STATION FIRST")
        rig.dockAt("P1")
        // 2 h of drilling at Mars: 16 t/h
        rig.pass(2 * 3600.0)
        val got = c.collect().assertOk()
        assertEquals(mapOf("MetalOre" to 12, "MineralOre" to 4), got.taken.toMap(), "16 t of room: metal ore first")
        assertEquals(16, rig.profile.cargoUsed)
        c.collect().assertFails("CARGO HOLD FULL")
        // sell the rest at the station's own prices
        val credits = rig.profile.credits
        val sold = c.sellStorage().assertOk()
        assertEquals(16, sold.tons, "8 MineralOre + 8 WaterIce left")
        val p = Price()
        Economy.price("P1", "Extraction", "MineralOre", (NOW + 7200).toDouble(), p)
        val mineral = p.sell
        Economy.price("P1", "Extraction", "WaterIce", (NOW + 7200).toDouble(), p)
        assertEquals(mineral * 8L + p.sell * 8L, sold.income)
        assertEquals(credits + sold.income, rig.profile.credits)
        c.sellStorage().assertFails("STORAGE EMPTY")
        // the hold is still full: trade it away, then collect again later
        c.trade("P1", "MetalOre", 12, false).assertOk()
        c.collect().assertFails("STORAGE EMPTY")
        rig.pass(3600.0)
        c.debugSite("age", 1.0).assertOk()
        assertEquals(12, c.collect().assertOk().taken.values.sum())
    }

    @Test
    fun abandonRules() {
        val rig = Rig()
        val c = rig.commerce
        c.abandon().assertFails("YOU DO NOT OWN A STATION")
        val chartered = charteredRig()
        chartered.commerce.abandon().assertOk()
        assertNull(chartered.profile.ownStation)
        assertNull(chartered.galaxy.station(1, "P1"))
        // a dangling pointer is cleared too
        rig.profile.ownStation = OwnStation("P1", 1, "Mars")
        c.abandon().assertOk()
        assertNull(rig.profile.ownStation)
        // and in a system the world does not know
        rig.profile.ownStation = OwnStation("P1", 9, "Nowhere")
        c.abandon().assertFails("GALAXY DATA UNAVAILABLE")
        c.deliver().assertFails("GALAXY DATA UNAVAILABLE")
    }

    @Test
    fun solOnlyWorldStillRunsTheRules() {
        // the default world (Sol only) as the game has it before the world port
        val rig = Rig(world = SolWorld)
        val r = rig.commerce.startSession().assertOk()
        assertEquals("SOL-MERIDIAN", r.docked)
        rig.commerce.undock()
        rig.commerce.reportJump(2).assertFails("NO RESPONSE - TRY AGAIN")
    }
}
