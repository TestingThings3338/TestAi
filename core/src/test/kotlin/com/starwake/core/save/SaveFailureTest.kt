package com.starwake.core.save

import com.starwake.core.econ.EconFixtures
import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.TestWorld
import com.starwake.core.station.StationCatalog
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The save layer when the disk lets it down: a write that fails on the Android writer thread
 * ([AsyncSaveStorage]) is written again and shows as offline, and save files that cannot be read
 * are kept before any save rotates them away.
 */
class SaveFailureTest {
    private val catalog = StationCatalog(TestWorld())

    private fun manager(storage: SaveStorage): SaveManager =
        SaveManager(storage, Clock(EconFixtures.FakeWall(NOW), EconFixtures.FakeMono()), catalog)

    /** A disk (in memory) that refuses the next [failNext] writes, and every write while [broken]. */
    private class FlakyDisk : SaveStorage {
        val files = MemorySaveStorage()

        @Volatile var failNext = 0

        @Volatile var broken = false

        @Volatile var attempts = 0

        override fun readSave(name: String): ByteArray? = files.readSave(name)

        override fun writeSave(name: String, data: ByteArray): Boolean {
            attempts++
            if (broken) return false
            if (failNext > 0) {
                failNext--
                return false
            }
            return files.writeSave(name, data)
        }
    }

    /** One frame of [dt] s, then waits for the writer thread (so the next frame sees what this one queued). */
    private fun SaveManager.frame(async: AsyncSaveStorage, dt: Float = 1f) {
        tick(dt)
        assertTrue(async.flush())
    }

    // ---------------------------------------------------------------- writes failing on the writer thread
    @Test
    fun aWriteThatFailsOnTheWriterThreadIsWrittenAgain() {
        // the review's probe: SaveManager over AsyncSaveStorage over a disk that fails exactly one write
        val disk = FlakyDisk()
        val async = AsyncSaveStorage(disk)
        val m = manager(async)
        m.load()
        assertTrue(m.save())
        assertTrue(async.flush())
        m.tick(0.016f)
        assertFalse(m.offline)
        disk.failNext = 1
        m.profile.credits = 777_777
        m.profile.changed(urgent = true)
        m.save() // true once queued; false only if the writer thread already failed it
        assertTrue(async.flush())
        m.tick(0.016f)
        assertTrue(m.offline, "the failure reaches the save manager (SaveStatus OFFLINE)")
        assertTrue(m.profile.dirty, "the profile is written again")
        // a pause save at this point stores it
        assertTrue(m.save())
        assertTrue(async.flush())
        m.tick(0.016f)
        assertFalse(m.offline, "confirmed by the writer thread")
        assertFalse(m.profile.dirty)
        val again = manager(disk)
        again.load()
        assertEquals(777_777L, again.profile.credits)
    }

    @Test
    fun aFailedWriteIsRetriedWithoutAnotherChange() {
        val disk = FlakyDisk()
        val async = AsyncSaveStorage(disk)
        val m = manager(async)
        m.load()
        m.save()
        assertTrue(async.flush())
        disk.failNext = 1
        m.profile.credits = 777_777
        m.profile.changed(urgent = true)
        repeat(200) { m.frame(async) } // 200 s of play in which nothing else changes
        assertFalse(m.offline)
        assertFalse(m.profile.dirty)
        val again = manager(disk)
        again.load()
        assertEquals(777_777L, again.profile.credits, "not the 1,500 CR of the file before")
    }

    @Test
    fun aDiskThatAlwaysFailsIsOfflineAndRetriedLessAndLessOften() {
        val disk = FlakyDisk()
        val async = AsyncSaveStorage(disk)
        val m = manager(async)
        m.load()
        disk.broken = true
        m.save() // queued (the outcome is not known yet, or already a failure)
        assertTrue(async.flush())
        m.tick(0.016f)
        assertTrue(m.offline)
        assertTrue(m.profile.dirty)
        val before = disk.attempts
        repeat(300) { m.frame(async) }
        val retries = disk.attempts - before
        assertTrue(retries in 4..8, "$retries retries in 300 s (8 s, then doubling up to the 90 s autosave)")
        assertTrue(m.offline)
        disk.broken = false
        repeat(91) { m.frame(async) }
        assertFalse(m.offline, "online again once a write is stored")
        assertFalse(m.profile.dirty)
    }

    @Test
    fun aRefusedWriteIsRetriedWithinEightSeconds() {
        // a synchronous storage that refuses (no space): retried soon, not at the next 90 s autosave
        val storage = MemorySaveStorage()
        val m = manager(storage)
        m.load()
        m.save()
        storage.writable = false
        m.profile.credits = 4242
        m.profile.changed(urgent = true)
        repeat(9) { m.tick(1f) }
        assertTrue(m.offline)
        storage.writable = true
        repeat(9) { m.tick(1f) }
        assertFalse(m.offline)
        assertFalse(m.profile.dirty)
        val again = manager(storage)
        again.load()
        assertEquals(4242L, again.profile.credits)
    }

