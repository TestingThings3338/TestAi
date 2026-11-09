package com.starwake.core.save

import com.starwake.core.econ.EconFixtures
import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.Rig
import com.starwake.core.econ.EconFixtures.TestWorld
import com.starwake.core.station.StationCatalog
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pilot profile and its save file: template, reconcile, JSON, corrupt files, backups, timers, the clock. */
class ProfileTest {
    private val catalog = StationCatalog(TestWorld())

    private fun tree(json: String): MutableMap<String, Any?> = LinkedHashMap(Json.obj(Json.parse(json))!!)

    private fun decode(json: String): Profile = Profile.fromTree(Profile.migrate(tree(json)), catalog, NOW)

    // ---------------------------------------------------------------- template
    @Test
    fun templateIsTheDecisions13Start() {
        val p = Profile.template(NOW)
        assertEquals(1, p.schemaVersion)
        assertEquals(1500L, p.credits)
        assertEquals("Varrow", p.activeShip)
        assertEquals(1.0, p.fuel("Varrow"))
        assertEquals(1, p.shipCount())
        assertEquals(0, p.cargoUsed)
        assertTrue(p.survey.isEmpty())
        assertEquals(0, p.chartedCount)
        assertEquals(1, p.location.system)
        assertEquals("SOL-MERIDIAN", p.location.station)
        assertTrue(p.location.docked)
        assertEquals(NOW, p.stats.created)
        assertEquals(0L, p.stats.earned + p.stats.spent + p.stats.trades + p.stats.jumps + p.stats.losses + p.stats.mined)
        assertFalse(p.training.done)
        assertNull(p.ownStation)
        assertNull(p.homeStation)
        assertEquals("PILOT", p.pilotName())
        assertEquals("SOL  /  MERIDIAN STATION", p.locationText(catalog))
        p.location.setInFlight(1, "SOL-MERIDIAN")
        assertEquals("SOL  /  IN FLIGHT", p.locationText(catalog))
    }

    @Test
    fun gettersAndRevision() {
        val p = Profile.template(NOW)
        val r = p.revision
        p.changed()
        assertEquals(r + 1, p.revision)
        assertTrue(p.dirty)
        assertFalse(p.urgentRequested)
        p.changed(urgent = true, quiet = true)
        assertEquals(r + 1, p.revision, "quiet")
        assertTrue(p.urgentRequested)
        assertTrue(p.setCargo("Water", 4))
        assertTrue(p.addCargo("Grain", 2))
        assertFalse(p.setCargo("Unobtainium", 1))
        assertEquals(6, p.cargoUsed)
        assertEquals(4, p.cargo("Water"))
        assertEquals(0, p.cargo("Copper"))
        p.setCargo("Water", 0)
        assertEquals(2, p.cargoUsed)
        p.clearCargo()
        assertEquals(0, p.cargoUsed)
        assertNull(p.fuel("Torrin"))
        assertFalse(p.setShip("Starfury"))
        p.setFuel("Torrin", 0.3)
        assertNull(p.fuel("Torrin"), "not owned: ignored")
        p.setCharted(1, "Earth", true)
        p.setCharted(1, "Earth", true)
        p.setCharted(12, "Far", true)
        assertEquals(2, p.chartedCount)
        assertTrue(p.isCharted(1, "Earth"))
        assertFalse(p.isCharted(1, "Mars"))
        assertFalse(p.isCharted(99, "Earth"))
        assertEquals(listOf("1/Earth", "12/Far"), p.chartedKeys())
        p.setCharted(1, "Earth", false)
        assertEquals(1, p.chartedCount)
        p.callsign = "  nova  7!x "
        assertEquals("NOVA 7X", p.callsign)
        assertEquals("PILOT NOVA 7X", p.pilotName())
        p.callsign = "   "
        assertNull(p.callsign)
        p.callsign = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        assertEquals(16, p.callsign!!.length)
    }

