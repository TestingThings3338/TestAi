package com.starwake.core.terrain

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What a tile build produces besides the vertex data (filled by the worker). */
class TileStaging(val index: Int, bytes: Int) {
    /** Packed vertices ([TileFormat]), position 0, limit = size. */
    val buffer: ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

    var owner = -1

    /** The owner's registration ([TileWorkerPool.register]) the tile was built for. */
    var epoch = 0
    var node = -1
    var gen = 0

    /** Tile origin C (body space, m) and the half extents of the vertex box around it. */
    var cx = 0.0
    var cy = 0.0
    var cz = 0.0
    var hx = 1f
    var hy = 1f
    var hz = 1f

    /** Scale of the stored morph deltas (m per unit), = the largest |delta|. */
    var morphScale = 0f
    var rMin = 0.0
    var rMax = 0.0
    var buildNanos = 0L
    var ok = false
}

/** A user of the pool: builds its tiles on the workers. */
interface TileJobOwner {
    /** Per node: 0 none, 1 queued, 2 building. Read and written only under the pool's lock. */
    val jobStates: IntArray

    /** Per node generation; a job whose generation no longer matches is dropped. */
    val generations: IntArray

    /** Builds [node] into [out] on worker [worker]. Returns false if the node cannot be built (dropped). */
    fun buildTile(node: Int, out: TileStaging, worker: Int): Boolean
}

/** Receives finished tiles on the game thread ([TileWorkerPool.pollCompleted]). */
fun interface TileSink {
    fun accept(staging: TileStaging)
}

/**
 * A fixed pool of worker threads that build terrain tiles (§9.6): one job heap (lower priority =
 * sooner; rebuilt from the owner's wanted list on every [submit]), a pool of direct staging buffers
 * the workers write vertices into, and a completion ring the game thread drains with
 * [pollCompleted] (which uploads at most a given number per frame). Everything is allocated at
 * construction; the game-thread calls allocate nothing (monitors, not j.u.c locks).
 *
 * Workers run below normal priority and outside the performance-hint session (they are created
 * here, not on the render thread).
 */
class TileWorkerPool(val threads: Int, stagingCount: Int, stagingBytes: Int = TileFormat.TILE_BYTES) {
    private val lock = java.lang.Object()

    private val owners = arrayOfNulls<TileJobOwner>(MAX_OWNERS)

    /**
     * Registration of each owner id (a fresh number per [register]): a tile finished for an earlier
     * registration of the same id (a released planet whose id went to the next one) is never
     * handed to the new owner, and its staging buffer goes back to the pool.
     */
    private val ownerEpoch = IntArray(MAX_OWNERS)
    private var epochs = 0

    // job heap (parallel arrays)
    private val heapPrio = FloatArray(HEAP_CAPACITY)
    private val heapNode = IntArray(HEAP_CAPACITY)
    private val heapGen = IntArray(HEAP_CAPACITY)
    private val heapOwner = IntArray(HEAP_CAPACITY)
    private var heapSize = 0

    val staging = Array(stagingCount) { TileStaging(it, stagingBytes) }
    private val freeStaging = IntArray(stagingCount) { stagingCount - 1 - it }
    private var freeCount = stagingCount

    private val done = IntArray(stagingCount)
    private var doneCount = 0
    private val drained = IntArray(stagingCount)

    @Volatile private var running = true
    private var paused = false
    private var building = 0

    // statistics (written under the lock)
    var tilesBuilt = 0L
        private set
    var buildNanosTotal = 0L
        private set
    var buildNanosMax = 0L
        private set

    private val workers = Array(threads) { w ->
        Thread({ workerLoop(w) }, "terrain-worker-$w").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }

    init {
        for (t in workers) t.start()
    }

    fun register(owner: TileJobOwner): Int = synchronized(lock) {
        for (i in owners.indices) {
            if (owners[i] == null) {
                owners[i] = owner
                ownerEpoch[i] = ++epochs
                return i
            }
        }
        throw IllegalStateException("too many terrain owners")
    }

    /**
     * Drops the owner's queued jobs and its finished tiles; tiles it has in flight go back to the
     * staging pool when they come back (never to the next owner of the id).
     */
    fun unregister(id: Int) = synchronized(lock) {
        removeOwnerJobs(id)
        owners[id] = null
        ownerEpoch[id] = 0
        // finished tiles nobody will collect
        var w = 0
        for (k in 0 until doneCount) {
            val s = done[k]
            if (staging[s].owner == id) freeStaging[freeCount++] = s else done[w++] = s
        }
        doneCount = w
        lock.notifyAll()
    }

    /** True when staging [s] was built for the current registration of its owner id. Under the lock. */
    private fun current(s: TileStaging): Boolean = owners[s.owner] != null && ownerEpoch[s.owner] == s.epoch

    /**
     * Replaces the owner's queue with [count] wanted nodes ([nodes], [prios]): queued jobs not in the
     * list are dropped, nodes already queued or building are kept as they are.
     */
    fun submit(id: Int, nodes: IntArray, prios: FloatArray, count: Int) {
        synchronized(lock) {
            val owner = owners[id] ?: return
            removeOwnerJobs(id)
            val states = owner.jobStates
            val gens = owner.generations
            for (k in 0 until count) {
                val n = nodes[k]
                if (states[n] != 0) continue
                if (heapSize >= HEAP_CAPACITY) break
                states[n] = 1
                heapPrio[heapSize] = prios[k]
                heapNode[heapSize] = n
                heapGen[heapSize] = gens[n]
                heapOwner[heapSize] = id
                heapSize++
            }
            for (i in heapSize / 2 - 1 downTo 0) siftDown(i)
            if (heapSize > 0) lock.notifyAll()
        }
    }

