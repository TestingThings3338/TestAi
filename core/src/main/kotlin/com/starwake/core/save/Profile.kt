package com.starwake.core.save

import com.starwake.core.econ.Economy
import com.starwake.core.station.StationCatalog

/** One unsold survey (`Survey[]`): `{ System, SystemName, Body, Class, Value, First? }`. */
class SurveyEntry(val system: Int, val systemName: String, val body: String, val cls: String, val value: Long, val first: Boolean)

/**
 * Where the pilot is (`Location`): docked at a station, or in flight with the last reported pose.
 * `{ System, Station?, Docked, LastStation?, Position?, Look?, Frame? }`.
 */
class Location {
    var system = 1
    var station: String? = StationCatalog.HOME_STATION
    var docked = true
    var lastStation: String? = null

    /** True when [position], [look] and [frame] hold a reported flight pose. */
    var hasPose = false
    val position = DoubleArray(3)
    val look = DoubleArray(3)

    /** Version of the coordinate convention of [position] (ShipConfig.Ship.PositionFrame); a pose of another frame is stale. */
    var frame = 0

    /** `{ System = 1, Station = HomeStation, Docked = true }`: the template (DECISIONS 13). */
    fun setHome(): Location = setDocked(1, StationCatalog.HOME_STATION)

    /** `{ System, Station, Docked = true }`. */
    fun setDocked(system: Int, station: String): Location {
        this.system = system
        this.station = station
        docked = true
        lastStation = null
        hasPose = false
        frame = 0
        return this
    }

    /** `{ System, Docked = false, LastStation }`. */
    fun setInFlight(system: Int, lastStation: String?): Location {
        this.system = system
        station = null
        docked = false
        this.lastStation = lastStation
        hasPose = false
        frame = 0
        return this
    }

    /** `{ System, Docked = false, LastStation, Position, Look, Frame }`. */
    fun setPose(
        system: Int, lastStation: String?, x: Double, y: Double, z: Double, lx: Double, ly: Double, lz: Double, frame: Int,
    ): Location {
        setInFlight(system, lastStation)
        hasPose = true
        position[0] = x; position[1] = y; position[2] = z
        look[0] = lx; look[1] = ly; look[2] = lz
        this.frame = frame
        return this
    }

    fun copyFrom(o: Location): Location {
        system = o.system
        station = o.station
        docked = o.docked
        lastStation = o.lastStation
        hasPose = o.hasPose
        o.position.copyInto(position)
        o.look.copyInto(look)
        frame = o.frame
        return this
    }
}

/** `Stats`: lifetime counters; [created] is the epoch second the pilot was created. */
class Stats {
    var earned = 0L
    var spent = 0L
    var trades = 0L
    var surveys = 0L
    var jumps = 0L
    var dockings = 0L
    var losses = 0L

    /** Tons mined by hand. */
    var mined = 0L
    var created = 0L
}

/** `Training`: Done once completed or skipped (DECISIONS 28: optional, offered to new pilots). */
class Training {
    var done = false
    var completed = false
    var skipped = false
}

/** `OwnStation = { Id, System, Body }`: the pointer to the pilot's site or station in the galaxy store. */
class OwnStation(val id: String, val system: Int, val body: String) {
    override fun toString(): String = "OwnStation($id, $system/$body)"
}

/** A schema migration: turns a version < [toVersion] save tree into version [toVersion] (ShipData MIGRATIONS). */
class Migration(val toVersion: Int, val up: (MutableMap<String, Any?>) -> MutableMap<String, Any?>)

/**
 * The pilot profile (ShipServer/ShipData's `data` plus ShipClient/ShipProfile's getters): credits,
 * ships and their fuel, cargo, unsold survey data, charted bodies, location, stats, training, home
 * station, own station and the optional callsign (DECISIONS 29).
 *
 * Owned ships and cargo live in arrays by catalogue index ([Economy.SHIPS], [Economy.COMMODITIES]),
 * so the getters the HUD and the screens read every frame never allocate. Every change goes through
 * the commerce engine, which then calls [changed]: that marks the profile [dirty] for the save
 * manager and bumps [revision], which the screens compare instead of registering listeners.
 */