    // ---------------------------------------------------------------- JSON round trip
    private fun populated(): Profile {
        val p = Profile.template(NOW)
        p.credits = 123_456
        p.setShip("Lochran", 0.25)
        p.activeShip = "Lochran"
        p.setFuel("Varrow", 0.5)
        p.setCargo("Water", 3)
        p.setCargo("PreciousOre", 2)
        p.addSurvey(SurveyEntry(2, "Kepler", "Kepler b", "TERRESTRIAL WORLD", 21_600, first = true))
        p.addSurvey(SurveyEntry(1, "Sol", "Earth", "TERRESTRIAL WORLD", 6480, first = false))
        p.setCharted(1, "Earth", true)
        p.setCharted(2, "Kepler b", true)
        p.location.setPose(2, "SOL-ARES", 1.5e9, -2.25, 3.0e-3, 0.0, 0.6, -0.8, 1)
        p.stats.earned = 10
        p.stats.spent = 20
        p.stats.trades = 3
        p.stats.surveys = 4
        p.stats.jumps = 5
        p.stats.dockings = 6
        p.stats.losses = 7
        p.stats.mined = 8
        p.training.done = true
        p.training.completed = true
        p.homeStation = "SOL-TITAN"
        p.ownStation = OwnStation("P1", 1, "Mars")
        p.callsign = "Nova"
        return p
    }

    private fun text(p: Profile): String = JsonWriter().also { Profile.write(p, it) }.toString()

    @Test
    fun jsonRoundTrip() {
        val a = populated()
        val json = text(a)
        val b = decode(json)
        assertEquals(json, text(b), "write -> read -> write is stable")
        assertEquals(123_456L, b.credits)
        assertEquals("Lochran", b.activeShip)
        assertEquals(0.25, b.fuel("Lochran"))
        assertEquals(0.5, b.fuel("Varrow"))
        assertEquals(5, b.cargoUsed)
        assertEquals(2, b.cargo("PreciousOre"))
        assertEquals(2, b.survey.size)
        assertTrue(b.survey[0].first)
        assertEquals("Kepler b", b.survey[0].body)
        assertEquals(21_600L + 6480L, b.surveyTotal)
        assertTrue(b.isCharted(2, "Kepler b"))
        assertTrue(b.location.hasPose)
        assertFalse(b.location.docked)
        assertEquals(2, b.location.system)
        assertEquals(1.5e9, b.location.position[0])
        assertEquals(-2.25, b.location.position[1])
        assertEquals(3.0e-3, b.location.position[2])
        assertEquals(-0.8, b.location.look[2])
        assertEquals("SOL-ARES", b.location.lastStation)
        assertEquals(1, b.location.frame)
        assertEquals(8L, b.stats.mined)
        assertEquals(NOW, b.stats.created)
        assertTrue(b.training.done && b.training.completed && !b.training.skipped)
        assertEquals("SOL-TITAN", b.homeStation)
        assertEquals("P1", b.ownStation!!.id)
        assertEquals("NOVA", b.callsign)
        // the Roblox field names
        for (key in listOf("\"SchemaVersion\"", "\"Credits\"", "\"ActiveShip\"", "\"Ships\"", "\"Cargo\"", "\"Survey\"", "\"Charted\"", "\"Location\"", "\"Stats\"", "\"Training\"", "\"OwnStation\"")) {
            assertTrue(json.contains(key), key)
        }
    }

    @Test
    fun unknownFieldsSurvive() {
        val p = decode("""{"Credits": 5, "Future": {"a": [1, 2.5, "x", null, true]}, "Note": "hi"}""")
        assertEquals(5L, p.credits)
        val again = decode(text(p))
        assertEquals(mapOf("a" to listOf(1L, 2.5, "x", null, true)), again.extra["Future"])
        assertEquals("hi", again.extra["Note"])
    }