    /** Removes the owner's queued jobs from the heap (their state goes back to 0). Under the lock. */
    private fun removeOwnerJobs(id: Int) {
        val owner = owners[id]
        var w = 0
        for (k in 0 until heapSize) {
            if (heapOwner[k] == id) {
                if (owner != null && owner.jobStates[heapNode[k]] == 1) owner.jobStates[heapNode[k]] = 0
            } else {
                heapPrio[w] = heapPrio[k]
                heapNode[w] = heapNode[k]
                heapGen[w] = heapGen[k]
                heapOwner[w] = heapOwner[k]
                w++
            }
        }
        heapSize = w
        for (i in heapSize / 2 - 1 downTo 0) siftDown(i)
    }

    /**
     * Hands at most [max] finished tiles of owner [id] to [sink] (game thread), then frees their
     * staging buffers. Returns how many were handed over.
     */
    fun pollCompleted(id: Int, max: Int, sink: TileSink): Int {
        var n = 0
        var freed = false
        synchronized(lock) {
            var w = 0
            for (k in 0 until doneCount) {
                val s = done[k]
                val st = staging[s]
                if (!current(st)) {
                    // built for a registration that has ended: back to the pool
                    freeStaging[freeCount++] = s
                    freed = true
                } else if (st.owner == id && n < max) {
                    val owner = owners[id]
                    if (owner != null && owner.jobStates[st.node] == 2 && owner.generations[st.node] == st.gen) {
                        owner.jobStates[st.node] = 0
                    }
                    drained[n++] = s
                } else {
                    done[w++] = s
                }
            }
            doneCount = w
        }
        for (k in 0 until n) sink.accept(staging[drained[k]])
        synchronized(lock) {
            for (k in 0 until n) freeStaging[freeCount++] = drained[k]
            if (n > 0 || freed) lock.notifyAll()
        }
        return n
    }

    /** Jobs waiting, being built, finished but not collected (diagnostics). */
    fun queued(): Int = synchronized(lock) { heapSize }
    fun inFlight(): Int = synchronized(lock) { building }
    fun finished(): Int = synchronized(lock) { doneCount }

    /** Staging buffers free for new jobs (diagnostics). */
    fun freeStagings(): Int = synchronized(lock) { freeCount }

    /** True when nothing is queued, building or waiting to be collected. */
    fun idle(): Boolean = synchronized(lock) { heapSize == 0 && building == 0 && doneCount == 0 }

    fun pause() = synchronized(lock) { paused = true }

    fun resume() = synchronized(lock) {
        paused = false
        lock.notifyAll()
    }

    /** Stops the workers (they finish their current tile). */
    fun shutdown() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        for (t in workers) t.join(2000)
    }

    private fun workerLoop(w: Int) {
        while (running) {
            var st: TileStaging? = null
            var owner: TileJobOwner? = null
            synchronized(lock) {
                while (running && (paused || heapSize == 0 || freeCount == 0)) lock.wait()
                if (!running) return
                // pop the best job
                val node = heapNode[0]
                val gen = heapGen[0]
                val id = heapOwner[0]
                heapSize--
                if (heapSize > 0) {
                    heapPrio[0] = heapPrio[heapSize]
                    heapNode[0] = heapNode[heapSize]
                    heapGen[0] = heapGen[heapSize]
                    heapOwner[0] = heapOwner[heapSize]
                    siftDown(0)
                }
                val o = owners[id]
                if (o != null && o.generations[node] == gen && o.jobStates[node] == 1) {
                    o.jobStates[node] = 2
                    val s = staging[freeStaging[--freeCount]]
                    s.owner = id
                    s.epoch = ownerEpoch[id]
                    s.node = node
                    s.gen = gen
                    st = s
                    owner = o
                    building++
                }
            }
            val s = st ?: continue
            val t0 = System.nanoTime()
            var ok = false
            try {
                ok = owner!!.buildTile(s.node, s, w)
            } catch (e: Throwable) {
                System.err.println("terrain worker $w: tile ${s.node} failed: $e")
                e.printStackTrace()
            }
            val ns = System.nanoTime() - t0
            s.buildNanos = ns
            s.ok = ok
            synchronized(lock) {
                building--
                tilesBuilt++
                buildNanosTotal += ns
                if (ns > buildNanosMax) buildNanosMax = ns
                if (current(s)) {
                    done[doneCount++] = s.index
                } else {
                    // its owner left the pool while it was being built
                    freeStaging[freeCount++] = s.index
                    lock.notifyAll()
                }
            }
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1
            if (l >= heapSize) return
            val r = l + 1
            val m = if (r < heapSize && heapPrio[r] < heapPrio[l]) r else l
            if (heapPrio[m] >= heapPrio[i]) return
            swap(i, m)
            i = m
        }
    }

    private fun swap(a: Int, b: Int) {
        val p = heapPrio[a]; heapPrio[a] = heapPrio[b]; heapPrio[b] = p
        val n = heapNode[a]; heapNode[a] = heapNode[b]; heapNode[b] = n
        val g = heapGen[a]; heapGen[a] = heapGen[b]; heapGen[b] = g
        val o = heapOwner[a]; heapOwner[a] = heapOwner[b]; heapOwner[b] = o
    }

    fun resetStats() = synchronized(lock) {
        tilesBuilt = 0
        buildNanosTotal = 0
        buildNanosMax = 0
    }

    companion object {
        const val MAX_OWNERS = 8
        const val HEAP_CAPACITY = 8192

        /** Worker threads per quality (§9.6): HIGH 3, MEDIUM 2, LOW 2. */
        fun threadsFor(quality: Int) = if (quality >= 2) 3 else 2

        /** Staging buffers per quality: HIGH 32, LOW 16. */
        fun stagingFor(quality: Int) = if (quality >= 2) 32 else 16
    }
}
