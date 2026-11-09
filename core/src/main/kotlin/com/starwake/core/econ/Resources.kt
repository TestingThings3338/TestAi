package com.starwake.core.econ

import com.starwake.core.math.DVec3
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import com.starwake.core.station.BodyInfo
import com.starwake.core.station.OrbitSlot
import com.starwake.core.station.StationCatalog
import com.starwake.core.station.StationDef
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** A mineable resource (ShipResources.Resources); [index] is its Order - 1. */
class Resource(val index: Int, val id: String, val name: String, val rich: String, val plain: String)

/** A build stage of an own station: name, build time, the deliveries it needs, and its info line. */
class Stage(val name: String, val seconds: Long, val needIds: Array<String>, val needTons: IntArray, val info: String)

/** One row of [Resources.needs]: `{ Id, Need, Delivered, Missing }`. */
class NeedRow {
    var id: String = ""
    var need = 0
    var delivered = 0
    var missing = 0
}

/** A free orbit slot at a host ([Resources.slot]). */
class Slot(val index: Int, override val angle: Double, override val elevation: Double, override val distance: Double) : OrbitSlot

/**
 * Port of ShipCore/ShipResources.lua: deposits (Sol by hand, other systems rolled from the seed),
 * their value letter, trait and best hand-mining choice, the own-station charter and its four build
 * stages, the lazily accrued storage, player station names and orbit slots. Pure rules; the
 * galaxy store keeps the records and the commerce engine calls these.
 *
 * Rolls use Roblox `Random` through [LuaRandomFactory] in the Lua's call order (DECISIONS 10). The
 * list-returning functions allocate (they run on survey, deliver and button presses); the per-frame
 * readers ([needs], [builtStages], [isOperational], [hourly], [letter]) do not.
 */
object Resources {
    // ---------------------------------------------------------------- resources and grades (:45)
    val RESOURCES = arrayOf(
        Resource(0, "MetalOre", "METAL ORE", "METAL-RICH", "METAL DEPOSITS"),
        Resource(1, "MineralOre", "MINERAL ORE", "MINERAL-RICH", "MINERAL DEPOSITS"),
        Resource(2, "WaterIce", "WATER ICE", "ICE-RICH", "ICE DEPOSITS"),
        Resource(3, "Helium3", "HELIUM-3", "HELIUM-3 RICH", "HELIUM-3 CLOUDS"),
        Resource(4, "PreciousOre", "PRECIOUS ORE", "PRECIOUS-RICH", "PRECIOUS ORE"),
    )

    fun resource(id: String?): Resource? {
        if (id == null) return null
        for (r in RESOURCES) if (r.id == id) return r
        return null
    }

    /** Grade words 1..5 (GRADES[grade - 1]). */
    val GRADES = arrayOf("POOR", "FAIR", "GOOD", "RICH", "EXCEPTIONAL")

    /** The grade's word, or "" out of range (as `Grades[g] or ""`). */
    fun gradeName(grade: Int): String = if (grade in 1..GRADES.size) GRADES[grade - 1] else ""

    /** Hand mining: seconds per ton by grade (a Kessock divides by its MineSpeed perk). */
    val MINE_SECONDS = intArrayOf(24, 18, 14, 11, 8)

    /** A finished station: tons per hour per deposit by grade. */
    val STATION_RATE = intArrayOf(2, 4, 6, 9, 12)

    /** Own station storage, all resources together (t). */
    const val STORAGE_CAP = 200.0

    /** ShipResources.Mining: highest above the ground (m), fastest (m/s), the server's share of the mining time. */
    const val MINING_MAX_HEIGHT = 120.0
    const val MINING_MAX_SPEED = 40.0
    const val MINING_SERVER_TOLERANCE = 0.8

    /** `MineSeconds[grade] or fallback`. */
    fun mineSeconds(grade: Int, fallback: Int): Int = if (grade in 1..MINE_SECONDS.size) MINE_SECONDS[grade - 1] else fallback

    /** `StationRate[grade] or 0`. */
    fun stationRate(grade: Int): Int = if (grade in 1..STATION_RATE.size) STATION_RATE[grade - 1] else 0