    // ---------------------------------------------------------------- files that cannot be read
    /** The bytes of a profile.json holding [credits], cut in half (a damaged file that no longer parses). */
    private fun truncatedSave(credits: Long): ByteArray {
        val storage = MemorySaveStorage()
        val m = manager(storage)
        m.load()
        m.profile.credits = credits
        m.profile.changed()
        assertTrue(m.save())
        val bytes = storage.readSave("profile.json")!!
        val cut = bytes.copyOf(bytes.size / 2)
        assertTrue(String(cut).contains(credits.toString()), "the cut keeps the credits")
        return cut
    }

    @Test
    fun unreadableCopiesAreKeptBeforeTheSavesRotateThemAway() {
        // the review's probe: profile.json and profile.json.bak both damaged, so the pilot starts fresh
        val storage = MemorySaveStorage()
        val primary = truncatedSave(987_654)
        val backup = truncatedSave(987_000)
        val galaxy = "{\"Format\":\"starwake-galaxy\",\"SavedAt\":1800000000,\"Systems\":{\"1\":{\"Na".toByteArray()
        storage.put("profile.json", primary)
        storage.put("profile.json.bak", backup)
        storage.put("galaxy.json", galaxy)
        val m = manager(storage)
        m.load()
        assertEquals(SaveManager.Source.FRESH, m.profileSource)
        assertEquals(SaveManager.Source.FRESH, m.galaxySource)
        assertEquals(1500L, m.profile.credits)
        assertFalse(m.offline)
        // two saves of each file rotate the originals out of the file and its .bak ...
        m.galaxy.ensure(1)
        assertTrue(m.save())
        m.profile.changed(urgent = true)
        m.galaxy.touch(1)
        assertTrue(m.save())
        assertFalse(String(storage.readSave("profile.json")!!).contains("987"))
        assertFalse(String(storage.readSave("profile.json.bak")!!).contains("987"))
        // ... but they were kept first, byte for byte, for the pilot or a later version to recover
        assertContentEquals(primary, storage.readSave("profile.json.unreadable"))
        assertContentEquals(backup, storage.readSave("profile.json.bak.unreadable"))
        assertContentEquals(galaxy, storage.readSave("galaxy.json.unreadable"))
        // later loads read the new files and leave the kept copies alone
        val m2 = manager(storage)
        m2.load()
        assertEquals(SaveManager.Source.PRIMARY, m2.profileSource)
        m2.profile.changed()
        m2.save()
        m2.profile.changed()
        m2.save()
        assertContentEquals(primary, storage.readSave("profile.json.unreadable"))
        assertContentEquals(backup, storage.readSave("profile.json.bak.unreadable"))
    }

    @Test
    fun aDamagedPrimaryIsKeptBeforeTheBackupIsWrittenBack() {
        val storage = MemorySaveStorage()
        val damaged = truncatedSave(5353)
        val m = manager(storage)
        m.load()
        m.profile.credits = 4242
        m.profile.changed()
        m.save()
        m.save() // nothing dirty: no write
        m.profile.credits = 5353
        m.profile.changed()
        m.save() // profile.json = 5353, profile.json.bak = 4242
        storage.put("profile.json", damaged)
        val m2 = manager(storage)
        m2.load()
        assertEquals(SaveManager.Source.BACKUP, m2.profileSource)
        assertEquals(4242L, m2.profile.credits)
        assertContentEquals(storage.readSave("profile.json"), storage.readSave("profile.json.bak"), "repaired")
        assertContentEquals(damaged, storage.readSave("profile.json.unreadable"), "the newer, damaged copy is kept")
        assertEquals(null, storage.readSave("profile.json.bak.unreadable"), "the backup was readable")
    }

    @Test
    fun whenAnUnreadableFileCannotBeKeptItIsNotOverwritten() {
        // the copy cannot be stored: then the damaged files stay the only record, untouched, and the saves show offline
        val files = MemorySaveStorage()
        val disk = object : SaveStorage {
            override fun readSave(name: String): ByteArray? = files.readSave(name)
            override fun writeSave(name: String, data: ByteArray): Boolean = !name.endsWith(".unreadable") && files.writeSave(name, data)
        }
        val primary = truncatedSave(987_654)
        val backup = truncatedSave(987_000)
        files.put("profile.json", primary)
        files.put("profile.json.bak", backup)
        val m = manager(disk)
        m.load()
        assertEquals(SaveManager.Source.FRESH, m.profileSource)
        assertTrue(m.offline)
        m.profile.credits = 5000
        m.profile.changed(urgent = true)
        repeat(200) { m.tick(1f) }
        assertFalse(m.save())
        assertContentEquals(primary, files.readSave("profile.json"))
        assertContentEquals(backup, files.readSave("profile.json.bak"))
        // the galaxy is saved as usual
        m.galaxy.ensure(1)
        m.save()
        assertNotNull(files.readSave("galaxy.json"))
        // the next start tries again, and once the copy can be kept the pilot's saves go on
        val m2 = manager(files)
        m2.load()
        assertFalse(m2.offline)
        assertContentEquals(primary, files.readSave("profile.json.unreadable"))
        assertContentEquals(backup, files.readSave("profile.json.bak.unreadable"))
        assertTrue(m2.save())
        assertEquals(1500L, manager(files).also { it.load() }.profile.credits)
    }
}
