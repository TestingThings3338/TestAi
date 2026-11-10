package com.starwake.core.save

import com.starwake.core.econ.EconFixtures
import com.starwake.core.econ.EconFixtures.NOW
import com.starwake.core.econ.EconFixtures.TestWorld
import com.starwake.core.station.StationCatalog
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The outcome of background writes ([SaveStorage.finishedWrites], [SaveStorage.writeState]) and what the save manager makes of it. */
class SaveOutcomeTest {
    private val catalog = StationCatalog(TestWorld())

    private fun manager(storage: SaveStorage): SaveManager =
        SaveManager(storage, Clock(EconFixtures.FakeWall(NOW), EconFixtures.FakeMono()), catalog)

    /** A disk that refuses while [broken] and, while [gate] is closed, holds every write until it opens. */
    private class GatedDisk : SaveStorage {
        val files = MemorySaveStorage()

        @Volatile var broken = false

        @Volatile var gate: CountDownLatch? = null

        override fun readSave(name: String): ByteArray? = files.readSave(name)

        override fun writeSave(name: String, data: ByteArray): Boolean {
            gate?.await(5, TimeUnit.SECONDS)
            return !broken && files.writeSave(name, data)
        }
    }

    @Test
    fun asyncStorageReportsTheNewestOutcomePerFile() {
        val disk = GatedDisk()
        val s = AsyncSaveStorage(disk)
        assertEquals(SaveStorage.WRITE_DONE, s.writeState("profile.json"), "never written")
        assertEquals(0, s.finishedWrites)
        // queued and running: pending
        val gate = CountDownLatch(1)
        disk.gate = gate
        disk.broken = true
        assertTrue(s.writeSave("profile.json", "a".toByteArray()), "accepted")
        assertEquals(SaveStorage.WRITE_PENDING, s.writeState("profile.json"))
        assertEquals(SaveStorage.WRITE_DONE, s.writeState("galaxy.json"))
        gate.countDown()
        disk.gate = null
        assertTrue(s.flush())
        assertEquals(SaveStorage.WRITE_FAILED, s.writeState("profile.json"))
        assertEquals(1, s.finishedWrites)
        assertEquals(1, s.failures)
        // a newer write that is stored supersedes the failure
        disk.broken = false
        s.writeSave("profile.json", "b".toByteArray())
        assertTrue(s.flush())
        assertEquals(SaveStorage.WRITE_DONE, s.writeState("profile.json"))
        assertEquals(2, s.finishedWrites)
        assertEquals("b", String(s.readSave("profile.json")!!))
        // a synchronous storage keeps the defaults: its writeSave answer is final
        val memory = MemorySaveStorage()
        memory.writable = false
        assertFalse(memory.writeSave("profile.json", ByteArray(1)))
        assertEquals(SaveStorage.WRITE_DONE, memory.writeState("profile.json"))
        assertEquals(0, memory.finishedWrites)
    }

    @Test
    fun offlineClearsOnlyOnceAWriteIsConfirmed() {
        val disk = GatedDisk()
        val async = AsyncSaveStorage(disk)
        val m = manager(async)
        m.load()
        disk.broken = true
        m.save()
        assertTrue(async.flush())
        m.tick(0.016f)
        assertTrue(m.offline)
        // the disk recovers; the next write is still on the writer thread: not confirmed yet
        disk.broken = false
        val gate = CountDownLatch(1)
        disk.gate = gate
        assertTrue(m.save())
        m.tick(0.016f)
        assertTrue(m.offline, "pending is not stored")
        assertFalse(m.profile.dirty, "and not written twice meanwhile")
        gate.countDown()
        disk.gate = null
        assertTrue(async.flush())
        m.tick(0.016f)
        assertFalse(m.offline)
    }

    @Test
    fun aRefusalIsNotUndoneByAnOlderWriteThatSucceeds() {
        // an asynchronous storage that can also refuse at once: its writeState describes the newest
        // write it took, so an older write finishing must not hide the newer refusal
        val disk = GatedDisk()
        val async = AsyncSaveStorage(disk)
        var refuse = false
        val storage = object : SaveStorage by async {
            override fun writeSave(name: String, data: ByteArray): Boolean = !refuse && async.writeSave(name, data)
        }
        val m = manager(storage)
        m.load()
        val gate = CountDownLatch(1)
        disk.gate = gate
        assertTrue(m.save(), "queued, held at the gate")
        refuse = true
        m.profile.credits = 4242
        m.profile.changed()
        assertFalse(m.save(), "refused")
        assertTrue(m.offline)
        gate.countDown()
        disk.gate = null
        assertTrue(async.flush())
        m.tick(0.016f)
        assertTrue(m.offline, "the older write is stored, the newest attempt is not")
        assertTrue(m.profile.dirty)
        refuse = false
        repeat(9) {
            m.tick(1f)
            assertTrue(async.flush())
        }
        assertFalse(m.offline)
        assertFalse(m.profile.dirty)
        assertEquals(4242L, manager(disk).also { it.load() }.profile.credits)
    }

    @Test
    fun loadNamesTheCopiesItKept() {
        val storage = MemorySaveStorage()
        storage.put("profile.json", "{\"Format\":\"starwake-profile\",\"Data\":{\"Credits\":98".toByteArray())
        storage.put("profile.json.bak", "{\"Format\":\"starwake-profile\",\"Data\":{\"Credits\":97".toByteArray())
        storage.put("galaxy.json", ByteArray(0)) // empty: nothing to keep
        val m = manager(storage)
        m.load()
        assertEquals(listOf("profile.json.unreadable", "profile.json.bak.unreadable"), m.setAside)
        assertEquals("galaxy.json.bak.unreadable", SaveManager.unreadableName("galaxy.json.bak"))
        // a later, readable load keeps nothing
        m.save()
        val m2 = manager(storage)
        m2.load()
        assertTrue(m2.setAside.isEmpty())
        // a file of a format this version does not know (a newer version's) is kept, not lost
        storage.put("galaxy.json", "{\"Format\":\"starwake-galaxy-2\",\"Systems\":{}}".toByteArray())
        val m3 = manager(storage)
        m3.load()
        assertEquals(listOf("galaxy.json.unreadable"), m3.setAside)
    }

    @Test
    fun tickBetweenSavesDoesNotAllocate() {
        val bean = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean ?: return
        val id = Thread.currentThread().id
        val async = AsyncSaveStorage(MemorySaveStorage())
        val m = manager(async)
        m.load()
        m.save()
        assertTrue(async.flush())
        // a refusing storage too: a retry is armed and counts down
        val refusing = MemorySaveStorage().also { it.writable = false }
        val r = manager(refusing)
        r.load()
        r.save()
        assertTrue(r.offline)
        repeat(5000) { // warm up (JIT)
            m.tick(0.0001f)
            r.tick(0.0001f)
        }
        val before = bean.getThreadAllocatedBytes(id)
        repeat(20_000) {
            m.tick(0.0001f)
            r.tick(0.0001f)
        }
        val bytes = bean.getThreadAllocatedBytes(id) - before
        println("save manager tick: $bytes B allocated over 20,000 ticks of two managers")
        assertTrue(bytes < 1024, "$bytes B allocated by 20,000 ticks")
    }
}