class Profile {
    var schemaVersion = SCHEMA
    var credits = Economy.START_CREDITS
    var activeShip = Economy.DEFAULT_SHIP

    private val owned = BooleanArray(Economy.SHIPS.size)
    private val fuel = DoubleArray(Economy.SHIPS.size) { 1.0 }
    private val cargoTons = IntArray(Economy.COMMODITIES.size)

    /** ΣCargo (t), kept up to date by the cargo setters. */
    var cargoUsed = 0
        private set

    /** Unsold survey data, oldest first. */
    val survey = ArrayList<SurveyEntry>()

    /** Σ survey values (CR), kept up to date. */
    var surveyTotal = 0L
        private set

    /** Charted bodies per system index: `Charted["<system>/<body>"] = true`. */
    private val charted = ArrayList<LinkedHashSet<String>?>()

    /** Number of charted bodies ("WORLDS CHARTED"). */
    var chartedCount = 0
        private set

    val location = Location()
    val stats = Stats()
    val training = Training()

    /** The station whose quarters are home (decks area); null = the start station. */
    var homeStation: String? = null
    var ownStation: OwnStation? = null

    /** Optional callsign (DECISIONS 29), sanitised; the pilot is "PILOT" or "PILOT <CALLSIGN>". */
    var callsign: String? = null
        set(value) {
            field = sanitizeCallsign(value)
        }

    /** Top-level keys this version does not know, written back unchanged (a newer save survives a downgrade). */
    val extra = LinkedHashMap<String, Any?>()

    /** Counts up on every visible change ([changed] without quiet). */
    var revision = 0
        private set

    /** Changed since the last save. */
    var dirty = false

    /** An urgent change asked for a save within [SaveManager.URGENT_DELAY] (the save manager clears it). */
    var urgentRequested = false

    /** `ShipData.changed(urgent, quiet)`: marks the profile dirty, asks for a save soon when [urgent], and bumps [revision] unless [quiet]. */
    fun changed(urgent: Boolean = false, quiet: Boolean = false) {
        dirty = true
        if (urgent) urgentRequested = true
        if (!quiet) revision++
    }

    // ---------------------------------------------------------------- ships
    /** Owned (`Ships[id] ~= nil`). Unknown ids are never owned. */
    fun owns(shipId: String?): Boolean {
        val i = Economy.shipIndex(shipId)
        return i >= 0 && owned[i]
    }

    /** `Ships[id].Fuel` (0..1), or null when not owned (`getFuel`). Defaults to the active ship. */
    fun fuel(shipId: String? = activeShip): Double? {
        val i = Economy.shipIndex(shipId)
        return if (i >= 0 && owned[i]) fuel[i] else null
    }

    /** The active ship's fuel, 1 when unknown. */
    fun activeFuel(): Double = fuel(activeShip) ?: 1.0

    /** `Ships[id] = { Fuel = f }` (adds the ship when it is not owned yet). False for an unknown id. */
    fun setShip(shipId: String, fuelFraction: Double = 1.0): Boolean {
        val i = Economy.shipIndex(shipId)
        if (i < 0) return false
        owned[i] = true
        fuel[i] = fuelFraction.coerceIn(0.0, 1.0)
        return true
    }

    /** Sets an owned ship's fuel; ignored for a ship not owned. */
    fun setFuel(shipId: String, fuelFraction: Double) {
        val i = Economy.shipIndex(shipId)
        if (i >= 0 && owned[i]) fuel[i] = fuelFraction.coerceIn(0.0, 1.0)
    }

    /** Number of owned ships ("SHIPS"). */
    fun shipCount(): Int = owned.count { it }

    /** Owned ship by catalogue index. */
    fun ownsIndex(index: Int): Boolean = index in owned.indices && owned[index]

