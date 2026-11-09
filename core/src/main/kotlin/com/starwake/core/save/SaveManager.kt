package com.starwake.core.save

import com.starwake.core.econ.Resources
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import com.starwake.core.station.StationCatalog

/**
 * Loads and saves the pilot ([profile], profile.json) and the galaxy store ([galaxy], galaxy.json)
 * through a [SaveStorage] (the Platform), replacing ShipData's DataStore (8.3, DECISIONS 23).
 *
 * - **Load** ([load]): each file from its primary copy, else its ".bak", else a fresh template /
 *   empty galaxy. A corrupt or truncated file never crashes: it counts as missing. When a backup
 *   was used it is written back twice, so both copies are good again before the next save rotates
 *   the corrupt one away. The clock observes the files' SavedAt (it never goes back behind them).
 * - **Save** ([tick]): like ShipData's loop, an urgent change ([Profile.changed] with urgent, every
 *   galaxy change that matters) is saved within [URGENT_DELAY] s, anything else at the next
 *   [AUTOSAVE_SECONDS] autosave; only dirty files are written, galaxy first (a site never loses its
 *   owner's pointer: see [load]). [save] forces it (pause, quit). Serialisation happens on the
 *   game thread at save points only; the Platform writes on its own thread.
 *
 * File format: `{ "Format", "SavedAt", "Data" }` for the profile (Data = the Roblox `data` table,
 * same field names) and `{ "Format", "SavedAt", "Systems": { "<index>": record } }` for the galaxy.
 */