    // ---------------------------------------------------------------- Sol (:86)
    private fun d(id: String, grade: Int) = Deposit(id, grade)

    /** Sol's hand-set deposits as listed in the Lua (unsorted; [roll] sorts them). Earth has none. */
    val SOL_DEPOSITS: Map<String, List<Deposit>> = linkedMapOf(
        "Mercury" to listOf(d("MetalOre", 5), d("MineralOre", 3), d("PreciousOre", 2)),
        "Venus" to listOf(d("MineralOre", 3), d("MetalOre", 2)),
        "Earth" to listOf(),
        "Luna" to listOf(d("MineralOre", 3), d("Helium3", 2), d("MetalOre", 1)),
        "Mars" to listOf(d("MetalOre", 3), d("MineralOre", 3), d("WaterIce", 2)),
        "Phobos" to listOf(d("MetalOre", 2), d("MineralOre", 2)),
        "Deimos" to listOf(d("MineralOre", 2), d("WaterIce", 1)),
        "Jupiter" to listOf(d("Helium3", 4)),
        "Io" to listOf(d("MineralOre", 4), d("MetalOre", 2), d("PreciousOre", 1)),
        "Europa" to listOf(d("WaterIce", 5), d("MineralOre", 1)),
        "Ganymede" to listOf(d("WaterIce", 4), d("MineralOre", 2), d("MetalOre", 1)),
        "Callisto" to listOf(d("WaterIce", 3), d("MineralOre", 2)),
        "Saturn" to listOf(d("Helium3", 4)),
        "Titan" to listOf(d("WaterIce", 3), d("MineralOre", 2), d("Helium3", 1)),
        "Uranus" to listOf(d("Helium3", 4), d("WaterIce", 3)),
        "Neptune" to listOf(d("Helium3", 3), d("WaterIce", 3)),
        "Triton" to listOf(d("WaterIce", 4), d("MineralOre", 1)),
    )

    /** Inhabited worlds: no mining, no charter (`Inhabited["1/Earth"]`). */
    private val INHABITED_SYSTEMS = intArrayOf(1)
    private val INHABITED_BODIES = arrayOf("Earth")

    /** `isInhabited(system, body)` without building the "<system>/<body>" key. */
    fun isInhabited(systemIndex: Int, bodyName: String?): Boolean {
        for (i in INHABITED_SYSTEMS.indices) if (INHABITED_SYSTEMS[i] == systemIndex && INHABITED_BODIES[i] == bodyName) return true
        return false
    }

    // ---------------------------------------------------------------- rolled deposits (:114)
    /** `{ resource, lowest grade, highest grade }` (grade 0 = may be missing). */
    class Range(val id: String, val low: Int, val high: Int)

    private fun r(id: String, low: Int, high: Int) = Range(id, low, high)

    /** Per planet class, in roll order. */
    val CLASS_RANGES: Map<String, Array<Range>> = linkedMapOf(
        "Terrestrial World" to arrayOf(r("MineralOre", 1, 3), r("MetalOre", 1, 2), r("WaterIce", 0, 2)),
        "Ocean World" to arrayOf(r("WaterIce", 2, 4), r("MineralOre", 0, 2)),
        "Greenhouse World" to arrayOf(r("MineralOre", 2, 4), r("MetalOre", 1, 3)),
        "Gas Giant" to arrayOf(r("Helium3", 2, 5)),
        "Ice Giant" to arrayOf(r("Helium3", 2, 4), r("WaterIce", 2, 4)),
        "Volcanic Body" to arrayOf(r("MetalOre", 2, 5), r("MineralOre", 2, 4), r("PreciousOre", 0, 3)),
        "Ice World" to arrayOf(r("WaterIce", 3, 5), r("MineralOre", 0, 2)),
        "Rocky Body" to arrayOf(r("MetalOre", 1, 5), r("MineralOre", 1, 4), r("PreciousOre", 0, 2)),
    )

