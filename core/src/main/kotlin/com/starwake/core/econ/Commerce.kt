package com.starwake.core.econ

import com.starwake.core.math.LuaRandom
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import com.starwake.core.save.Clock
import com.starwake.core.save.DiscoverResult
import com.starwake.core.save.GalaxyStore
import com.starwake.core.save.OwnStation
import com.starwake.core.save.Profile
import com.starwake.core.save.SurveyEntry
import com.starwake.core.save.SystemRecord
import com.starwake.core.station.OrbitSlot
import com.starwake.core.station.StationCatalog
import com.starwake.core.station.StationDef
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The answer of a [Commerce] call: `{ ok, reason, … }` as the Roblox server replies. One object is
 * reused for every call (8.3): read it right after the call. Only the fields of the call are set;
 * the rest are reset to their defaults.
 */
class Reply {
    var ok = false

    /** Why it failed, verbatim from the Lua ("NOT ENOUGH CREDITS"); null on success. */
    var reason: String? = null

    /** Epoch seconds of the answer (startSession, market, deliver). */
    var now = 0L

    // docking
    var pad = 0

    /** Seconds left on the docking permit (RequestDocking). */
    var seconds = 0.0

    /** startSession: the station the pilot starts docked at, or null. */
    var docked: String? = null

    // trade
    var credits = 0L
    var hold = 0
    var price = 0

    // refuel
    var cost = 0L
    var fuel = 0.0

    // data sale
    var earned = 0L
    var count = 0

    // shipyard
    var ship: String? = null

    // survey
    var value = 0L

    /** "CLASS G STAR" or the upper-case class ("TERRESTRIAL WORLD"). */
    var cls: String? = null

    /** "<system>/<body>" (also on "ALREADY CHARTED"). */
    var key: String? = null
    var first = false
    var discoveredBy: String? = null
    var discoveredAt = 0L

    /** True when [discoveredBy] / [discoveredAt] are set. */
    var hasDiscovery = false

    // ReportJump
    var firstVisit = false

    // ReportDestroyed
    var fee = 0L
    var lostCargo = 0
    var lostData = 0L

    // Mine
    var resource: String? = null
    var cargoUsed = 0
    var capacity = 0

    // FoundStation
    var id: String? = null
    var name: String? = null
    var system = 0
    var body: String? = null

    /** Deliver, Collect: tons per commodity id. */
    val taken = LinkedHashMap<String, Int>()
    var stage = 0
    var buildEnds = StationRecord.NONE

    // SellStorage
    var income = 0L
    var tons = 0

    fun reset(): Reply {
        ok = false
        reason = null
        now = 0L
        pad = 0
        seconds = 0.0
        docked = null
        credits = 0L
        hold = 0
        price = 0
        cost = 0L
        fuel = 0.0
        earned = 0L
        count = 0
        ship = null
        value = 0L
        cls = null
        key = null
        first = false
        discoveredBy = null
        discoveredAt = 0L
        hasDiscovery = false
        firstVisit = false
        fee = 0L
        lostCargo = 0
        lostData = 0L
        resource = null
        cargoUsed = 0
        capacity = 0
        id = null
        name = null
        system = 0
        body = null
        taken.clear()
        stage = 0
        buildEnds = StationRecord.NONE
        income = 0L
        tons = 0
        return this
    }

    override fun toString(): String = if (ok) "Reply(ok)" else "Reply(failed: $reason)"
}

/**
 * The singleplayer rules engine (8.3): ShipServer/ShipCommerce and the rules of
 * ShipServer/ShipConstruction as synchronous local calls. Every rule, every check order and every
 * reason string is the Lua's (DECISIONS 30 renames apply to texts shown elsewhere); the network
 * guards' rate limits are gone, and a request the guards would have rejected (a bad argument)
 * answers "NO RESPONSE - TRY AGAIN", as the Roblox client shows it.
 *
 * Session state (not saved): the current [system], a docking permit (station, pad, expiry on the
 * monotonic clock), the station the pilot is [docked] at and its [pad]. Saved state lives in
 * [profile] and [galaxy]; every change calls [Profile.changed] (urgent where ShipData's was) and
 * marks the galaxy dirty, so the save manager writes it. Calls run on a button press (or an event)
 * and may allocate a little; they never run per frame. The 1.6 s radio delay before
 * [requestDocking] belongs to the docking state machine.
 */