    // ---------------------------------------------------------------- reconcile
    @Test
    fun reconcileFillsAndRepairs() {
        // an empty table is a template (Lua fills every missing key)
        val empty = decode("{}")
        assertEquals(1500L, empty.credits)
        assertEquals("Varrow", empty.activeShip)
        assertTrue(empty.location.docked)
        assertFalse(empty.training.done)
        // no Training: experienced pilots count as trained
        assertTrue(decode("""{"Stats": {"Dockings": 2}}""").training.done)
        assertTrue(decode("""{"Stats": {"Jumps": 1}}""").training.done)
        assertFalse(decode("""{"Stats": {"Losses": 3}}""").training.done)
        assertFalse(decode("""{"Stats": {"Dockings": 2}, "Training": {"Done": false}}""").training.done)
        // ships: empty -> a Varrow; an active ship not owned -> an owned one; unknown ids dropped
        assertEquals("Varrow", decode("""{"Ships": {}, "ActiveShip": "Morven"}""").activeShip)
        val other = decode("""{"Ships": {"Kessock": {"Fuel": 0.5}, "Starfury": {"Fuel": 1}}, "ActiveShip": "Starfury"}""")
        assertEquals("Kessock", other.activeShip)
        assertEquals(1, other.shipCount())
        assertEquals(0.5, other.fuel())
        assertEquals(1.0, decode("""{"Ships": {"Varrow": {}}}""").fuel())
        // credits: whole and never negative
        assertEquals(0L, decode("""{"Credits": -50}""").credits)
        assertEquals(12L, decode("""{"Credits": 12.9}""").credits)
        assertEquals(0L, decode("""{"Credits": "lots"}""").credits)
        // location: unknown system or a docked station that does not exist -> the template
        assertEquals("SOL-MERIDIAN", decode("""{"Location": {"System": 99, "Docked": false}}""").location.station)
        val gone = decode("""{"Location": {"System": 1, "Station": "SOL-ATLANTIS", "Docked": true}}""")
        assertEquals("SOL-MERIDIAN", gone.location.station)
        assertEquals(1, gone.location.system)
        val ares = decode("""{"Location": {"System": 1, "Station": "SOL-ARES", "Docked": true}}""")
        assertEquals("SOL-ARES", ares.location.station)
        // a player station is checked later (its record may not be loaded yet)
        val own = decode("""{"Location": {"System": 2, "Station": "P1", "Docked": true}}""")
        assertEquals("P1", own.location.station)
        assertEquals(2, own.location.system)
        // in flight without a valid pose: just in flight
        val flight = decode("""{"Location": {"System": 2, "Docked": false, "LastStation": "SOL-ARES", "Position": [1, 2]}}""")
        assertFalse(flight.location.docked)
        assertFalse(flight.location.hasPose)
        assertEquals("SOL-ARES", flight.location.lastStation)
        // OwnStation must be a well-formed table
        assertNull(decode("""{"OwnStation": 5}""").ownStation)
        assertNull(decode("""{"OwnStation": {"Id": "P1"}}""").ownStation)
        assertEquals("Luna", decode("""{"OwnStation": {"Id": "P1", "System": 1, "Body": "Luna"}}""").ownStation!!.body)
        // malformed entries are skipped, not fatal
        val junk = decode("""{"Cargo": {"Water": "x", "Grain": -3, "Copper": 2, "Nope": 4}, "Survey": [1, {"System": 1}, {"System": 1, "Body": "Earth", "Value": 4320}], "Charted": ["1/Earth", "x", "/y", 5, "2/"]}""")
        assertEquals(2, junk.cargoUsed)
        assertEquals(1, junk.survey.size)
        assertEquals("Sol", junk.survey[0].systemName)
        assertEquals(1, junk.chartedCount)
        assertTrue(junk.isCharted(1, "Earth"))
        // Charted as Roblox stores it: an object of keys
        assertTrue(decode("""{"Charted": {"1/Mars": true}}""").isCharted(1, "Mars"))
    }

    @Test
    fun migrationsRunInOrderAndStopOnAFailure() {
        val migrations = listOf(
            Migration(2) { it.apply { put("Credits", 777L) } },
            Migration(3) { throw IllegalStateException("broken") },
            Migration(4) { it.apply { put("Credits", 999L) } },
        )
        val t = Profile.migrate(tree("""{"SchemaVersion": 1, "Credits": 5}"""), migrations)
        assertEquals(777L, t["Credits"])
        assertEquals(2L, t["SchemaVersion"], "stopped at the failing step")
        // already newer: nothing runs, the version stays
        val n = Profile.migrate(tree("""{"SchemaVersion": 7, "Credits": 5}"""), migrations)
        assertEquals(5L, n["Credits"])
        assertEquals(7L, n["SchemaVersion"])
        // the real list is empty: version 0 (missing) becomes 1
        assertEquals(1L, Profile.migrate(tree("{}"))["SchemaVersion"])
    }

