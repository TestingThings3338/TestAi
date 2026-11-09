package com.starwake.core.terrain

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The worker pool when an owner leaves while one of its tiles is being built. */
class TileWorkersTest {
    /** An owner whose builds wait for [gate], so a tile can be held in flight. */
    private class GatedOwner : TileJobOwner {
        override val jobStates = IntArray(16)
        override val generations = IntArray(16)
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)

        override fun buildTile(node: Int, out: TileStaging, worker: Int): Boolean {
            started.countDown()
            gate.await(10, TimeUnit.SECONDS)
            return true
        }
    }

    private fun waitIdle(pool: TileWorkerPool) {
        val end = System.currentTimeMillis() + 10_000
        while ((pool.inFlight() > 0 || pool.queued() > 0) && System.currentTimeMillis() < end) Thread.sleep(2)
        assertEquals(0, pool.inFlight(), "workers idle")
    }

    /**
     * A planet released while one of its tiles is being built; the next planet gets the same owner
     * id. The finished tile must not be handed to the new planet (a root of the old body would land
     * in the new one: roots have generation 0 in every tree), and its staging buffer comes back.
     */
    @Test
    fun aTileInFlightNeverReachesTheNextOwnerOfItsId() {
        val pool = TileWorkerPool(1, 4, 64)
        try {
            val a = GatedOwner()
            val idA = pool.register(a)
            pool.submit(idA, intArrayOf(0), floatArrayOf(0f), 1)
            assertTrue(a.started.await(5, TimeUnit.SECONDS), "A's root is being built")
            pool.unregister(idA)
            val b = GatedOwner().also { it.gate.countDown() }
            val idB = pool.register(b)
            assertEquals(idA, idB, "the id is reused")
            a.gate.countDown()
            waitIdle(pool)
            var handed = 0
            pool.pollCompleted(idB, 100) { handed++ }
            assertEquals(0, handed, "the new owner got the old owner's tile")
            assertEquals(4, pool.freeStagings(), "the staging buffer came back")
            // the new owner's own tiles still arrive
            pool.submit(idB, intArrayOf(0), floatArrayOf(0f), 1)
            waitIdle(pool)
            val end = System.currentTimeMillis() + 5000
            while (pool.finished() == 0 && System.currentTimeMillis() < end) Thread.sleep(2)
            pool.pollCompleted(idB, 100) { handed++ }
            assertEquals(1, handed, "the new owner's own tile")
        } finally {
            pool.shutdown()
        }
    }

    /** With another planet alive the id is not reused: the tile in flight must still return its staging buffer. */
    @Test
    fun aReleasedOwnersTileInFlightReturnsItsStaging() {
        val pool = TileWorkerPool(1, 4, 64)
        try {
            val other = GatedOwner().also { it.gate.countDown() }
            val idOther = pool.register(other)
            val a = GatedOwner()
            val idA = pool.register(a)
            pool.submit(idA, intArrayOf(3), floatArrayOf(0f), 1)
            assertTrue(a.started.await(5, TimeUnit.SECONDS))
            pool.unregister(idA)
            a.gate.countDown()
            waitIdle(pool)
            pool.pollCompleted(idOther, 100) { }
            assertEquals(0, pool.finished(), "nothing left for nobody")
            assertEquals(4, pool.freeStagings(), "the staging buffer came back")
        } finally {
            pool.shutdown()
        }
    }
}
