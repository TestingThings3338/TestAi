package com.starwake.core.save

import com.starwake.core.econ.Deposit
import com.starwake.core.econ.Resources
import com.starwake.core.econ.StationRecord
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import com.starwake.core.station.StationCatalog
import com.starwake.core.station.StationDef

/** Who did something first, and when (`{ By, UserId, Time }`; Sol: "SOL REGISTRY", 0, 0). */
class Discovery(val by: String, val userId: Long, val time: Long) {
    override fun toString(): String = "Discovery($by, $userId, $time)"
}

/** A body's galaxy record: its frozen deposits (best first) and its first survey. */
class BodyRecord(val deposits: List<Deposit>) {
    var discovered: Discovery? = null
}

/**
 * One system's galaxy record (ShipGalaxy "system_<i>"): `{ Version, System, Name, FirstVisit?,
 * Bodies { name -> { Deposits, Discovered? } }, Stations { id -> record }, Updated }`.
 */
class SystemRecord(val system: Int, var name: String) {
    var version = GalaxyStore.VERSION
    var firstVisit: Discovery? = null
    val bodies = LinkedHashMap<String, BodyRecord>()
    val stations = LinkedHashMap<String, StationRecord>()

    /** Epoch seconds of the last change. */
    var updated = 0L
}

/** The answer of [GalaxyStore.discover]: whether this survey was the first, and the discovery on record. */
class DiscoverResult {
    var first = false
    var discovered: Discovery? = null
}

/**
 * Port of ShipServer/ShipGalaxy (with the client's getters): the galaxy store, singleplayer and
 * kept in galaxy.json (DECISIONS 23). Per system the frozen deposits of every body (rolled on the
 * first visit, never re-rolled even if the roll tables change), who surveyed each body first, the
 * first visit, and the pilot's build site or station.
 *
 * Sol starts surveyed and visited by "SOL REGISTRY" (time 0). Every first survey or visit
 * elsewhere is the local pilot's (8.3); the ×1.5 first-survey bonus applies (DECISIONS 25). The
 * Roblox 15 s server tick becomes [refresh], run every 2 s by the station world, which syncs its
 * stations and build sites when it returns true; the construction actions advance their own site
 * first (8.3). Finished player stations are registered in the station catalogue
 * ([syncStations]), so they can be found and docked at like any other.
 */