    /** Moons (and any class without ranges) first roll a type: ice, rock, or dust with helium-3. */
    val MOON_TYPES: Array<Array<Range>> = arrayOf(
        arrayOf(r("WaterIce", 1, 5), r("MineralOre", 0, 2)),
        arrayOf(r("MetalOre", 1, 4), r("MineralOre", 1, 3), r("PreciousOre", 0, 1)),
        arrayOf(r("MineralOre", 1, 3), r("Helium3", 0, 2), r("MetalOre", 0, 2)),
    )

    /** High grades are rarer: the roll is `u ^ SKEW`. */
    const val SKEW = 1.35

    /** Chance that the best deposit is one grade better. */
    const val BONANZA = 0.08

    /** `seedOf(text)`: djb2 (h = 5381, h·33 + byte) mod 2147483647 over the UTF-8 bytes. */
    fun seedOf(text: CharSequence): Long = Lua.hash(5381L, 33L, 2_147_483_647L, text)

    /** seedOf(prefix .. system .. "/" .. body) streamed. */
    private fun seedOf(prefix: String, systemIndex: Int, bodyName: String): Long {
        var h = Lua.hash(5381L, 33L, 2_147_483_647L, prefix)
        h = Lua.hashInt(h, 33L, 2_147_483_647L, systemIndex.toLong())
        h = Lua.hashByte(h, 33L, 2_147_483_647L, '/'.code)
        return Lua.hash(h, 33L, 2_147_483_647L, bodyName)
    }

    /** `depositHourly(entry)`: CR per hour a finished station makes from it, at base prices. */
    fun depositHourly(entry: Deposit): Int = stationRate(entry.grade) * (Economy.commodity(entry.id)?.base ?: 0)

    private val DEPOSIT_ORDER = Comparator<Deposit> { a, b ->
        val va = depositHourly(a)
        val vb = depositHourly(b)
        if (va != vb) vb.compareTo(va) else (resource(a.id)?.index ?: 98).compareTo(resource(b.id)?.index ?: 98)
    }

    /** `sort(list)`: most valuable per hour first, then resource order. Sorts in place and returns [list]. */
    fun sort(list: MutableList<Deposit>): MutableList<Deposit> {
        list.sortWith(DEPOSIT_ORDER)
        return list
    }

    /**
     * `roll(system, body, info)`: the deposits of a body. Stars have none; Sol comes from
     * [SOL_DEPOSITS]; every other body rolls with `Random(seedOf("<system>/<body>"))`: a moon type
     * first (NextInteger(1, 3)) when its class has no ranges, then one NextNumber per range (always
     * drawn, even for a missing deposit, so the sequence stays fixed), then, if any deposit exists,
     * one more NextNumber for the bonanza. The galaxy store freezes the result on the first visit.
     */
    fun roll(systemIndex: Int, bodyName: String, body: BodyInfo?, randoms: LuaRandomFactory = Pcg32Random.FACTORY): MutableList<Deposit> {
        val list = ArrayList<Deposit>()
        if (body == null || body.isStar) return list
        if (systemIndex == 1) {
            SOL_DEPOSITS[bodyName]?.let { list.addAll(it) }
            return sort(list)
        }
        val rng = randoms.create(seedOf("", systemIndex, bodyName))
        val ranges = CLASS_RANGES[body.cls] ?: MOON_TYPES[rng.nextInteger(1, MOON_TYPES.size) - 1]
        for (range in ranges) {
            val u = rng.nextNumber().pow(SKEW)
            val grade = min(range.high, range.low + floor(u * (range.high - range.low + 1)).toInt())
            if (grade > 0) list.add(Deposit(range.id, grade))
        }
        sort(list)
        if (list.isNotEmpty() && rng.nextNumber() < BONANZA) {
            val top = list[0]
            list[0] = Deposit(top.id, min(5, top.grade + 1))
        }
        return list
    }

    // ---------------------------------------------------------------- value, letter, trait (:217)
    private val LETTERS = arrayOf("A", "B", "C", "D")
    private val LETTER_MIN = intArrayOf(6000, 4000, 2500, 1200)

    /** `hourly(deposits)`: CR per hour of a finished station at full output. */
    fun hourly(deposits: List<Deposit>?): Int {
        if (deposits == null) return 0
        var total = 0
        for (i in deposits.indices) total += depositHourly(deposits[i])
        return total
    }