class Commerce(
    profile: Profile,
    galaxy: GalaxyStore,
    val catalog: StationCatalog,
    val clock: Clock,
    /** `math.random` for the pad choice. */
    private val padRandom: LuaRandom = Pcg32Random(System.nanoTime()),
    /** `Random.new` for the station name options. */
    private val randoms: LuaRandomFactory = Pcg32Random.FACTORY,
) {
    var profile: Profile = profile
        private set
    var galaxy: GalaxyStore = galaxy
        private set

    /** The reply of the last call. */
    val reply = Reply()

    /** The system the pilot is in (`s.system`). */
    var system = 1
        private set

    /** The station the pilot is docked at (`s.docked`), or null. */
    var docked: String? = null
        private set

    /** The pad the pilot is docked on (1..6), 0 when not docked. */
    var pad = 0
        private set

    private var grantStation: String? = null
    private var grantPad = 0
    private var grantExpires = 0.0

    /** The pad this pilot holds (singleplayer: every other pad is free). */
    private var reservedStation: String? = null
    private var reservedPad = 0
    private var lastMine = Double.NaN

    private val discover = DiscoverResult()
    private val needRows = Resources.needRows()
    private val scratchPrice = Price()

    /** Uses a newly loaded profile and galaxy (after a reload); the session state starts over. */
    fun attach(profile: Profile, galaxy: GalaxyStore) {
        this.profile = profile
        this.galaxy = galaxy
        system = 1
        docked = null
        pad = 0
        grantStation = null
        reservedStation = null
        lastMine = Double.NaN
    }

    // ---------------------------------------------------------------- helpers
    private fun fail(reason: String): Reply {
        reply.ok = false
        reply.reason = reason
        return reply
    }

    private fun ok(): Reply {
        reply.ok = true
        reply.reason = null
        return reply
    }

    /** What the Roblox client shows when the server's argument checks drop a request. */
    private fun rejected(): Reply = fail(REJECTED)

    /** The active ship's CargoCapacity (unknown ships fly as a Varrow, as `shipProfile` does). */
    fun cargoCapacity(): Int = (Economy.ship(profile.activeShip) ?: Economy.SHIPS[0]).cargoCapacity

    /** `freePad(player)`. */
    private fun freePad() {
        reservedStation = null
        reservedPad = 0
    }

    /** `reservePad(player, station)`: the pad already held there, else a random free pad 1..6 (all are free in singleplayer). */
    private fun reservePad(stationId: String): Int {
        if (reservedStation == stationId && reservedPad > 0) return reservedPad
        val pad = padRandom.nextInteger(1, PAD_COUNT)
        reservedStation = stationId
        reservedPad = pad
        return pad
    }

    /** `dockedAt(player, id)`: null when docked at [stationId] in the current system, else the reason. */
    fun dockedAt(stationId: String?): String? {
        if (stationId == null || docked != stationId) return "NOT DOCKED HERE"
        if (catalog.find(system, stationId) == null) return "UNKNOWN STATION"
        return null
    }

    /** The definition of the station the pilot is docked at, or null. */
    fun dockedStation(): StationDef? = catalog.find(system, docked)

    // ---------------------------------------------------------------- start (GetProfile)
    /**
     * `GetProfile` (`Commerce.startSession`): the current system from the saved location, permit
     * and docking cleared, the system's galaxy record loaded (that registers player stations). A
     * pilot saved docked at a station that no longer exists goes back to MERIDIAN. A pilot docked
     * at an existing station gets a pad: [Reply.docked] and [Reply.pad] (the game then starts docked
     * there, DECISIONS 13 / 23). [Reply.now] is the clock.
     */
    fun startSession(): Reply {
        reply.reset()
        val location = profile.location
        system = location.system
        grantStation = null
        docked = null
        pad = 0
        galaxy.ensure(system)
        val saved = location.station
        if (location.docked && saved != null && catalog.find(system, saved) == null) {
            location.setHome()
            system = 1
            galaxy.ensure(system)
            profile.changed(urgent = true, quiet = true)
        }
        val station = location.station
        if (location.docked && station != null && catalog.find(system, station) != null) {
            val p = reservePad(station)
            if (p > 0) {
                docked = station
                pad = p
            }
        }
        reply.docked = docked
        reply.pad = pad
        reply.now = clock.now()
        return ok()
    }

    // ---------------------------------------------------------------- docking
    /** `RequestDocking(id)`: a pad and a 600 s permit (an unexpired permit for the same station is returned as is). */
    fun requestDocking(stationId: String): Reply {
        reply.reset()
        if (stationId.length > 40) return rejected()
        if (docked != null) return fail("ALREADY DOCKED")
        if (catalog.find(system, stationId) == null) return fail("UNKNOWN STATION")
        val t = clock.seconds()
        if (grantStation == stationId && t < grantExpires) {
            reply.pad = grantPad
            reply.seconds = grantExpires - t
            return ok()
        }
        freePad()
        val p = reservePad(stationId)
        if (p <= 0) return fail("NO PADS FREE")
        grantStation = stationId
        grantPad = p
        grantExpires = t + GRANT_SECONDS
        reply.pad = p
        reply.seconds = GRANT_SECONDS
        return ok()
    }

    /** `CancelDocking`: drops the permit (not while docked). Always ok. */
    fun cancelDocking(): Reply {
        reply.reset()
        if (grantStation != null && docked == null) {
            grantStation = null
            freePad()
        }
        return ok()
    }

    /** `Dock(id, fuel)`: needs the unexpired permit for this station; saves the location, the fuel and a docking. */
    fun dock(stationId: String, fuel: Double): Reply {
        reply.reset()
        if (stationId.length > 40 || !(fuel in 0.0..1.0)) return rejected()
        val grant = grantStation
        if (grant == null || grant != stationId || clock.seconds() > grantExpires) return fail("NO DOCKING PERMISSION")
        docked = stationId
        pad = grantPad
        grantStation = null
        profile.location.setDocked(system, stationId)
        profile.setFuel(profile.activeShip, fuel)
        profile.stats.dockings += 1
        profile.changed(urgent = true)
        reply.pad = pad
        return ok()
    }

    /** `Undock`: in flight again, the station remembered as LastStation. */
    fun undock(): Reply {
        reply.reset()
        val from = docked ?: return fail("NOT DOCKED")
        profile.location.setInFlight(system, from)
        docked = null
        pad = 0
        freePad()
        profile.changed(urgent = true)
        return ok()
    }

    // ---------------------------------------------------------------- market and trade
    /**
     * `GetMarket(id)` into [out]: the station's 17 rows at the current time with the pilot's hold,
     * cargo used and capacity. Does not need docking (the UI shows it); the station must be in the
     * current system.
     */
    fun market(stationId: String, out: Market): Reply {
        reply.reset()
        if (stationId.length > 40) {
            out.valid = false
            return rejected()
        }
        val station = catalog.find(system, stationId)
        if (station == null) {
            out.valid = false
            return fail("UNKNOWN STATION")
        }
        val now = clock.now()
        Economy.market(stationId, station.economy, now, out)
        for (row in out.rows) row.hold = profile.cargoAt(row.commodity.index)
        out.cargoUsed = profile.cargoUsed
        out.cargoCapacity = cargoCapacity()
        reply.now = now
        return ok()
    }

    /** `Trade(id, commodity, tons 1..2000, isBuy)` at the docked station's current price. */
    fun trade(stationId: String, commodityId: String, tons: Int, isBuy: Boolean): Reply {
        reply.reset()
        if (stationId.length > 40 || commodityId.length > 32 || tons !in 1..2000) return rejected()
        dockedAt(stationId)?.let { return fail(it) }
        val station = catalog.find(system, stationId)!!
        val commodity = Economy.commodity(commodityId) ?: return fail("UNKNOWN COMMODITY")
        val p = Economy.price(stationId, station.economy, commodity, clock.now().toDouble(), scratchPrice)
        val hold = profile.cargo(commodityId)
        if (isBuy) {
            if (profile.cargoUsed + tons > cargoCapacity()) return fail("CARGO HOLD FULL")
            val cost = p.buy.toLong() * tons
            if (profile.credits < cost) return fail("NOT ENOUGH CREDITS")
            profile.credits -= cost
            profile.setCargo(commodityId, hold + tons)
            profile.stats.spent += cost
        } else {
            if (hold < tons) return fail("NOT ENOUGH IN THE HOLD")
            val income = p.sell.toLong() * tons
            profile.credits += income
            profile.setCargo(commodityId, hold - tons)
            profile.stats.earned += income
        }
        profile.stats.trades += 1
        profile.changed(urgent = true)
        reply.credits = profile.credits
        reply.hold = profile.cargo(commodityId)
        reply.price = if (isBuy) p.buy else p.sell
        return ok()
    }

    // ---------------------------------------------------------------- refuel and data
    /**
     * `Refuel(id, fuel)`: 4 CR per unit of the 100-unit tank, as many as the credits pay for; the
     * tank counts as full when less than half a unit would be missing.
     */
    fun refuel(stationId: String, fuel: Double): Reply {
        reply.reset()
        if (stationId.length > 40 || !(fuel in 0.0..1.0)) return rejected()
        dockedAt(stationId)?.let { return fail(it) }
        val capacity = Economy.FUEL_CAPACITY
        val price = Economy.FUEL_PRICE_PER_UNIT
        val missing = max(0.0, (1 - fuel) * capacity)
        if (missing < 0.5) return fail("TANK ALREADY FULL")
        val units = min(missing, floor(profile.credits.toDouble() / price))
        if (units < 1) return fail("NOT ENOUGH CREDITS")
        val cost = ceil(units * price).toLong()
        profile.credits -= cost
        profile.stats.spent += cost
        var newFuel = min(1.0, fuel + units / capacity)
        if (missing - units < 0.5) newFuel = 1.0
        profile.setFuel(profile.activeShip, newFuel)
        profile.changed(urgent = true)
        reply.cost = cost
        reply.fuel = newFuel
        return ok()
    }

    /** `SellData(id)`: every unsold survey for its value. The bodies stay charted. */
    fun sellData(stationId: String): Reply {
        reply.reset()
        if (stationId.length > 40) return rejected()
        dockedAt(stationId)?.let { return fail(it) }
        val count = profile.survey.size
        if (count == 0) return fail("NO SURVEY DATA ON BOARD")
        val total = profile.surveyTotal
        profile.credits += total
        profile.stats.earned += total
        profile.clearSurvey()
        profile.changed(urgent = true)
        reply.earned = total
        reply.count = count
        return ok()
    }

    // ---------------------------------------------------------------- shipyard
    /** `shipyardCheck`: docked here, a shipyard, an empty hold; null when fine. */
    private fun shipyardCheck(stationId: String): String? {
        dockedAt(stationId)?.let { return it }
        val station = catalog.find(system, stationId)!!
        if (!station.shipyard) return "NO SHIPYARD HERE"
        if (profile.cargoUsed > 0) return "SELL YOUR CARGO FIRST"
        return null
    }

    /** `BuyShip(id, ship)`: pays the price, the new ship (full tank) becomes the active one. */
    fun buyShip(stationId: String, shipId: String): Reply {
        reply.reset()
        if (stationId.length > 40 || shipId.length > 32) return rejected()
        val ship = Economy.ship(shipId)
        if (ship == null || ship.testOnly) return fail("UNKNOWN SHIP")
        shipyardCheck(stationId)?.let { return fail(it) }
        if (profile.owns(shipId)) return fail("ALREADY OWNED")
        if (profile.credits < ship.price) return fail("NOT ENOUGH CREDITS")
        profile.credits -= ship.price
        profile.stats.spent += ship.price.toLong()
        profile.setShip(shipId, 1.0)
        profile.activeShip = shipId
        profile.changed(urgent = true)
        reply.ship = shipId
        return ok()
    }

    /** `SwitchShip(id, ship)`: an owned ship becomes the active one (its fuel as last saved). */
    fun switchShip(stationId: String, shipId: String): Reply {
        reply.reset()
        if (stationId.length > 40 || shipId.length > 32) return rejected()
        shipyardCheck(stationId)?.let { return fail(it) }
        val ship = Economy.ship(shipId)
        if (!profile.owns(shipId) || ship == null || ship.testOnly) return fail("NOT OWNED")
        if (profile.activeShip == shipId) return fail("ALREADY ACTIVE")
        profile.activeShip = shipId
        profile.changed(urgent = true)
        reply.ship = shipId
        return ok()
    }

    // ---------------------------------------------------------------- survey
    /**
     * `Survey(system, body)`: charts the body at once (a second call cannot pay twice), then the
     * value: [Economy.surveyValue], the active ship's SurveyValue perk (`round(v·perk/10)·10`), and
     * ×1.5 for the first survey of a body outside Sol (DECISIONS 25). The data goes into the hold.
     */
    fun survey(systemIndex: Int, bodyName: String): Reply {
        reply.reset()
        if (!catalog.isValidSystem(systemIndex) || bodyName.length > 60) return rejected()
        if (system != systemIndex) return fail("WRONG SYSTEM")
        val body = catalog.body(systemIndex, bodyName) ?: return fail("UNKNOWN BODY")
        val key = Profile.chartedKey(systemIndex, bodyName)
        if (profile.isCharted(systemIndex, bodyName)) {
            reply.key = key
            return fail("ALREADY CHARTED")
        }
        profile.setCharted(systemIndex, bodyName, true)
        val home = systemIndex == 1
        var value = Economy.surveyValue(body.cls, body.starClass, body.ring, body.landable, home)
        val perk = Economy.perk(profile.activeShip, Economy.PERK_SURVEY_VALUE)
        if (perk != 1.0) value = Economy.scaleValue(value, perk)
        val cls = if (body.isStar) "CLASS ${body.starClass ?: "G"} STAR" else body.cls.uppercase()
        galaxy.discover(systemIndex, bodyName, profile.pilotName(), Profile.USER_ID, discover)
        val first = discover.first
        if (first && !home) value = Economy.scaleValue(value, Economy.SURVEY_FIRST_BONUS)
        profile.addSurvey(SurveyEntry(systemIndex, catalog.systemName(systemIndex), bodyName, cls, value, first))
        profile.stats.surveys += 1
        profile.changed()
        reply.value = value
        reply.cls = cls
        reply.key = key
        reply.first = first
        discover.discovered?.let {
            reply.hasDiscovery = true
            reply.discoveredBy = it.by
            reply.discoveredAt = it.time
        }
        return ok()
    }

    // ---------------------------------------------------------------- reports from the flight
    /**
     * `ReportPosition(system, pos, look, fuel)` (every 10 s in flight): the pose to restore after
     * the app was killed (DECISIONS 23) and the fuel. Ignored while docked or for another system.
     * A quiet change (no revision bump, no urgent save).
     */
    fun reportPosition(
        systemIndex: Int, x: Double, y: Double, z: Double, lx: Double, ly: Double, lz: Double, fuel: Double,
        frame: Int = POSITION_FRAME,
    ) {
        if (!catalog.isValidSystem(systemIndex) || !(fuel in 0.0..1.0)) return
        if (!(x.isFinite() && y.isFinite() && z.isFinite() && lx.isFinite() && ly.isFinite() && lz.isFinite())) return
        if (docked != null || system != systemIndex) return
        val location = profile.location
        location.setPose(systemIndex, location.lastStation, x, y, z, lx, ly, lz, frame)
        profile.setFuel(profile.activeShip, fuel)
        profile.changed(urgent = false, quiet = true)
    }

    /**
     * `ReportJump(system)`: the new system (permit dropped), a jump in the stats, and the galaxy's
     * first visit: [Reply.firstVisit] when nobody had been there (the FIRST VISIT toast).
     */
    fun reportJump(systemIndex: Int): Reply {
        reply.reset()
        if (!catalog.isValidSystem(systemIndex)) return rejected()
        if (docked != null) return fail("DOCKED")
        system = systemIndex
        grantStation = null
        freePad()
        val location = profile.location
        location.setInFlight(systemIndex, location.lastStation)
        profile.stats.jumps += 1
        profile.changed(urgent = true)
        reply.firstVisit = galaxy.visit(systemIndex, profile.pilotName(), Profile.USER_ID)
        return ok()
    }

    /**
     * `ReportDestroyed`: the replacement fee `min(credits, round(price·0.05))`; cargo and unsold
     * survey data are lost, and the lost bodies can be surveyed again.
     */
    fun reportDestroyed(): Reply {
        reply.reset()
        grantStation = null
        if (docked == null) freePad()
        val ship = Economy.ship(profile.activeShip)
        val fee = min(profile.credits, Lua.roundToLong((ship?.price ?: 0) * Economy.REBUY_FRACTION))
        val lostCargo = profile.cargoUsed
        val lostData = profile.surveyTotal
        for (entry in profile.survey) profile.setCharted(entry.system, entry.body, false)
        profile.credits -= fee
        profile.stats.spent += fee
        profile.stats.losses += 1
        profile.clearCargo()
        profile.clearSurvey()
        profile.changed(urgent = true)
        reply.fee = fee
        reply.lostCargo = lostCargo
        reply.lostData = lostData
        return ok()
    }

    /** `FinishTraining(completed)`: training done, completed or skipped. */
    fun finishTraining(completed: Boolean): Reply {
        reply.reset()
        profile.training.done = true
        if (completed) profile.training.completed = true else profile.training.skipped = true
        profile.changed(urgent = true)
        return ok()
    }

    // ---------------------------------------------------------------- mining by hand (ShipConstruction Mine)
    /**
     * `Mine(system, body, resource)`: one ton into the hold. Landed in flight (not docked), not on an
     * inhabited world, on a landable body that has been surveyed and has that deposit, with room in
     * the hold, and not faster than 0.8 × the grade's mining time (÷ the MineSpeed perk).
     */
    fun mine(systemIndex: Int, bodyName: String, resourceId: String): Reply {
        reply.reset()
        if (!catalog.isValidSystem(systemIndex) || bodyName.length > 60 || resourceId.length > 32) return rejected()
        if (system != systemIndex) return fail("WRONG SYSTEM")
        if (docked != null) return fail("DOCKED")
        if (Resources.isInhabited(systemIndex, bodyName)) return fail("NO MINING ON INHABITED WORLDS")
        val info = catalog.body(systemIndex, bodyName)
        if (info == null || !info.landable) return fail("LAND ON THE BODY TO MINE IT")
        val record = galaxy.ensure(systemIndex)
        val body = record?.bodies?.get(bodyName) ?: return fail(if (record != null) "UNKNOWN BODY" else "GALAXY DATA UNAVAILABLE")
        if (body.discovered == null) return fail("SURVEY THIS BODY FIRST")
        val entry = Resources.findDeposit(body.deposits, resourceId) ?: return fail("NO SUCH DEPOSIT HERE")
        val capacity = cargoCapacity()
        if (profile.cargoUsed + 1 > capacity) return fail("CARGO HOLD FULL")
        val t = clock.seconds()
        val need = Resources.mineSeconds(entry.grade, 24) * Resources.MINING_SERVER_TOLERANCE /
            Economy.perk(profile.activeShip, Economy.PERK_MINE_SPEED)
        if (!lastMine.isNaN() && t - lastMine < need) return fail("TOO FAST")
        lastMine = t
        profile.addCargo(resourceId, 1)
        profile.stats.mined += 1
        profile.changed()
        reply.resource = resourceId
        reply.hold = profile.cargo(resourceId)
        reply.cargoUsed = profile.cargoUsed
        reply.capacity = capacity
        return ok()
    }

    // ---------------------------------------------------------------- own station (ShipConstruction)
    private var ownRecord: SystemRecord? = null
    private var ownSite: StationRecord? = null

    /**
     * `ownStation(player)`: the pointer, the system record and the station; null when all are there,
     * else the reason ("YOU DO NOT OWN A STATION", "GALAXY DATA UNAVAILABLE", "STATION NOT FOUND").
     */
    private fun checkOwnStation(): String? {
        ownRecord = null
        ownSite = null
        val own = profile.ownStation ?: return "YOU DO NOT OWN A STATION"
        val record = galaxy.ensure(own.system) ?: return "GALAXY DATA UNAVAILABLE"
        ownRecord = record
        val station = record.stations[own.id] ?: return "STATION NOT FOUND"
        ownSite = station
        return null
    }

    /** `exclusive`: one build action at a time; an error becomes "SERVER ERROR - TRY AGAIN" instead of a crash. */
    private inline fun exclusive(action: () -> Reply): Reply = try {
        action()
    } catch (e: RuntimeException) {
        fail("SERVER ERROR - TRY AGAIN")
    }

    /** The twelve station names offered for a charter at [bodyName] (`nameOptions(nameSeed(user, system, body))`). */
    fun nameOptions(systemIndex: Int, bodyName: String): List<String> =
        Resources.nameOptions(Resources.nameSeed(Profile.USER_ID, systemIndex, bodyName), Resources.NAME_COUNT, randoms)

    /**
     * `FoundStation(system, body, nameIndex 1..12)`: the 200,000 CR charter for a build site in the
     * first free orbit slot of a surveyed body with deposits. The charter is paid first and refunded
     * when no slot is free.
     */
    fun foundStation(systemIndex: Int, bodyName: String, nameIndex: Int): Reply = exclusive {
        reply.reset()
        if (!catalog.isValidSystem(systemIndex) || bodyName.length > 60 || nameIndex !in 1..Resources.NAME_COUNT) return@exclusive rejected()
        if (profile.ownStation != null) return@exclusive fail("YOU ALREADY OWN A STATION")
        if (system != systemIndex) return@exclusive fail("WRONG SYSTEM")
        if (Resources.isInhabited(systemIndex, bodyName)) return@exclusive fail("INHABITED WORLD - NO CHARTERS")
        val info = catalog.body(systemIndex, bodyName)
        if (info == null || info.isStar) return@exclusive fail("STATIONS ORBIT PLANETS AND MOONS")
        val record = galaxy.ensure(systemIndex) ?: return@exclusive fail("GALAXY DATA UNAVAILABLE")
        val body = record.bodies[bodyName]
        if (body == null || body.discovered == null) return@exclusive fail("SURVEY THIS BODY FIRST")
        if (body.deposits.isEmpty()) return@exclusive fail("NO DEPOSITS TO WORK HERE")
        if (profile.credits < Resources.CHARTER) return@exclusive fail("NOT ENOUGH CREDITS")
        val id = Resources.stationId(Profile.USER_ID)
        val names = nameOptions(systemIndex, bodyName)
        val name = names.getOrNull(nameIndex - 1) ?: return@exclusive fail("INVALID NAME")
        // the galaxy's stations at the same host keep their place
        val others = ArrayList<OrbitSlot>()
        for (s in catalog.baseForSystem(systemIndex)) if (s.host == bodyName) others.add(s)
        profile.credits -= Resources.CHARTER
        if (record.stations.containsKey(id)) {
            profile.credits += Resources.CHARTER
            return@exclusive fail("YOU ALREADY OWN A STATION")
        }
        for (s in record.stations.values) if (s.host == bodyName) others.add(s)
        val slot = Resources.pickSlot(info.radius, info.parent != null, others)
        if (slot == null) {
            profile.credits += Resources.CHARTER
            return@exclusive fail("NO FREE ORBIT LEFT AT THIS BODY")
        }
        val now = clock.now()
        val station = StationRecord(id).apply {
            this.name = name
            owner = profile.pilotName()
            ownerId = Profile.USER_ID
            host = bodyName
            economy = Resources.economyFor(body.deposits)
            this.slot = slot.index
            angle = slot.angle
            elevation = slot.elevation
            distance = slot.distance
            founded = now
            stage = 1
        }
        record.stations[id] = station
        galaxy.touch(systemIndex)
        profile.stats.spent += Resources.CHARTER
        profile.ownStation = OwnStation(id, systemIndex, bodyName)
        profile.changed(urgent = true)
        reply.id = id
        reply.name = name
        reply.system = systemIndex
        reply.body = bodyName
        ok()
    }

    /**
     * `Deliver()`: whatever the hold carries of the current stage's missing materials (the 3 km
     * distance rule is the panel's, never checked here). The offer leaves the hold, the site takes
     * what it misses, the rest comes back; the build clock starts when the stage is complete.
     */
    fun deliver(): Reply = exclusive {
        reply.reset()
        checkOwnStation()?.let { return@exclusive fail(it) }
        val own = profile.ownStation!!
        val station = ownSite!!
        if (system != own.system) return@exclusive fail("YOUR SITE IS IN ANOTHER SYSTEM")
        if (docked != null) return@exclusive fail("LAUNCH FIRST")
        val now = clock.now()
        if (Resources.isOperational(station, now)) return@exclusive fail("STATION ALREADY COMPLETE")
        if (station.building && now < station.buildEnds) return@exclusive fail("CONSTRUCTION IN PROGRESS")
        // the current stage (if the build time just ran out)
        val probe = station.copy()
        Resources.advance(probe, now)
        val offered = LinkedHashMap<String, Int>()
        val n = Resources.needs(probe, needRows)
        for (i in 0 until n) {
            val need = needRows[i]
            val give = min(need.missing, profile.cargo(need.id))
            if (give > 0) offered[need.id] = give
        }
        if (offered.isEmpty()) return@exclusive fail("NOTHING IN YOUR HOLD FOR THIS STAGE")
        for ((id, tons) in offered) profile.addCargo(id, -tons)
        val advanced = Resources.advance(station, now)
        Resources.deliver(station, offered, now, reply.taken)
        if (advanced || reply.taken.isNotEmpty()) {
            galaxy.touch(own.system)
            if (advanced) galaxy.syncStations(own.system)
        }
        // what was not taken goes back into the hold
        for ((id, tons) in offered) {
            val back = tons - (reply.taken[id] ?: 0)
            if (back > 0) profile.addCargo(id, back)
        }
        if (reply.taken.isEmpty()) {
            profile.changed()
            return@exclusive fail("NOTHING NEEDED RIGHT NOW")
        }
        profile.changed(urgent = true)
        reply.stage = station.stage
        reply.buildEnds = station.buildEnds
        reply.now = now
        ok()
    }

    /** `Abandon()`: gives up an unfinished site (no refund); a pointer to a missing site is cleared too. */
    fun abandon(): Reply = exclusive {
        reply.reset()
        val why = checkOwnStation()
        val own = profile.ownStation ?: return@exclusive fail(why ?: "YOU DO NOT OWN A STATION")
        val station = ownSite
        if (station == null && why != "STATION NOT FOUND") return@exclusive fail(why ?: "GALAXY DATA UNAVAILABLE")
        if (station != null) {
            if (Resources.isOperational(station, clock.now())) return@exclusive fail("A FINISHED STATION STAYS")
            galaxy.removeStation(own.system, own.id)
        }
        profile.ownStation = null
        profile.changed(urgent = true)
        ok()
    }

    /** Checks shared by [collect] and [sellStorage]: own station, docked at it, operational; null when fine. */
    private fun storageCheck(): String? {
        checkOwnStation()?.let { return it }
        val own = profile.ownStation!!
        if (dockedAt(own.id) != null) return "DOCK AT YOUR STATION FIRST"
        if (!Resources.isOperational(ownSite!!, clock.now())) return "STATION NOT FINISHED"
        return null
    }

    /** `Collect()`: loads what fits of the storage into the hold, most valuable first. */
    fun collect(): Reply = exclusive {
        reply.reset()
        storageCheck()?.let { return@exclusive fail(it) }
        val own = profile.ownStation!!
        val station = ownSite!!
        val room = cargoCapacity() - profile.cargoUsed
        if (room < 1) return@exclusive fail("CARGO HOLD FULL")
        val now = clock.now()
        Resources.advance(station, now)
        Resources.accrue(station, ownRecord?.bodies?.get(station.host)?.deposits, now)
        Resources.take(station, room.toDouble(), reply.taken)
        galaxy.touch(own.system)
        if (reply.taken.isEmpty()) return@exclusive fail("STORAGE EMPTY")
        for ((id, tons) in reply.taken) profile.addCargo(id, tons)
        profile.changed(urgent = true)
        ok()
    }

    /** `SellStorage()`: sells the whole storage at the own station's sell prices. */
    fun sellStorage(): Reply = exclusive {
        reply.reset()
        storageCheck()?.let { return@exclusive fail(it) }
        val own = profile.ownStation!!
        val station = ownSite!!
        val now = clock.now()
        Resources.advance(station, now)
        Resources.accrue(station, ownRecord?.bodies?.get(station.host)?.deposits, now)
        val sold = Resources.take(station, Double.POSITIVE_INFINITY, reply.taken)
        galaxy.touch(own.system)
        var income = 0L
        var tons = 0
        for ((id, amount) in sold) {
            val sell = if (Economy.price(own.id, station.economy, id, now.toDouble(), scratchPrice)) scratchPrice.sell else 0
            income += sell.toLong() * amount
            tons += amount
        }
        if (tons == 0) return@exclusive fail("STORAGE EMPTY")
        profile.credits += income
        profile.stats.earned += income
        profile.stats.trades += 1
        profile.changed(urgent = true)
        reply.income = income
        reply.tons = tons
        ok()
    }

    // ---------------------------------------------------------------- debug (harness; Roblox Studio hooks)
    /** Sets the credits (the Roblox owner top-up to 999,999 CR, as a debug command). */
    fun debugSetCredits(credits: Long): Reply {
        reply.reset()
        profile.credits = max(0L, credits)
        profile.changed(urgent = true)
        reply.credits = profile.credits
        return ok()
    }

    /** `cargo <id> <t>`: adds (or removes) tons of a commodity, at least 0 stays. */
    fun debugCargo(commodityId: String, tons: Int): Reply {
        reply.reset()
        if (Economy.commodity(commodityId) == null) return fail("UNKNOWN COMMODITY")
        profile.setCargo(commodityId, max(0, profile.cargo(commodityId) + tons))
        profile.changed()
        reply.hold = profile.cargo(commodityId)
        return ok()
    }

    /**
     * Construction debug hooks: "fill" (deliver everything the stage misses), "skip" (end the
     * running build now), "age <hours>" (the storage grows older). [Reply.stage] and
     * [Reply.buildEnds] show the result.
     */
    fun debugSite(command: String, hours: Double = 1.0): Reply {
        reply.reset()
        val own = profile.ownStation ?: return fail("YOU DO NOT OWN A STATION")
        val target = galaxy.ensure(own.system)?.stations?.get(own.id) ?: return fail("STATION NOT FOUND")
        val now = clock.now()
        when (command) {
            "fill" -> {
                val offer = LinkedHashMap<String, Int>()
                val n = Resources.needs(target, needRows)
                for (i in 0 until n) offer[needRows[i].id] = needRows[i].missing
                Resources.deliver(target, offer, now, LinkedHashMap())
            }
            "skip" -> if (target.building) {
                target.buildEnds = now
                Resources.advance(target, now)
            }
            "age" -> if (target.storageAt != StationRecord.NONE) target.storageAt -= (hours * 3600).toLong()
            else -> return fail("UNKNOWN COMMAND")
        }
        galaxy.touch(own.system)
        galaxy.syncStations(own.system)
        reply.stage = target.stage
        reply.buildEnds = target.buildEnds
        return ok()
    }

    companion object {
        /** Pads per station. */
        const val PAD_COUNT = 6

        /** ShipConfig.Stations.GrantSeconds: the docking permit (s). */
        const val GRANT_SECONDS = 600.0

        /** What the Roblox client shows when the server drops a request (ShipProfile noAnswer). */
        const val REJECTED = "NO RESPONSE - TRY AGAIN"

        /**
         * Version of the coordinates [reportPosition] saves (ShipConfig.Ship.PositionFrame in Roblox):
         * a saved pose of another frame is stale. Bump it when the world's coordinates change meaning.
         */
        const val POSITION_FRAME = 1
    }
}
