package com.starwake.core.save

import com.starwake.core.econ.Resources
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import com.starwake.core.station.StationCatalog
import kotlin.math.min

/**
 * Loads and saves the pilot ([profile], profile.json) and the galaxy store ([galaxy], galaxy.json)
 * through a [SaveStorage] (the Platform), replacing ShipData's DataStore (8.3, DECISIONS 23).
 *
 * - **Load** ([load]): each file from its primary copy, else its ".bak", else a fresh template /
 *   empty galaxy. A corrupt or truncated file never crashes: it counts as missing. Before anything
 *   can overwrite it, a copy that holds bytes but cannot be read, and is newer than the copy used,
 *   is kept as "<copy>.unreadable" ([setAside]); when no copy of a file is readable and that cannot
 *   be done, the file is held (never written this session, [offline]) so the pilot's only record
 *   survives. When a backup was used it is written back twice, so both copies are good again before
 *   the next save rotates the corrupt one away. The clock observes the files' SavedAt (it never
 *   goes back behind them).
 * - **Save** ([tick]): like ShipData's loop, an urgent change ([Profile.changed] with urgent, every
 *   galaxy change that matters) is saved within [URGENT_DELAY] s, anything else at the next
 *   [AUTOSAVE_SECONDS] autosave; only dirty files are written, galaxy first (a site never loses its
 *   owner's pointer: see [load]). [save] forces it (pause, quit). Serialisation happens on the
 *   game thread at save points only; the Platform may write on its own thread.
 * - **Failures**: a write the storage refuses, or one it accepted and then failed on its own thread
 *   ([SaveStorage.writeState], polled every frame without allocating), marks that file dirty
 *   again and [offline] until a newer write of it is confirmed; it is retried after [RETRY_DELAY] s,
 *   the delay doubling up to the autosave interval while the failures go on (ShipData retries with
 *   a growing wait too).
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

    /** Files handed to the storage (diagnostics, tests). */
    var writes = 0
        private set

    /** What the manager knows about the writes of one of its two files. */
    private class FileStatus(val name: String) {
        /** The newest attempt to write it was refused or failed: it is dirty again and a retry is due. */
        var failed = false

        /** Not written this session: an unreadable copy of it could not be kept ([load]). */
        var held = false

        /** A write was handed to an asynchronous storage and its outcome is not known yet. */
        var awaiting = false

        fun reset() {
            failed = false
            held = false
            awaiting = false
        }
    }

    private val profileFile = FileStatus(PROFILE_FILE)
    private val galaxyFile = FileStatus(GALAXY_FILE)

    /**
     * Saves are not being stored (Roblox's SaveStatus "OFFLINE"): the newest attempt to write
     * profile.json or galaxy.json was refused, or failed on the storage's thread, and no newer write
     * of it is confirmed yet; or a file is held ([load]).
     */
    val offline: Boolean get() = profileFile.failed || galaxyFile.failed || profileFile.held || galaxyFile.held

    /** The unreadable copies [load] kept, by the name they were kept under ("profile.json.unreadable", ..). */
    val setAside = ArrayList<String>(4)

    private var sinceSave = 0f
    private var urgentLeft = -1f
    private var retryLeft = -1f
    private var retryDelay = RETRY_DELAY

    /** [SaveStorage.finishedWrites] at the last [poll]. */
    private var seenFinished = 0

    /** Loads both files (see the class comment). Never throws. */
    fun load() {
        val profileCopies = copies(PROFILE_FILE, PROFILE_FORMAT, "Data")
        val galaxyCopies = copies(GALAXY_FILE, GALAXY_FORMAT, "Systems")
        for (c in profileCopies) c.read?.savedAt?.let { clock.observe(it) }
        for (c in galaxyCopies) c.read?.savedAt?.let { clock.observe(it) }
        val now = clock.now()

        // galaxy first: the profile's checks look at its stations
        galaxy = GalaxyStore(catalog, clock, randoms)
        galaxySource = Source.FRESH
        var galaxyUsed: Copy? = null
        for (c in galaxyCopies) {
            val f = c.read ?: continue
            val g = GalaxyStore(catalog, clock, randoms)
            try {
                g.read(f.data)
            } catch (e: RuntimeException) {
                c.read = null
                continue
            }
            galaxy = g
            galaxySource = c.source
            galaxyUsed = c
            break
        }

        profile = Profile.template(now)
        profileSource = Source.FRESH
        var profileUsed: Copy? = null
        for (c in profileCopies) {
            val f = c.read ?: continue
            val p = try {
                Profile.fromTree(Profile.migrate(LinkedHashMap(f.data)), catalog, now)
            } catch (e: RuntimeException) {
                c.read = null
                continue
            }
            profile = p
            profileSource = c.source
            profileUsed = c
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

        // the files were just read (an asynchronous storage finishes its queue first): no outcome is awaited
        sinceSave = 0f
        urgentLeft = -1f
        retryLeft = -1f
        retryDelay = RETRY_DELAY
        profileFile.reset()
        galaxyFile.reset()
        seenFinished = storage.finishedWrites

        // unreadable copies newer than the one used are kept before any write can rotate them away
        setAside.clear()
        galaxyFile.held = !keepUnreadable(galaxyCopies, galaxyUsed)
        profileFile.held = !keepUnreadable(profileCopies, profileUsed)

        // a backup was used: write it back twice so the corrupt copy is rotated out and both copies are good
        if (profileUsed != null && profileUsed.source == Source.BACKUP) repair(profileFile, profileUsed.bytes)
        if (galaxyUsed != null && galaxyUsed.source == Source.BACKUP) repair(galaxyFile, galaxyUsed.bytes)
    }

    /** A save file that parsed: its payload object ([data]) and SavedAt. */
    private class FileRead(val data: Map<String, Any?>, val savedAt: Long?)

    /** One copy of a save file as found at load: its name, its bytes and, while it counts as readable, what it parsed to. */
    private class Copy(val name: String, val source: Source, val bytes: ByteArray?, var read: FileRead?)

    /** The primary copy and the backup of [name]; [Copy.read] is set for those that parse as a [format] file with an object [payload]. */
    private fun copies(name: String, format: String, payload: String): List<Copy> {
        val primary = read(name)
        val backupName = SaveStorage.backupName(name)
        val backup = read(backupName)
        return listOf(
            Copy(name, Source.PRIMARY, primary, parse(primary, format, payload)),
            Copy(backupName, Source.BACKUP, backup, parse(backup, format, payload)),
        )
    }

    private fun read(name: String): ByteArray? = try {
        storage.readSave(name)
    } catch (e: RuntimeException) {
        null
    }

    private fun parse(bytes: ByteArray?, format: String, payload: String): FileRead? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            val root = Json.obj(Json.parse(bytes)) ?: return null
            if (Json.str(root["Format"]) != format) return null
            val data = Json.obj(root[payload]) ?: return null
            FileRead(data, Json.long(root["SavedAt"]))
        } catch (e: RuntimeException) {
            null
        }
    }

    /**
     * Keeps every copy in [copies] that holds bytes, could not be read and comes before [used]
     * (newer; all of them when nothing was readable) as "<copy name>.unreadable", read back to
     * confirm it. False when nothing was readable and such a copy could not be kept: the caller
     * then holds the file, so the original stays the only record instead of being overwritten.
     */
    private fun keepUnreadable(copies: List<Copy>, used: Copy?): Boolean {
        var kept = true
        for (c in copies) {
            if (c === used) break
            val bytes = c.bytes ?: continue
            if (bytes.isEmpty()) continue
            val name = unreadableName(c.name)
            val ok = try {
                storage.writeSave(name, bytes) && storage.readSave(name)?.contentEquals(bytes) == true
            } catch (e: RuntimeException) {
                false
            }
            if (ok) {
                writes++
                setAside.add(name)
            } else {
                kept = false
            }
        }
        return kept || used != null
    }

    private fun repair(file: FileStatus, bytes: ByteArray?) {
        if (bytes == null) return
        write(file, bytes)
        write(file, bytes)
    }

    /**
     * Hands [bytes] to the storage as [file]; false when it refused them or already failed them. The
     * outcome of a write that is still queued comes through [poll].
     */
    private fun write(file: FileStatus, bytes: ByteArray): Boolean {
        val accepted = try {
            storage.writeSave(file.name, bytes)
        } catch (e: RuntimeException) {
            false
        }
        if (!accepted) {
            file.awaiting = false // the refusal is the newest outcome, whatever an older write does
            failed(file)
            return false
        }
        writes++
        file.awaiting = true
        return outcome(file)
    }

    /** Takes in the outcome of [file]'s newest write if it is known; false when it failed. */
    private fun outcome(file: FileStatus): Boolean {
        val state = try {
            storage.writeState(file.name)
        } catch (e: RuntimeException) {
            SaveStorage.WRITE_PENDING
        }
        when (state) {
            SaveStorage.WRITE_DONE -> {
                file.awaiting = false
                stored(file)
            }
            SaveStorage.WRITE_FAILED -> {
                file.awaiting = false
                failed(file)
                return false
            }
        }
        return true
    }

    /** Takes in the outcome of the writes an asynchronous storage finished since the last poll. */
    private fun poll() {
        // read the counter before the states: a write finishing in between moves it again, so the next poll sees it
        seenFinished = storage.finishedWrites
        if (profileFile.awaiting) outcome(profileFile)
        if (galaxyFile.awaiting) outcome(galaxyFile)
    }

    /** The newest write of [file] did not store it: dirty again, offline, a retry scheduled (doubling while failures go on). */
    private fun failed(file: FileStatus) {
        file.failed = true
        if (file === profileFile) profile.dirty = true else galaxy.dirty = true
        if (retryLeft < 0f) {
            retryLeft = retryDelay
            retryDelay = min(retryDelay * 2f, AUTOSAVE_SECONDS)
        }
    }

    /** The newest write of [file] is stored. */
    private fun stored(file: FileStatus) {
        file.failed = false
        if (!profileFile.failed && !galaxyFile.failed) retryDelay = RETRY_DELAY
    }

    /**
     * Per frame: takes in finished background writes and runs the urgent, retry and autosave
     * timers, saving when one is due. Only reads flags, a volatile counter and floats unless a
     * write finished or it saves (no allocation between saves).
     */
    fun tick(dt: Float) {
        if (storage.finishedWrites != seenFinished) poll()
        sinceSave += dt
        if (profile.urgentRequested || galaxy.urgentRequested) {
            profile.urgentRequested = false
            galaxy.urgentRequested = false
            if (urgentLeft < 0f) urgentLeft = URGENT_DELAY
        }
        val dirty = (profile.dirty && !profileFile.held) || (galaxy.dirty && !galaxyFile.held)
        if (retryLeft >= 0f) {
            retryLeft -= dt
            if (retryLeft <= 0f) {
                retryLeft = -1f
                if (dirty) {
                    save()
                    return
                }
            }
        }
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

    /**
     * Writes the dirty files now (pause, quit, the harness's `save`). True when every dirty file was
     * stored or, by an asynchronous storage, accepted; that one reports a later failure through
     * [tick], which writes the file again and shows [offline].
     */
    fun save(): Boolean {
        if (storage.finishedWrites != seenFinished) poll()
        val now = clock.now()
        sinceSave = 0f
        urgentLeft = -1f
        retryLeft = -1f
        var ok = true
        if (galaxy.dirty) {
            if (galaxyFile.held) {
                ok = false
            } else {
                val w = JsonWriter()
                w.beginObject().field("Format", GALAXY_FORMAT).field("SavedAt", now).name("Systems")
                galaxy.write(w)
                w.endObject()
                galaxy.dirty = false
                if (!write(galaxyFile, w.toBytes())) ok = false
            }
        }
        if (profile.dirty) {
            if (profileFile.held) {
                ok = false
            } else {
                val w = JsonWriter()
                w.beginObject().field("Format", PROFILE_FORMAT).field("SavedAt", now).name("Data")
                Profile.write(profile, w)
                w.endObject()
                profile.dirty = false
                if (!write(profileFile, w.toBytes())) ok = false
            }
        }
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

        /** First retry after a failed write (s); doubles while the failures go on, up to [AUTOSAVE_SECONDS]. */
        const val RETRY_DELAY = 8f

        /** Where [load] keeps an unreadable copy [name] ("profile.json" -> "profile.json.unreadable"). */
        fun unreadableName(name: String): String = "$name.unreadable"
    }
}