    /** `letter(hourly)`: A ≥ 6000, B ≥ 4000, C ≥ 2500, D ≥ 1200, E > 0, else "-". */
    fun letter(hourly: Int): String {
        for (i in LETTERS.indices) if (hourly >= LETTER_MIN[i]) return LETTERS[i]
        return if (hourly > 0) "E" else "-"
    }

    /**
     * `trait(deposits, inhabited)`: "INHABITED", "NO DEPOSITS", the Rich trait of the first deposit
     * with the highest grade when that grade is ≥ 4, "MIXED DEPOSITS" for 3+, "POOR DEPOSITS" for
     * letter E, else the Plain trait of the top deposit.
     */
    fun trait(deposits: List<Deposit>?, inhabited: Boolean): String {
        if (inhabited) return "INHABITED"
        if (deposits.isNullOrEmpty()) return "NO DEPOSITS"
        val top = deposits[0]
        var richest = top
        for (i in deposits.indices) if (deposits[i].grade > richest.grade) richest = deposits[i]
        val rich = resource(richest.id)
        if (richest.grade >= 4 && rich != null) return rich.rich
        val res = resource(top.id) ?: return "UNKNOWN"
        if (deposits.size >= 3) return "MIXED DEPOSITS"
        if (letter(hourly(deposits)) == "E") return "POOR DEPOSITS"
        return res.plain
    }

    /** `economyFor(deposits)`: an own station refines gas (top deposit Helium-3), mines anything else, or serves crews. */
    fun economyFor(deposits: List<Deposit>?): String {
        val top = deposits?.firstOrNull() ?: return "Service"
        return if (top.id == "Helium3") "Refinery" else "Extraction"
    }

    /** `bestToMine(deposits)`: the most CR per second by hand (base / MineSeconds[grade]); first wins ties. */
    fun bestToMine(deposits: List<Deposit>?): Deposit? {
        if (deposits == null) return null
        var best: Deposit? = null
        var bestValue = -1.0
        for (i in deposits.indices) {
            val e = deposits[i]
            val value = (Economy.commodity(e.id)?.base ?: 0).toDouble() / mineSeconds(e.grade, 99)
            if (value > bestValue) {
                best = e
                bestValue = value
            }
        }
        return best
    }

    fun findDeposit(deposits: List<Deposit>?, resourceId: String?): Deposit? {
        if (deposits == null) return null
        for (i in deposits.indices) if (deposits[i].id == resourceId) return deposits[i]
        return null
    }

    // ---------------------------------------------------------------- charter and stages (:304)
    const val CHARTER = 200_000L

    val STAGES = arrayOf(
        Stage(
            "FRAME", 30 * 60L, arrayOf("MetalOre", "ConstructionAlloys"), intArrayOf(16, 8),
            "THE SPINE AND SCAFFOLD. EVERYTHING ELSE IS BOLTED TO IT.",
        ),
        Stage(
            "CORE", 40 * 60L, arrayOf("MineralOre", "ConstructionAlloys", "Water"), intArrayOf(16, 12, 8),
            "REACTOR, LIFE SUPPORT AND THE COMMAND HUB.",
        ),
        Stage(
            "HABITAT RING", 50 * 60L, arrayOf("MetalOre", "Titanium", "RoboticsParts"), intArrayOf(16, 8, 4),
            "THE SPINNING RING WHERE THE CREW LIVES AND WORKS.",
        ),
        Stage(
            "DOCKING BAY", 60 * 60L, arrayOf("Copper", "Microprocessors", "MedicalSupplies", "Grain"), intArrayOf(8, 4, 4, 8),
            "PADS, TRAFFIC CONTROL AND A STOCKED CREW. THEN THE STATION OPENS.",
        ),
    )

    /** Most needs a stage has (size for [needs] out-arrays). */
    const val MAX_NEEDS = 4

    /** `Stages[stage]`, or null outside 1..4. */
    fun stage(stage: Int): Stage? = if (stage in 1..STAGES.size) STAGES[stage - 1] else null