class SaveManager(
    val storage: SaveStorage,
    val clock: Clock,
    val catalog: StationCatalog,
    private val randoms: LuaRandomFactory = Pcg32Random.FACTORY,
) {
    /** Where a file came from at the last [load]. */
    enum class Source { PRIMARY, BACKUP, FRESH }

    var profile: Profile = Profile.template(clock.now())
        private set
    var galaxy: GalaxyStore = GalaxyStore(catalog, clock, randoms)
        private set

    var profileSource = Source.FRESH
        private set
    var galaxySource = Source.FRESH
        private set

    /** Files written (diagnostics, tests). */
    var writes = 0
        private set

    /** The last write was refused: saves are not stored (Roblox's SaveStatus "OFFLINE"). */
    var offline = false
        private set

    private var sinceSave = 0f
    private var urgentLeft = -1f

    /** Loads both files (see the class comment). Never throws. */
    fun load() {
        val profileFiles = candidates(PROFILE_FILE, PROFILE_FORMAT, "Data")
        val galaxyFiles = candidates(GALAXY_FILE, GALAXY_FORMAT, "Systems")
        for (f in profileFiles) f.savedAt?.let { clock.observe(it) }
        for (f in galaxyFiles) f.savedAt?.let { clock.observe(it) }
        val now = clock.now()

        // galaxy first: the profile's checks look at its stations
        galaxy = GalaxyStore(catalog, clock, randoms)
        galaxySource = Source.FRESH
        var galaxyUsed: FileRead? = null
        for (f in galaxyFiles) {
            val g = GalaxyStore(catalog, clock, randoms)
            try {
                g.read(f.data)
            } catch (e: RuntimeException) {
                continue
            }
            galaxy = g
            galaxySource = f.source
            galaxyUsed = f
            break
        }

        profile = Profile.template(now)
        profileSource = Source.FRESH
        var profileUsed: FileRead? = null
        for (f in profileFiles) {
            val p = try {
                Profile.fromTree(Profile.migrate(LinkedHashMap(f.data)), catalog, now)
            } catch (e: RuntimeException) {
                continue
            }
            profile = p
            profileSource = f.source
            profileUsed = f
            break
        }
        if (profileSource == Source.FRESH) profile.dirty = true

        // two files cannot be written together: a site whose owner pointer did not make it to disk gets it back
        if (profile.ownStation == null) {
            val id = Resources.stationId(Profile.USER_ID)
            for (record in galaxy.loaded()) {
                val s = record.stations[id] ?: continue
                profile.ownStation = OwnStation(id, record.system, s.host)
                profile.dirty = true
                break
            }
        }

        // a backup was used: write it back twice so the corrupt copy is rotated out and both copies are good
        if (profileUsed != null && profileUsed.source == Source.BACKUP) repair(PROFILE_FILE, profileUsed.bytes)
        if (galaxyUsed != null && galaxyUsed.source == Source.BACKUP) repair(GALAXY_FILE, galaxyUsed.bytes)
        sinceSave = 0f
        urgentLeft = -1f
    }

    /** A save file that parsed: its payload object ([data]), SavedAt, and where it came from. */
    private class FileRead(val data: Map<String, Any?>, val savedAt: Long?, val source: Source, val bytes: ByteArray)

    /** The primary copy and the backup of [name], those that parse as a [format] file with an object [payload]. */
    private fun candidates(name: String, format: String, payload: String): List<FileRead> {
        val list = ArrayList<FileRead>(2)
        parse(read(name), format, payload, Source.PRIMARY)?.let { list.add(it) }
        parse(read(SaveStorage.backupName(name)), format, payload, Source.BACKUP)?.let { list.add(it) }
        return list
    }

    private fun read(name: String): ByteArray? = try {
        storage.readSave(name)
    } catch (e: RuntimeException) {
        null
    }

    private fun parse(bytes: ByteArray?, format: String, payload: String, source: Source): FileRead? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val root = Json.obj(Json.parse(bytes)) ?: return null
            if (Json.str(root["Format"]) != format) return null
            val data = Json.obj(root[payload]) ?: return null
            FileRead(data, Json.long(root["SavedAt"]), source, bytes)
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun repair(name: String, bytes: ByteArray) {
        write(name, bytes)
        write(name, bytes)
    }

    private fun write(name: String, bytes: ByteArray): Boolean {
        val ok = try {
            storage.writeSave(name, bytes)
        } catch (e: RuntimeException) {
            false
        }
        offline = !ok
        if (ok) writes++
        return ok
    }

    /**
     * Per frame: runs the urgent and autosave timers and saves when one is due. Only reads flags
     * and floats unless it saves (no allocation between saves).
     */
    fun tick(dt: Float) {
        sinceSave += dt
        if (profile.urgentRequested || galaxy.urgentRequested) {
            profile.urgentRequested = false
            galaxy.urgentRequested = false
            if (urgentLeft < 0f) urgentLeft = URGENT_DELAY
        }
        val dirty = profile.dirty || galaxy.dirty
        if (urgentLeft >= 0f) {
            urgentLeft -= dt
            if (urgentLeft <= 0f) {
                urgentLeft = -1f
                if (dirty) save()
                return
            }
        }
        if (dirty && sinceSave >= AUTOSAVE_SECONDS) save()
    }

    /** Writes the dirty files now (pause, quit, the harness's `save`). True when everything dirty was stored. */
    fun save(): Boolean {
        val now = clock.now()
        var ok = true
        if (galaxy.dirty) {
            val w = JsonWriter()
            w.beginObject().field("Format", GALAXY_FORMAT).field("SavedAt", now).name("Systems")
            galaxy.write(w)
            w.endObject()
            if (write(GALAXY_FILE, w.toBytes())) galaxy.dirty = false else ok = false
        }
        if (profile.dirty) {
            val w = JsonWriter()
            w.beginObject().field("Format", PROFILE_FORMAT).field("SavedAt", now).name("Data")
            Profile.write(profile, w)
            w.endObject()
            if (write(PROFILE_FILE, w.toBytes())) profile.dirty = false else ok = false
        }
        sinceSave = 0f
        urgentLeft = -1f
        return ok
    }

    companion object {
        const val PROFILE_FILE = "profile.json"
        const val GALAXY_FILE = "galaxy.json"
        const val PROFILE_FORMAT = "starwake-profile"
        const val GALAXY_FORMAT = "starwake-galaxy"

        /** ShipData AUTOSAVE (s). */
        const val AUTOSAVE_SECONDS = 90f

        /** ShipData URGENT_DELAY (s): an urgent change is on disk within this time. */
        const val URGENT_DELAY = 8f
    }
}