    /** Drops every ship (reconcile and loading only). */
    internal fun clearShips() {
        owned.fill(false)
        fuel.fill(1.0)
    }

    // ---------------------------------------------------------------- cargo
    /** `Cargo[id] or 0`. */
    fun cargo(commodityId: String?): Int {
        val i = Economy.commodityIndex(commodityId)
        return if (i >= 0) cargoTons[i] else 0
    }

    /** Cargo by commodity index (market order). */
    fun cargoAt(index: Int): Int = cargoTons[index]

    /** `Cargo[id] = tons` (0 or less removes it); false for an unknown commodity. */
    fun setCargo(commodityId: String?, tons: Int): Boolean {
        val i = Economy.commodityIndex(commodityId)
        if (i < 0) return false
        cargoUsed += maxOf(0, tons) - cargoTons[i]
        cargoTons[i] = maxOf(0, tons)
        return true
    }

    fun addCargo(commodityId: String?, tons: Int): Boolean = setCargo(commodityId, cargo(commodityId) + tons)

    /** `Cargo = {}`. */
    fun clearCargo() {
        cargoTons.fill(0)
        cargoUsed = 0
    }

    // ---------------------------------------------------------------- survey data
    fun addSurvey(entry: SurveyEntry) {
        survey.add(entry)
        surveyTotal += entry.value
    }

    /** `Survey = {}`. */
    fun clearSurvey() {
        survey.clear()
        surveyTotal = 0L
    }

    // ---------------------------------------------------------------- charted bodies
    /** `isCharted(system, body)` (no allocation). */
    fun isCharted(systemIndex: Int, bodyName: String?): Boolean {
        if (bodyName == null || systemIndex < 0 || systemIndex >= charted.size) return false
        return charted[systemIndex]?.contains(bodyName) == true
    }

    /** `Charted[key] = charted or nil`. */
    fun setCharted(systemIndex: Int, bodyName: String, isCharted: Boolean) {
        if (systemIndex < 0 || systemIndex > MAX_SYSTEM) return
        if (isCharted) {
            while (charted.size <= systemIndex) charted.add(null)
            val set = charted[systemIndex] ?: LinkedHashSet<String>().also { charted[systemIndex] = it }
            if (set.add(bodyName)) chartedCount++
        } else if (systemIndex < charted.size) {
            if (charted[systemIndex]?.remove(bodyName) == true) chartedCount--
        }
    }

    /** Every charted key "<system>/<body>", by system (the profile view's `Charted` list). */
    fun chartedKeys(): List<String> {
        val keys = ArrayList<String>(chartedCount)
        for (i in charted.indices) charted[i]?.forEach { keys.add(chartedKey(i, it)) }
        return keys
    }

    // ---------------------------------------------------------------- views
    /** The pilot's name on discoveries, the station founder line and the pilot card (never COMMANDER). */
    fun pilotName(): String = callsign?.let { "PILOT $it" } ?: "PILOT"

    /** The main menu's location line: "SOL  /  MERIDIAN STATION", "SOL  /  DOCKED" or "SOL  /  IN FLIGHT". */
    fun locationText(catalog: StationCatalog): String {
        val systemName = catalog.systemName(location.system).uppercase()
        val station = location.station
        if (location.docked && station != null) return "$systemName  /  ${catalog.find(location.system, station)?.name ?: "DOCKED"}"
        return "$systemName  /  IN FLIGHT"
    }