    // ---------------------------------------------------------------- names (:334)
    const val NAME_COUNT = 12
    val NAME_FIRST = arrayOf(
        "ALDER", "BASTION", "CINDER", "DRIFTWOOD", "EMBER", "FALLOW", "GRANITE", "HALCYON", "IRONWOOD",
        "JUNIPER", "KESTREL", "LANTERN", "MAGPIE", "NORTHWIND", "ONYX", "PILGRIM", "QUARRY", "RAVEN",
        "SABLE", "TEMPEST", "UMBER", "VESPER", "WARDEN", "YARROW", "ZENITH", "AURORA", "BRIMSTONE",
        "CORMORANT", "DUSKFALL", "FARHOLD", "GLACIER", "HEARTHSTONE", "LODESTAR", "NIGHTJAR", "OSPREY",
        "PEREGRINE", "SKYLARK", "THISTLE", "WAYPOINT", "COPPERLEAF",
    )
    val NAME_LAST = arrayOf("OUTPOST", "STATION", "HOLD", "REACH", "ANCHORAGE", "PLATFORM", "DEPOT", "HAVEN", "WORKS", "LANDING")

    /**
     * `nameOptions(seed, count)`: up to `count·20` draws of FIRST[NextInteger(1, 40)] + " " +
     * LAST[NextInteger(1, 10)] from `Random(seedOf("NAME/" .. seed))`, keeping unique names until
     * there are [count]. No free text, so there is nothing to filter.
     */
    fun nameOptions(seedText: String, count: Int = NAME_COUNT, randoms: LuaRandomFactory = Pcg32Random.FACTORY): List<String> {
        val rng = randoms.create(seedOf("NAME/$seedText"))
        val names = ArrayList<String>(count)
        val seen = HashSet<String>()
        for (n in 1..count * 20) {
            if (names.size >= count) break
            val name = NAME_FIRST[rng.nextInteger(1, NAME_FIRST.size) - 1] + " " + NAME_LAST[rng.nextInteger(1, NAME_LAST.size) - 1]
            if (seen.add(name)) names.add(name)
        }
        return names
    }

    /** `nameSeed(userId, system, body)` = "<userId>/<system>/<body>": same pilot, same place, same names. */
    fun nameSeed(userId: Long, systemIndex: Int, bodyName: String): String = "$userId/$systemIndex/$bodyName"

    // ---------------------------------------------------------------- build rules (:367)
    /**
     * `needs(station)` into [out] (size ≥ [MAX_NEEDS]): what the current stage needs, `{ Id, Need,
     * Delivered = min(Need, delivered), Missing }`. Returns the row count (0 once operational).
     */
    fun needs(station: StationRecord, out: Array<NeedRow>): Int {
        val stage = stage(station.stage)
        if (stage == null || station.operational) return 0
        for (i in stage.needIds.indices) {
            val id = stage.needIds[i]
            val tons = stage.needTons[i]
            val have = min(tons, station.delivered[id] ?: 0)
            val row = out[i]
            row.id = id
            row.need = tons
            row.delivered = have
            row.missing = tons - have
        }
        return stage.needIds.size
    }

    /** Fresh rows for [needs] (one allocation; keep and reuse it). */
    fun needRows(): Array<NeedRow> = Array(MAX_NEEDS) { NeedRow() }

    private val scratchNeeds = needRows()

    /** `allDelivered(station)`: nothing missing in the current stage. */
    fun allDelivered(station: StationRecord): Boolean {
        val n = needs(station, scratchNeeds)
        for (i in 0 until n) if (scratchNeeds[i].missing > 0) return false
        return true
    }