class GalaxyStore(
    val catalog: StationCatalog,
    val clock: Clock,
    private val randoms: LuaRandomFactory = Pcg32Random.FACTORY,
) {
    private var records = arrayOfNulls<SystemRecord>(16)

    /** Station-list signatures already registered per system ([syncStations]). */
    private val registered = HashMap<Int, String>()

    /** Changed since the last save. */
    var dirty = false

    /** A change that should be saved soon (the save manager clears it). */
    var urgentRequested = false

    /** Counts up on every change (screens compare it). */
    var revision = 0
        private set

    private fun changed(record: SystemRecord, urgent: Boolean) {
        record.updated = clock.now()
        dirty = true
        if (urgent) urgentRequested = true
        revision++
    }

    /** The record of [index] if loaded (no rolling, no allocation). */
    fun get(index: Int): SystemRecord? = if (index in records.indices) records[index] else null

    private fun put(record: SystemRecord) {
        val i = record.system
        if (i >= records.size) records = records.copyOf(maxOf(i + 1, records.size * 2))
        records[i] = record
    }

    /** Every loaded record (for saving), by system index. */
    fun loaded(): List<SystemRecord> = records.filterNotNull()

    /**
     * `ensure(i)`: the record of a valid system, created on the first visit and completed: every
     * body the world lists gets its deposits rolled and frozen; Sol's bodies and first visit go to
     * "SOL REGISTRY". Registers the system's finished player stations. Null for an invalid index.
     */
    fun ensure(index: Int): SystemRecord? {
        if (!catalog.isValidSystem(index)) return null
        var record = get(index)
        if (record == null) {
            record = SystemRecord(index, catalog.systemName(index))
            put(record)
            complete(record)
            changed(record, urgent = false)
        } else if (complete(record)) {
            changed(record, urgent = false)
        }
        syncStations(index)
        return record
    }

    /** `complete(record, i)`: adds missing bodies (rolled) and Sol's registry entries; true when something was added. */
    private fun complete(record: SystemRecord): Boolean {
        val index = record.system
        var changed = false
        for (body in catalog.bodies(index)) {
            var entry = record.bodies[body.name]
            if (entry == null) {
                entry = BodyRecord(Resources.roll(index, body.name, body, randoms))
                record.bodies[body.name] = entry
                changed = true
            }
            if (index == 1 && entry.discovered == null) {
                entry.discovered = Discovery(SOL_REGISTRY, 0L, 0L)
                changed = true
            }
        }
        if (index == 1 && record.firstVisit == null) {
            record.firstVisit = Discovery(SOL_REGISTRY, 0L, 0L)
            changed = true
        }
        return changed
    }

    /** `getBody(i, name)` of a loaded record. */
    fun body(index: Int, bodyName: String?): BodyRecord? = if (bodyName == null) null else get(index)?.bodies?.get(bodyName)

    /** A station or site record of a loaded system. */
    fun station(index: Int, id: String?): StationRecord? = if (id == null) null else get(index)?.stations?.get(id)

    /**
     * `discover(player, i, body)` into [out]: the first survey puts [by] (the pilot's name) on the
     * body; later ones get the existing discovery. Nothing for an unknown body or system.
     */
    fun discover(index: Int, bodyName: String, by: String, userId: Long, out: DiscoverResult): DiscoverResult {
        out.first = false
        out.discovered = null
        val record = ensure(index) ?: return out
        val entry = record.bodies[bodyName] ?: return out
        val existing = entry.discovered
        if (existing != null) {
            out.discovered = existing
            return out
        }
        val d = Discovery(by, userId, clock.now())
        entry.discovered = d
        changed(record, urgent = true)
        out.first = true
        out.discovered = d
        return out
    }

    /** `visit(player, i)`: true when this is the first visit of system [index] by anyone (the FIRST VISIT toast). */
    fun visit(index: Int, by: String, userId: Long): Boolean {
        val record = ensure(index) ?: return false
        if (record.firstVisit != null) return false
        record.firstVisit = Discovery(by, userId, clock.now())
        changed(record, urgent = true)
        return true
    }

    /** ShipClient's FIRST VISIT check: the record's first visit is [userId]'s and less than [FIRST_VISIT_WINDOW] s old. */
    fun isFreshFirstVisit(index: Int, userId: Long, now: Long): Boolean {
        val v = get(index)?.firstVisit ?: return false
        return v.userId == userId && now - v.time < FIRST_VISIT_WINDOW
    }

    /** `getSites(i)`: the build sites of a system (not operational at [now]), sorted by id, into [out]. */
    fun sites(index: Int, now: Long, out: MutableList<StationRecord>): MutableList<StationRecord> {
        out.clear()
        val record = get(index) ?: return out
        for (s in record.stations.values) if (!Resources.isOperational(s, now)) out.add(s)
        out.sortWith { a, b -> a.id.compareTo(b.id) }
        return out
    }

    /** `stationDefs(record, i, now)`: the finished player stations of a loaded system. */
    fun stationDefs(index: Int, now: Long): List<StationDef> {
        val record = get(index) ?: return emptyList()
        return Resources.stationDefs(record.stations.values, index, catalog.systemName(index), now)
    }

    /**
     * Registers the finished player stations of [index] in the catalogue when they changed (ids and
     * names); true when the list is new (the station world then syncs).
     */
    fun syncStations(index: Int): Boolean {
        val defs = stationDefs(index, clock.now())
        val signature = defs.joinToString(",") { it.id + "=" + it.name }
        if (registered[index] == signature) return false
        registered[index] = signature
        catalog.setPlayerStations(index, defs)
        return true
    }

    /** [revision] at the end of the last [refresh] (-1 before the first: that one always asks for a sync). */
    private var refreshedRevision = -1

    /**
     * The server tick, run by the station world every 2 s (ShipClient GALAXY_REFRESH): advances
     * every due build in the loaded records (one stage per due station; a stage whose BuildEnds
     * passed therefore becomes a record change here) and re-registers finished stations.
     *
     * True when the station world needs a sync: a registered station list changed, or any record
     * changed since the previous refresh (a site founded, supplied, abandoned, advanced or finished,
     * by this tick or by the commerce engine). ShipClient syncs every 2 s regardless and on every
     * galaxy change (:3176, :676); syncing only when this returns true shows the same, and syncing
     * every time is also correct (the sync is a diff). Call it from that one place only, since each
     * call takes in the changes since the one before.
     */
    fun refresh(): Boolean {
        val now = clock.now()
        var listChanged = false
        for (record in records) {
            if (record == null) continue
            var advanced = false
            for (s in record.stations.values) {
                if (!s.operational && s.building && now >= s.buildEnds && Resources.advance(s, now)) advanced = true
            }
            if (advanced) changed(record, urgent = true)
            if (catalog.isValidSystem(record.system) && syncStations(record.system)) listChanged = true
        }
        val recordsChanged = revision != refreshedRevision
        refreshedRevision = revision
        return listChanged || recordsChanged
    }

    /** Marks [index]'s record changed (the commerce engine after it edits a station record). */
    fun touch(index: Int, urgent: Boolean = true) {
        val record = get(index) ?: return
        changed(record, urgent)
    }

    /** Removes a station record (Abandon). */
    fun removeStation(index: Int, id: String): Boolean {
        val record = get(index) ?: return false
        val removed = record.stations.remove(id) != null
        if (removed) {
            changed(record, urgent = true)
            syncStations(index)
        }
        return removed
    }

    // ---------------------------------------------------------------- galaxy.json
    /** Writes every loaded record as `{ "<index>": record, .. }`. */
    fun write(w: JsonWriter) {
        w.beginObject()
        for (record in records) {
            if (record == null) continue
            w.name(record.system.toString())
            writeRecord(record, w)
        }
        w.endObject()
    }

    private fun writeDiscovery(w: JsonWriter, d: Discovery) {
        w.beginObject().field("By", d.by).field("UserId", d.userId).field("Time", d.time).endObject()
    }

    private fun writeRecord(r: SystemRecord, w: JsonWriter) {
        w.beginObject()
        w.field("Version", r.version).field("System", r.system).field("Name", r.name)
        r.firstVisit?.let { w.name("FirstVisit"); writeDiscovery(w, it) }
        w.name("Bodies").beginObject()
        for ((name, b) in r.bodies) {
            w.name(name).beginObject()
            w.name("Deposits").beginArray()
            for (d in b.deposits) w.beginObject().field("Id", d.id).field("Grade", d.grade).endObject()
            w.endArray()
            b.discovered?.let { w.name("Discovered"); writeDiscovery(w, it) }
            w.endObject()
        }
        w.endObject()
        w.name("Stations").beginObject()
        for ((id, s) in r.stations) {
            w.name(id)
            writeStation(s, w)
        }
        w.endObject()
        w.field("Updated", r.updated)
        w.endObject()
    }

    private fun writeStation(s: StationRecord, w: JsonWriter) {
        w.beginObject()
        w.field("Id", s.id).field("Name", s.name)
        s.owner?.let { w.field("Owner", it) }
        w.field("OwnerId", s.ownerId).field("Host", s.host)
        s.economy?.let { w.field("Economy", it) }
        w.field("Slot", s.slot).field("Angle", s.angle).field("Elevation", s.elevation).field("Distance", s.distance)
        w.field("Founded", s.founded).field("Stage", s.stage)
        w.name("Delivered").beginObject()
        for ((id, tons) in s.delivered) w.field(id, tons)
        w.endObject()
        if (s.buildEnds != StationRecord.NONE) w.field("BuildEnds", s.buildEnds)
        if (s.operational) w.field("Operational", true)
        if (s.operationalAt != StationRecord.NONE) w.field("OperationalAt", s.operationalAt)
        s.storage?.let { storage ->
            w.name("Storage").beginObject()
            for ((id, tons) in storage) w.field(id, tons)
            w.endObject()
        }
        if (s.storageAt != StationRecord.NONE) w.field("StorageAt", s.storageAt)
        w.endObject()
    }

    /**
     * Replaces the loaded records with those of a parsed galaxy tree (`{ "<index>": record }`).
     * Malformed parts are skipped, never thrown; records of systems the world does not know (yet)
     * are kept and written back. Deposits are taken as stored: frozen.
     */
    fun read(tree: Map<String, Any?>) {
        records = arrayOfNulls(16)
        registered.clear()
        refreshedRevision = -1
        for ((key, v) in tree) {
            val index = key.toIntOrNull() ?: continue
            if (index < 1 || index > Profile.MAX_SYSTEM) continue
            val o = Json.obj(v) ?: continue
            put(readRecord(index, o))
        }
    }

    private fun readDiscovery(v: Any?): Discovery? {
        val o = Json.obj(v) ?: return null
        val by = Json.str(o["By"]) ?: return null
        return Discovery(by, Json.long(o["UserId"]) ?: 0L, Json.long(o["Time"]) ?: 0L)
    }

    private fun readRecord(index: Int, o: Map<String, Any?>): SystemRecord {
        val r = SystemRecord(index, Json.str(o["Name"]) ?: catalog.systemName(index))
        r.version = Json.int(o["Version"]) ?: VERSION
        r.firstVisit = readDiscovery(o["FirstVisit"])
        r.updated = Json.long(o["Updated"]) ?: 0L
        Json.obj(o["Bodies"])?.forEach { (name, bv) ->
            val b = Json.obj(bv) ?: return@forEach
            val deposits = ArrayList<Deposit>()
            Json.arr(b["Deposits"])?.forEach { dv ->
                val d = Json.obj(dv)
                val id = Json.str(d?.get("Id"))
                val grade = Json.int(d?.get("Grade"))
                if (id != null && grade != null) deposits.add(Deposit(id, grade))
            }
            val body = BodyRecord(deposits)
            body.discovered = readDiscovery(b["Discovered"])
            r.bodies[name] = body
        }
        Json.obj(o["Stations"])?.forEach { (id, sv) ->
            val s = Json.obj(sv) ?: return@forEach
            r.stations[id] = readStation(id, s)
        }
        return r
    }

    private fun readStation(id: String, o: Map<String, Any?>): StationRecord {
        val s = StationRecord(Json.str(o["Id"]) ?: id)
        s.name = Json.str(o["Name"]) ?: ""
        s.owner = Json.str(o["Owner"])
        s.ownerId = Json.long(o["OwnerId"]) ?: 0L
        s.host = Json.str(o["Host"]) ?: ""
        s.economy = Json.str(o["Economy"])
        s.slot = Json.int(o["Slot"]) ?: 0
        s.angle = Json.num(o["Angle"]) ?: 0.0
        s.elevation = Json.num(o["Elevation"]) ?: 0.0
        s.distance = Json.num(o["Distance"]) ?: StationCatalog.DEFAULT_DISTANCE
        s.founded = Json.long(o["Founded"]) ?: 0L
        s.stage = Json.int(o["Stage"]) ?: 1
        Json.obj(o["Delivered"])?.forEach { (cid, tv) -> Json.int(tv)?.let { if (it > 0) s.delivered[cid] = it } }
        s.buildEnds = Json.long(o["BuildEnds"]) ?: StationRecord.NONE
        s.operational = Json.bool(o["Operational"]) == true
        s.operationalAt = Json.long(o["OperationalAt"]) ?: StationRecord.NONE
        Json.obj(o["Storage"])?.let { st ->
            val storage = LinkedHashMap<String, Double>()
            for ((cid, tv) in st) Json.num(tv)?.let { if (it > 0) storage[cid] = it }
            s.storage = storage
        }
        s.storageAt = Json.long(o["StorageAt"]) ?: StationRecord.NONE
        return s
    }

    companion object {
        /** ShipGalaxy VERSION of a system record. */
        const val VERSION = 1

        /** Who discovered Sol. */
        const val SOL_REGISTRY = "SOL REGISTRY"

        /** The FIRST VISIT toast shows for this long after the visit (s). */
        const val FIRST_VISIT_WINDOW = 300L
    }
}