    companion object {
        /** ShipData SCHEMA. */
        const val SCHEMA = 1

        /** The local pilot's user id (singleplayer: "P1" is the own station's id). */
        const val USER_ID = 1L

        /** Highest system index a charted key may name (sanity bound for corrupt saves). */
        const val MAX_SYSTEM = 100_000

        /** Longest callsign. */
        const val CALLSIGN_LENGTH = 16

        /** ShipData MIGRATIONS: none yet; future schema changes append `Migration(2) { … }`. */
        val MIGRATIONS: List<Migration> = emptyList()

        /** `"<system>/<body>"`. */
        fun chartedKey(systemIndex: Int, bodyName: String): String = "$systemIndex/$bodyName"

        /** Capitals, digits, single spaces and '-', at most [CALLSIGN_LENGTH] characters; blank = none. */
        fun sanitizeCallsign(text: String?): String? {
            if (text == null) return null
            val sb = StringBuilder()
            for (c in text.uppercase()) {
                val ok = c in 'A'..'Z' || c in '0'..'9' || c == '-' || (c == ' ' && sb.isNotEmpty() && sb.last() != ' ')
                if (ok && sb.length < CALLSIGN_LENGTH) sb.append(c)
            }
            val s = sb.toString().trim()
            return s.ifEmpty { null }
        }

        /** ShipData.newData(): 1,500 CR, a VARROW with a full tank, docked at MERIDIAN (DECISIONS 13), created [now]. */
        fun template(now: Long): Profile = Profile().apply {
            setShip(Economy.DEFAULT_SHIP, 1.0)
            stats.created = now
        }

        /**
         * ShipData.migrate on the raw save tree: every migration with `toVersion` above the tree's
         * SchemaVersion runs in order; one that throws stops the chain (as the Lua's pcall does).
         * SchemaVersion becomes max(version, [SCHEMA]).
         */
        fun migrate(tree: MutableMap<String, Any?>, migrations: List<Migration> = MIGRATIONS): MutableMap<String, Any?> {
            var data = tree
            var version = Json.int(data["SchemaVersion"]) ?: 0
            for (m in migrations) {
                if (m.toVersion > version) {
                    data = try {
                        m.up(data)
                    } catch (e: Exception) {
                        break
                    }
                    version = m.toVersion
                }
            }
            data["SchemaVersion"] = maxOf(version, SCHEMA).toLong()
            return data
        }

        /**
         * A profile from a migrated save tree, reconciled as ShipData.reconcile does: missing fields
         * from the template, Training from experience when missing, at least one ship, an owned
         * active ship, credits ≥ 0 and whole, a valid location (unknown system or a docked station
         * that does not exist → the template location; player stations "P<n>" are checked at the
         * session start), and an OwnStation pointer only when well formed. Never throws.
         */
        fun fromTree(data: Map<String, Any?>, catalog: StationCatalog, now: Long): Profile {
            val p = template(now)
            p.schemaVersion = Json.int(data["SchemaVersion"]) ?: SCHEMA

            // ships
            val ships = Json.obj(data["Ships"])
            if (ships != null) {
                p.clearShips()
                for ((id, v) in ships) {
                    val f = Json.num(Json.obj(v)?.get("Fuel")) ?: 1.0
                    p.setShip(id, f)
                }
                if (p.shipCount() == 0) p.setShip(Economy.DEFAULT_SHIP, 1.0)
            }
            val active = Json.str(data["ActiveShip"])
            p.activeShip = if (active != null && p.owns(active)) active else {
                // `next(Ships)`: any owned ship
                Economy.SHIPS.firstOrNull { p.owns(it.id) }?.id ?: Economy.DEFAULT_SHIP
            }

            p.credits = maxOf(0L, Json.long(data["Credits"]) ?: 0L)
            if (!data.containsKey("Credits")) p.credits = Economy.START_CREDITS

            Json.obj(data["Cargo"])?.forEach { (id, v) ->
                val tons = Json.int(v) ?: 0
                if (tons > 0) p.setCargo(id, tons)
            }

            Json.arr(data["Survey"])?.forEach { v ->
                val e = Json.obj(v) ?: return@forEach
                val system = Json.int(e["System"]) ?: return@forEach
                val body = Json.str(e["Body"]) ?: return@forEach
                val value = Json.long(e["Value"]) ?: return@forEach
                p.addSurvey(
                    SurveyEntry(
                        system, Json.str(e["SystemName"]) ?: catalog.systemName(system), body,
                        Json.str(e["Class"]) ?: "", maxOf(0L, value), Json.bool(e["First"]) == true,
                    ),
                )
            }

            // Charted: an object of keys (as Roblox stores it) or a list of keys (the view)
            val chartedKeys: Iterable<Any?>? = Json.obj(data["Charted"])?.filterValues { it == true }?.keys ?: Json.arr(data["Charted"])
            chartedKeys?.forEach { k ->
                val key = k as? String ?: return@forEach
                val slash = key.indexOf('/')
                if (slash <= 0) return@forEach
                val system = key.substring(0, slash).toIntOrNull() ?: return@forEach
                if (slash + 1 < key.length) p.setCharted(system, key.substring(slash + 1), true)
            }

            val stats = Json.obj(data["Stats"])
            if (stats != null) {
                val s = p.stats
                s.earned = Json.long(stats["Earned"]) ?: 0L
                s.spent = Json.long(stats["Spent"]) ?: 0L
                s.trades = Json.long(stats["Trades"]) ?: 0L
                s.surveys = Json.long(stats["Surveys"]) ?: 0L
                s.jumps = Json.long(stats["Jumps"]) ?: 0L
                s.dockings = Json.long(stats["Dockings"]) ?: 0L
                s.losses = Json.long(stats["Losses"]) ?: 0L
                s.mined = Json.long(stats["Mined"]) ?: 0L
                s.created = Json.long(stats["Created"]) ?: now
            }

            val training = Json.obj(data["Training"])
            if (training != null) {
                p.training.done = Json.bool(training["Done"]) == true
                p.training.completed = Json.bool(training["Completed"]) == true
                p.training.skipped = Json.bool(training["Skipped"]) == true
            } else {
                // saves from before the training: anyone who has docked, surveyed, traded or jumped is experienced
                val s = p.stats
                p.training.done = s.dockings > 0 || s.surveys > 0 || s.trades > 0 || s.jumps > 0
            }

            readLocation(Json.obj(data["Location"]), p.location, catalog)

            p.homeStation = Json.str(data["HomeStation"])
            p.ownStation = Json.obj(data["OwnStation"])?.let { o ->
                val id = Json.str(o["Id"])
                val system = Json.int(o["System"])
                val body = Json.str(o["Body"])
                if (id != null && system != null && body != null) OwnStation(id, system, body) else null
            }
            p.callsign = Json.str(data["Callsign"])

            for ((k, v) in data) if (k !in KNOWN_KEYS) p.extra[k] = v
            return p
        }

        private fun readLocation(o: Map<String, Any?>?, loc: Location, catalog: StationCatalog) {
            loc.setHome()
            val system = o?.let { Json.int(it["System"]) }
            if (o == null || system == null || !catalog.isValidSystem(system)) return
            val docked = Json.bool(o["Docked"]) == true
            val station = Json.str(o["Station"])
            val lastStation = Json.str(o["LastStation"])
            if (docked) {
                if (station == null) return
                // player stations ("P<n>") are only known once the galaxy record is loaded: the session start checks them
                if (!PLAYER_STATION.matches(station) && catalog.find(system, station) == null) return
                loc.setDocked(system, station)
                return
            }
            val pos = Json.arr(o["Position"])
            val look = Json.arr(o["Look"])
            val frame = Json.int(o["Frame"])
            if (pos != null && look != null && pos.size == 3 && look.size == 3 && frame != null) {
                val x = Json.num(pos[0])
                val y = Json.num(pos[1])
                val z = Json.num(pos[2])
                val lx = Json.num(look[0])
                val ly = Json.num(look[1])
                val lz = Json.num(look[2])
                if (x != null && y != null && z != null && lx != null && ly != null && lz != null) {
                    loc.setPose(system, lastStation, x, y, z, lx, ly, lz, frame)
                    // an undocked location never names a station
                    return
                }
            }
            loc.setInFlight(system, lastStation)
            if (station != null) loc.station = station
        }

        private val PLAYER_STATION = Regex("^P\\d+$")

        private val KNOWN_KEYS = setOf(
            "SchemaVersion", "Credits", "ActiveShip", "Ships", "Cargo", "Survey", "Charted", "Location", "Stats",
            "Training", "HomeStation", "OwnStation", "Callsign",
        )

        /** Writes [p] as the save's `Data` object (Roblox field names). */
        fun write(p: Profile, w: JsonWriter) {
            w.beginObject()
            w.field("SchemaVersion", p.schemaVersion)
            w.field("Credits", p.credits)
            w.field("ActiveShip", p.activeShip)
            w.name("Ships").beginObject()
            for (i in Economy.SHIPS.indices) if (p.owned[i]) {
                w.name(Economy.SHIPS[i].id).beginObject().field("Fuel", p.fuel[i]).endObject()
            }
            w.endObject()
            w.name("Cargo").beginObject()
            for (i in Economy.COMMODITIES.indices) if (p.cargoTons[i] > 0) w.field(Economy.COMMODITIES[i].id, p.cargoTons[i])
            w.endObject()
            w.name("Survey").beginArray()
            for (e in p.survey) {
                w.beginObject()
                    .field("System", e.system).field("SystemName", e.systemName).field("Body", e.body)
                    .field("Class", e.cls).field("Value", e.value)
                if (e.first) w.field("First", true)
                w.endObject()
            }
            w.endArray()
            w.name("Charted").beginObject()
            for (key in p.chartedKeys()) w.field(key, true)
            w.endObject()
            val l = p.location
            w.name("Location").beginObject().field("System", l.system)
            if (l.docked) {
                w.field("Station", l.station).field("Docked", true)
            } else {
                w.field("Docked", false)
                if (l.lastStation != null) w.field("LastStation", l.lastStation)
                if (l.station != null) w.field("Station", l.station)
                if (l.hasPose) {
                    w.name("Position").beginArray().value(l.position[0]).value(l.position[1]).value(l.position[2]).endArray()
                    w.name("Look").beginArray().value(l.look[0]).value(l.look[1]).value(l.look[2]).endArray()
                    w.field("Frame", l.frame)
                }
            }
            w.endObject()
            val s = p.stats
            w.name("Stats").beginObject()
                .field("Earned", s.earned).field("Spent", s.spent).field("Trades", s.trades).field("Surveys", s.surveys)
                .field("Jumps", s.jumps).field("Dockings", s.dockings).field("Losses", s.losses).field("Mined", s.mined)
                .field("Created", s.created)
                .endObject()
            w.name("Training").beginObject().field("Done", p.training.done)
            if (p.training.completed) w.field("Completed", true)
            if (p.training.skipped) w.field("Skipped", true)
            w.endObject()
            p.homeStation?.let { w.field("HomeStation", it) }
            p.ownStation?.let { o ->
                w.name("OwnStation").beginObject().field("Id", o.id).field("System", o.system).field("Body", o.body).endObject()
            }
            p.callsign?.let { w.field("Callsign", it) }
            for ((k, v) in p.extra) {
                w.name(k)
                writeAny(w, v)
            }
            w.endObject()
        }

        /** Writes a parsed JSON tree back (the preserved unknown fields). */
        fun writeAny(w: JsonWriter, v: Any?) {
            when (v) {
                null -> w.nullValue()
                is String -> w.value(v)
                is Boolean -> w.value(v)
                is Long -> w.value(v)
                is Int -> w.value(v)
                is Double -> w.value(v)
                is Map<*, *> -> {
                    w.beginObject()
                    for ((k, x) in v) {
                        w.name(k.toString())
                        writeAny(w, x)
                    }
                    w.endObject()
                }
                is List<*> -> {
                    w.beginArray()
                    for (x in v) writeAny(w, x)
                    w.endArray()
                }
                else -> w.value(v.toString())
            }
        }
    }
}