    /**
     * `deliver(station, offered, now)`: takes at most what the stage misses (whole tons) from
     * [offered] (commodity id -> tons) into [taken] (cleared first), and starts the build clock
     * (`BuildEnds = now + Seconds`) once the stage is complete. Nothing while building or operational.
     */
    fun deliver(station: StationRecord, offered: Map<String, Int>, now: Long, taken: MutableMap<String, Int>): MutableMap<String, Int> {
        taken.clear()
        if (station.operational || station.building) return taken
        val rows = needRows()
        val n = needs(station, rows)
        for (i in 0 until n) {
            val need = rows[i]
            val give = min(need.missing, offered[need.id] ?: 0)
            if (give > 0) {
                station.delivered[need.id] = (station.delivered[need.id] ?: 0) + give
                taken[need.id] = give
            }
        }
        if (taken.isNotEmpty() && allDelivered(station)) {
            station.buildEnds = now + (stage(station.stage) ?: STAGES[0]).seconds
        }
        return taken
    }

    /**
     * `advance(station, now)`: once the build time is over, the next stage (deliveries cleared), or,
     * after the last, an operational station with empty storage from that moment. One step per call;
     * true when something changed.
     */
    fun advance(station: StationRecord, now: Long): Boolean {
        if (station.operational || !station.building || now < station.buildEnds) return false
        val finished = station.buildEnds
        station.buildEnds = StationRecord.NONE
        station.delivered.clear()
        if (station.stage >= STAGES.size) {
            station.stage = STAGES.size
            station.operational = true
            station.operationalAt = finished
            station.storage = LinkedHashMap()
            station.storageAt = finished
        } else {
            station.stage = station.stage + 1
        }
        return true
    }

    /** `isOperational(station, now)`: finished, even if the record does not know it yet. */
    fun isOperational(station: StationRecord, now: Long): Boolean {
        if (station.operational) return true
        return station.stage >= STAGES.size && station.building && now >= station.buildEnds
    }

    /** `builtStages(station, now)`: finished stages 0..4 at [now]. */
    fun builtStages(station: StationRecord, now: Long): Int {
        if (station.operational) return STAGES.size
        var built = station.stage - 1
        if (station.building && now >= station.buildEnds) built += 1
        return built
    }

    /**
     * `accrue(station, deposits, now)`: the drills' output since StorageAt, all deposits at their
     * StationRate, until the storage holds [STORAGE_CAP] t; time spent full is lost. Operational
     * stations only; true when the record changed.
     */
    fun accrue(station: StationRecord, deposits: List<Deposit>?, now: Long): Boolean {
        if (!station.operational) return false
        val from = if (station.storageAt != StationRecord.NONE) station.storageAt else now
        val hours = max(0L, now - from).toDouble() / 3600.0
        val storage = station.storage ?: LinkedHashMap<String, Double>().also { station.storage = it }
        station.storageAt = now
        if (hours <= 0) return false
        var total = 0.0
        for (tons in storage.values) total += tons
        var rate = 0
        if (deposits != null) for (i in deposits.indices) rate += stationRate(deposits[i].grade)
        val room = max(0.0, STORAGE_CAP - total)
        if (rate <= 0 || room <= 0) return true
        val run = min(hours, room / rate)
        for (i in deposits!!.indices) {
            val e = deposits[i]
            val gain = stationRate(e.grade) * run
            storage[e.id] = (storage[e.id] ?: 0.0) + gain
        }
        return true
    }

    fun storageTotal(station: StationRecord): Double {
        var total = 0.0
        station.storage?.let { for (tons in it.values) total += tons }
        return total
    }

    /**
     * `take(station, room)`: up to [room] whole tons out of storage into [taken] (cleared first),
     * the most valuable resource first (base price, then id); emptied entries disappear.
     */
    fun take(station: StationRecord, room: Double, taken: MutableMap<String, Int>): MutableMap<String, Int> {
        taken.clear()
        val storage = station.storage ?: return taken
        val order = ArrayList(storage.keys)
        order.sortWith { a, b ->
            val ba = Economy.commodity(a)?.base ?: 0
            val bb = Economy.commodity(b)?.base ?: 0
            if (ba != bb) bb.compareTo(ba) else a.compareTo(b)
        }
        var left = room
        for (id in order) {
            if (left <= 0) break
            val whole = min(left, floor(storage[id] ?: 0.0))
            if (whole > 0) {
                val rest = (storage[id] ?: 0.0) - whole
                if (rest < 1e-6) storage.remove(id) else storage[id] = rest
                taken[id] = whole.toInt()
                left -= whole
            }
        }
        return taken
    }