    // ---------------------------------------------------------------- JSON
    @Test
    fun jsonParserAndWriter() {
        val v = Json.parse("""  {"a": [1, -2, 3.5, -0.25e2, 1E3, 12345678901234567890, true, false, null], "s": "q\"\\\/\b\f\n\r\t\u00e9\ud83d\ude80", "e": {}, "l": []} """)
        val m = Json.obj(v)!!
        assertEquals(listOf(1L, -2L, 3.5, -25.0, 1000.0, 1.2345678901234567E19, true, false, null), m["a"])
        assertEquals("q\"\\/\b\u000C\n\r\té🚀", m["s"])
        assertEquals(emptyMap<String, Any?>(), m["e"])
        assertEquals(emptyList<Any?>(), m["l"])
        // the writer's output parses back to the same tree (whole doubles come back as whole numbers)
        val w = JsonWriter()
        Profile.writeAny(w, m)
        val back = Json.obj(Json.parse(w.toString()))!!
        assertEquals(listOf(1L, -2L, 3.5, -25L, 1000L, 1.2345678901234567E19, true, false, null), back["a"])
        assertEquals(m["s"], back["s"])
        assertEquals(m["e"], back["e"])
        assertEquals(m["l"], back["l"])
        val compact = JsonWriter(pretty = false)
        compact.beginObject().field("x", 1.0).field("y", 0.1).field("n", Double.NaN).field("t", "a\u0001b").endObject()
        assertEquals("""{"x":1,"y":0.1,"n":null,"t":"a\u0001b"}""", compact.toString())
        // errors
        for (bad in listOf("", "{", "[1,]", "{\"a\" 1}", "{a: 1}", "[1] x", "\"abc", "01", "1.", "-", "nul", "[\"\u0001\"]", "{\"a\":1,}", "\"\\x\"", "\"\\u12G4\"")) {
            assertFailsWith<JsonException>(bad) { Json.parse(bad) }
        }
        assertFailsWith<JsonException> { Json.parse("[".repeat(100_000)) }
        assertFailsWith<JsonException> { Json.parse(byteArrayOf(0x7B, 0xC3.toByte(), 0x28, 0x7D)) }
        // a byte-order mark is fine
        assertEquals(emptyMap<String, Any?>(), Json.parse(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x7B, 0x7D)))
        assertEquals(3L, Json.long(3.99))
        assertNull(Json.long("3"))
        assertNull(Json.num(Double.POSITIVE_INFINITY))
    }

    // ---------------------------------------------------------------- the save manager
    private fun manager(storage: SaveStorage, now: Long = NOW): Pair<SaveManager, EconFixtures.FakeWall> {
        val wall = EconFixtures.FakeWall(now)
        return SaveManager(storage, Clock(wall, EconFixtures.FakeMono()), catalog) to wall
    }

    @Test
    fun freshStartThenSaveAndLoad() {
        val storage = MemorySaveStorage()
        val (m, _) = manager(storage)
        m.load()
        assertEquals(SaveManager.Source.FRESH, m.profileSource)
        assertEquals(SaveManager.Source.FRESH, m.galaxySource)
        assertTrue(m.profile.dirty, "a new pilot is saved at the next autosave")
        assertTrue(m.save())
        assertEquals(setOf("profile.json"), storage.names(), "the galaxy is not dirty yet")
        m.galaxy.ensure(1)
        assertTrue(m.save())
        assertEquals(setOf("galaxy.json", "profile.json"), storage.names())
        val (m2, _) = manager(storage)
        m2.load()
        assertEquals(SaveManager.Source.PRIMARY, m2.profileSource)
        assertEquals(SaveManager.Source.PRIMARY, m2.galaxySource)
        assertEquals(1500L, m2.profile.credits)
        assertNotNull(m2.galaxy.get(1))
        assertFalse(m2.profile.dirty)
    }

    @Test
    fun saveRestoreKeepsTradesSurveysAndTheDock() {
        // the 9.5 save-restore scenario on the rules engine
        val storage = MemorySaveStorage()
        val (m, wall) = manager(storage)
        m.load()
        val c = com.starwake.core.econ.Commerce(m.profile, m.galaxy, catalog, m.clock, com.starwake.core.math.Pcg32Random(3))
        val start = c.startSession()
        assertEquals("SOL-MERIDIAN", start.docked)
        c.trade("SOL-MERIDIAN", "Water", 4, true)
        c.survey(1, "Earth")
        c.undock()
        c.requestDocking("SOL-TRANQUILITY")
        c.dock("SOL-TRANQUILITY", 0.8)
        assertTrue(m.save())
        wall.t += 100
        val (m2, _) = manager(storage, NOW + 100)
        m2.load()
        assertEquals(m.profile.credits, m2.profile.credits)
        assertEquals(4, m2.profile.cargo("Water"))
        assertEquals(1, m2.profile.survey.size)
        assertEquals(4320L, m2.profile.surveyTotal)
        assertTrue(m2.profile.isCharted(1, "Earth"))
        assertEquals(0.8, m2.profile.fuel())
        assertEquals(1L, m2.profile.stats.dockings)
        val c2 = com.starwake.core.econ.Commerce(m2.profile, m2.galaxy, catalog, m2.clock)
        assertEquals("SOL-TRANQUILITY", c2.startSession().docked, "docked at the station the pilot left (DECISIONS 23)")
    }

    @Test
    fun corruptPrimaryFallsBackToTheBackupAndRepairs() {
        val storage = MemorySaveStorage()
        val (m, _) = manager(storage)
        m.load()
        m.profile.credits = 4242
        m.profile.changed()
        m.save()
        m.profile.credits = 5353
        m.profile.changed()
        m.save() // profile.json = 5353, profile.json.bak = 4242
        val good = storage.readSave("profile.json")!!
        // truncated primary
        storage.put("profile.json", good.copyOf(good.size / 2))
        val (m2, _) = manager(storage)
        m2.load()
        assertEquals(SaveManager.Source.BACKUP, m2.profileSource)
        assertEquals(4242L, m2.profile.credits)
        // repaired: both copies hold the backup now, so the next save rotates a good copy into .bak
        assertContentEquals(storage.readSave("profile.json"), storage.readSave("profile.json.bak"))
        m2.profile.credits = 6000
        m2.profile.changed()
        m2.save()
        val (m3, _) = manager(storage)
        m3.load()
        assertEquals(6000L, m3.profile.credits)
        storage.put("profile.json", "garbage".toByteArray())
        val (m4, _) = manager(storage)
        m4.load()
        assertEquals(4242L, m4.profile.credits, "the backup is the save before")
    }

    @Test
    fun corruptFilesNeverCrash() {
        val bad = listOf(
            ByteArray(0), "{".toByteArray(), "[]".toByteArray(), "null".toByteArray(), "{\"Format\":\"x\",\"Data\":{}}".toByteArray(),
            "{\"Format\":\"starwake-profile\",\"Data\":5}".toByteArray(), "[".repeat(50_000).toByteArray(),
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00), "{\"Format\":\"starwake-profile\",\"Data\":{\"Credits\":".toByteArray(),
        )
        for (b in bad) {
            val storage = MemorySaveStorage()
            storage.put("profile.json", b)
            storage.put("profile.json.bak", b)
            storage.put("galaxy.json", b)
            storage.put("galaxy.json.bak", b)
            val (m, _) = manager(storage)
            m.load()
            assertEquals(SaveManager.Source.FRESH, m.profileSource, String(b.copyOf(minOf(b.size, 40))))
            assertEquals(SaveManager.Source.FRESH, m.galaxySource)
            assertEquals(1500L, m.profile.credits)
        }
        // an empty Data object is a valid (template) profile, as in Roblox
        val storage = MemorySaveStorage()
        storage.put("profile.json", "{\"Format\":\"starwake-profile\",\"Data\":{}}".toByteArray())
        val (m, _) = manager(storage)
        m.load()
        assertEquals(SaveManager.Source.PRIMARY, m.profileSource)
        // a storage that throws counts as missing
        val throwing = object : SaveStorage {
            override fun readSave(name: String): ByteArray? = throw IllegalStateException("disk on fire")
            override fun writeSave(name: String, data: ByteArray): Boolean = throw IllegalStateException("disk on fire")
        }
        val (t, _) = manager(throwing)
        t.load()
        assertEquals(SaveManager.Source.FRESH, t.profileSource)
        assertFalse(t.save())
        assertTrue(t.offline)
    }

    @Test
    fun urgentAndAutosaveTimers() {
        val storage = MemorySaveStorage()
        val (m, _) = manager(storage)
        m.load()
        m.save()
        val base = storage.writes
        // an urgent change is on disk within 8 s
        m.profile.changed(urgent = true)
        repeat(7) { m.tick(1f) }
        assertEquals(base, storage.writes)
        m.tick(1.01f)
        assertEquals(base + 1, storage.writes)
        assertFalse(m.profile.dirty)
        // a plain change waits for the 90 s autosave
        m.profile.changed()
        repeat(89) { m.tick(1f) }
        assertEquals(base + 1, storage.writes)
        m.tick(1.01f)
        assertEquals(base + 2, storage.writes)
        // a galaxy change marked urgent counts too
        m.galaxy.ensure(1)
        m.galaxy.touch(1, urgent = true)
        repeat(9) { m.tick(1f) }
        assertEquals(base + 3, storage.writes, "galaxy.json")
        assertFalse(m.galaxy.dirty)
        // nothing dirty: no writes, however long; after such a quiet spell the autosave is overdue
        // and the next change is written at once (Roblox: within its 5 s save loop)
        repeat(200) { m.tick(1f) }
        assertEquals(base + 3, storage.writes)
        m.profile.changed()
        m.tick(0.016f)
        assertEquals(base + 4, storage.writes)
        // a refusing storage: offline, still dirty, retried at the next autosave
        storage.writable = false
        m.profile.changed(urgent = true)
        repeat(9) { m.tick(1f) }
        assertTrue(m.offline)
        assertTrue(m.profile.dirty)
        storage.writable = true
        repeat(91) { m.tick(1f) }
        assertFalse(m.offline)
        assertFalse(m.profile.dirty)
    }

    @Test
    fun anOrphanedSiteGetsItsPointerBack() {
        val storage = MemorySaveStorage()
        val (m, _) = manager(storage)
        m.load()
        val record = m.galaxy.ensure(1)!!
        record.stations["P1"] = com.starwake.core.econ.StationRecord("P1").apply { host = "Mars" }
        m.galaxy.touch(1)
        m.save() // the site reached galaxy.json, the profile still has no pointer (as after a crash between the writes)
        val (m2, _) = manager(storage)
        m2.load()
        assertEquals("Mars", m2.profile.ownStation!!.body)
        assertEquals(1, m2.profile.ownStation!!.system)
    }

    // ---------------------------------------------------------------- the clock
    @Test
    fun clockNeverGoesBackwards() {
        val wall = EconFixtures.FakeWall(NOW)
        val clock = Clock(wall, EconFixtures.FakeMono(5.0))
        assertEquals(NOW, clock.now())
        wall.t = NOW - 3600 // the device clock was set back
        assertEquals(NOW, clock.now())
        wall.t = NOW + 10
        assertEquals(NOW + 10, clock.now())
        clock.observe(NOW + 500) // a save from "later"
        assertEquals(NOW + 500, clock.now())
        clock.observe(Clock.MAX_PLAUSIBLE + 1) // nonsense is ignored
        clock.observe(-5)
        assertEquals(NOW + 500, clock.now())
        clock.offset = 1000 // debug "time +1000"
        assertEquals(NOW + 1010, clock.now())
        assertEquals(5.0, clock.seconds())
    }

    // ---------------------------------------------------------------- storages
    @Test
    fun fileStorageIsAtomicWithABackup() {
        val dir = Files.createTempDirectory("starwake-saves").toFile()
        try {
            val s = FileSaveStorage(File(dir, "nested"))
            assertNull(s.readSave("profile.json"))
            assertTrue(s.writeSave("profile.json", "one".toByteArray()))
            assertEquals("one", String(s.readSave("profile.json")!!))
            assertNull(s.readSave("profile.json.bak"))
            assertTrue(s.writeSave("profile.json", "two".toByteArray()))
            assertEquals("two", String(s.readSave("profile.json")!!))
            assertEquals("one", String(s.readSave("profile.json.bak")!!))
            assertEquals(setOf("profile.json", "profile.json.bak"), File(dir, "nested").list()!!.toSet(), "no temp files left")
            assertFalse(s.writeSave("../escape.json", "x".toByteArray()))
            assertFalse(s.writeSave("a/b.json", "x".toByteArray()))
            assertFalse(s.writeSave("x.tmp", "x".toByteArray()))
            assertNull(s.readSave("../profile.json"))
            // a manager on files: save, load
            val (m, _) = manager(s)
            m.load()
            assertEquals(SaveManager.Source.FRESH, m.profileSource, "\"two\" and \"one\" are not saves")
            m.save()
            val (m2, _) = manager(s)
            m2.load()
            assertEquals(SaveManager.Source.PRIMARY, m2.profileSource)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun asyncStorageWritesInOrderAndReadsAfterFlushing() {
        val target = MemorySaveStorage()
        val s = AsyncSaveStorage(target)
        for (i in 1..20) assertTrue(s.writeSave("profile.json", "v$i".toByteArray()))
        assertEquals("v20", String(s.readSave("profile.json")!!))
        assertEquals("v19", String(s.readSave("profile.json.bak")!!))
        assertEquals(20, target.writes, "no coalescing")
        assertTrue(s.flush())
        assertFalse(s.writeSave("../x", ByteArray(1)))
        assertEquals(0, s.failures)
        target.writable = false
        s.writeSave("galaxy.json", ByteArray(1))
        assertTrue(s.flush())
        assertEquals(1, s.failures)
    }
}