    // ---------------------------------------------------------------- orbit slots (:532)
    const val SLOT_COUNT = 8

    /** Least distance (studs) between two stations at one host, for new charters. */
    const val SLOT_CLEARANCE = 2400.0

    /** `slot(i, r, isMoon)`: angle (i-1)·45 + 22.5, elevation ±28 (moons ±55), alternating, distance floor(r·2.1 + 2200 (moons 1600)). */
    fun slot(index: Int, hostRadius: Double, isMoon: Boolean): Slot {
        val up = if (index % 2 == 1) 1 else -1
        return Slot(
            index,
            (index - 1) * 45 + 22.5,
            ((if (isMoon) 55 else 28) * up).toDouble(),
            floor(hostRadius * 2.1 + (if (isMoon) 1600 else 2200)),
        )
    }

    /**
     * `pickSlot(r, isMoon, others)`: the first of the eight slots at least [SLOT_CLEARANCE] studs
     * from every station and site already at the host ([others]), measured as ShipStations.offset
     * with the host at the origin and the star at (0, 0, -1e7); null when all are taken ("NO FREE
     * ORBIT LEFT AT THIS BODY").
     */
    fun pickSlot(hostRadius: Double, isMoon: Boolean, others: List<OrbitSlot>): Slot? {
        val blocked = Array(others.size) { DVec3() }
        for (i in others.indices) StationCatalog.offset(others[i], 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, blocked[i])
        val p = DVec3()
        for (index in 1..SLOT_COUNT) {
            val slot = slot(index, hostRadius, isMoon)
            StationCatalog.offset(slot, 0.0, 0.0, 0.0, 0.0, 0.0, -1e7, p)
            var free = true
            for (b in blocked) {
                val dx = b.x - p.x
                val dy = b.y - p.y
                val dz = b.z - p.z
                if (kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) < SLOT_CLEARANCE) {
                    free = false
                    break
                }
            }
            if (free) return slot
        }
        return null
    }

    // ---------------------------------------------------------------- player station definitions (:576)
    private fun ownBlurb(economy: String?, host: String): String = when (economy) {
        "Extraction" -> "ITS DRILLS WORK THE DEPOSITS OF $host AROUND THE CLOCK. ORE AND ICE GO OUT; FOOD AND MACHINERY COME IN."
        "Refinery" -> "ITS SKIMMERS HARVEST HELIUM-3 FROM $host. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN."
        else -> "A QUIET STOPOVER ABOVE $host FOR PASSING CREWS."
    }

    /**
     * `stationDef(record, system)`: a finished player station as a station definition. No shipyard;
     * the default model (Roblox player stations have none and show the RingStation); the
     * description names the founder.
     */
    fun stationDef(record: StationRecord, systemIndex: Int, systemName: String): StationDef {
        val host = record.host.uppercase()
        val description = "A PRIVATE STATION FOUNDED BY ${record.owner ?: "AN UNKNOWN PILOT"}. " + ownBlurb(record.economy, host)
        return StationDef(
            id = record.id,
            name = record.name,
            host = record.host,
            economy = record.economy ?: "Extraction",
            shipyard = false,
            model = StationCatalog.DEFAULT_MODEL,
            distance = record.distance,
            angle = record.angle,
            elevation = record.elevation,
            description = description,
            systemIndex = systemIndex,
            systemName = systemName,
            player = true,
            owner = record.owner,
            ownerId = record.ownerId,
            founded = record.founded,
        )
    }

    /** `stationDefs(record, system, now)`: the operational stations of a system, sorted by id. */
    fun stationDefs(stations: Collection<StationRecord>, systemIndex: Int, systemName: String, now: Long): List<StationDef> {
        val list = ArrayList<StationDef>()
        for (record in stations) if (isOperational(record, now)) list.add(stationDef(record, systemIndex, systemName))
        list.sortWith { a, b -> a.id.compareTo(b.id) }
        return list
    }

    /** `stationId(userId)` = "P<userId>". */
    fun stationId(userId: Long): String = "P$userId"
}
