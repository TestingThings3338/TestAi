package com.starwake.core.terrain

import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The cube-sphere quadtree (§9.3): six roots, child blocks of four from a fixed node pool, split
 * when the camera is closer than ρ·edge to a tile and its four children are resident, merge beyond
 * 1.15·ρ·edge (hysteresis), horizon culling, residency in all directions (the frustum only matters
 * for drawing), children requested only for resident parents so the cover is always complete.
 *
 * Game thread only (the job system reads [jobState] under its own lock). [update] allocates nothing.
 *
 * Resident tiles that leave the cut stay resident as a cache until their slot is needed
 * ([evictForReserve]); unused child blocks are recycled when the pool runs low.
 */
class TerrainQuadtree(
    val field: SurfaceField,
    val maxLevel: Int,
    val nodeCapacity: Int,
    val slotCapacity: Int,
) {
    val radius = field.radius

    // ---------------------------------------------------------------- node pool (SoA)
    val face = IntArray(nodeCapacity)
    val level = IntArray(nodeCapacity)
    val ix = IntArray(nodeCapacity)
    val iy = IntArray(nodeCapacity)
    val parent = IntArray(nodeCapacity)

    /** First node of the child block, or −1. */
    val child = IntArray(nodeCapacity) { -1 }

    /** GPU slot while resident, −1 otherwise. */
    val slot = IntArray(nodeCapacity) { -1 }

    /** Bumped whenever a node index is reused for another node (stale jobs are recognised by it). */
    val gen = IntArray(nodeCapacity)

    /** 0 none, 1 queued, 2 being built (written under the job system's lock). */
    val jobState = IntArray(nodeCapacity)

    /** Centre direction of the patch (unit, body space) and its angular radius (rad). */
    val dirX = DoubleArray(nodeCapacity)
    val dirY = DoubleArray(nodeCapacity)
    val dirZ = DoubleArray(nodeCapacity)
    val angular = DoubleArray(nodeCapacity)

    /** Radius range of the surface over the tile (estimated until it is built). */
    val rMin = DoubleArray(nodeCapacity)
    val rMax = DoubleArray(nodeCapacity)

    /** Bounding sphere (body space) of the tile. */
    val bx = DoubleArray(nodeCapacity)
    val by = DoubleArray(nodeCapacity)
    val bz = DoubleArray(nodeCapacity)
    val br = DoubleArray(nodeCapacity)

    /** Largest geomorph displacement of the tile (m), from its build. */
    val geomError = FloatArray(nodeCapacity)

    /** Frame the node was last visited by the selection (ancestors of the cut and the cut itself). */
    val visited = IntArray(nodeCapacity) { -1 }

    /** Distance of the node over its split distance at its last visit (< 1: split wanted). */
    val need = FloatArray(nodeCapacity)

    /** Frame the node was last a leaf of the cut (selected for drawing, before frustum culling). */
    val cutFrame = IntArray(nodeCapacity) { -1 }

    /** Time the node joined the cut (ground state) and the time its split-in morph started. */
    val cutSince = DoubleArray(nodeCapacity)
    val morphStart = DoubleArray(nodeCapacity)

    /** Allocated? (blocks are allocated and freed four at a time; roots always). */
    val alive = BooleanArray(nodeCapacity)

    private val freeBlocks = IntArray(nodeCapacity / 4)
    private var freeBlockCount = 0

    // ---------------------------------------------------------------- slots
    private val freeSlots = IntArray(slotCapacity)
    private var freeSlotCount = 0
    private val pendingSlot = IntArray(slotCapacity)
    private val pendingFrame = IntArray(slotCapacity)
    private var pendingCount = 0

    /** Resident tiles right now. */
    var residentCount = 0
        private set

    // ---------------------------------------------------------------- per-frame outputs
    /** Leaves of the cut (drawable, above the horizon), this frame. */
    val drawList = IntArray(nodeCapacity)
    var drawCount = 0
        private set

    /** Nodes wanted (not resident), with priorities (lower = sooner). */
    val wanted = IntArray(WANTED_CAPACITY)
    val wantedPriority = FloatArray(WANTED_CAPACITY)
    var wantedCount = 0
        private set

    /** Nodes in the tree reached by the selection this frame (cut + ancestors + out-of-horizon). */
    var visitedCount = 0
        private set

    /** Resident tiles the selection uses this frame (cut, ancestors, children waiting for siblings). */
    var inUse = 0
        private set

    var frame = 0
        private set
    var time = 0.0
        private set

    // ---------------------------------------------------------------- parameters
    /** Split distance in tile edges. */
    var rho = 2.5

    /** Merge hysteresis factor. */
    var mergeFactor = 1.15

    /** Free slots kept in reserve for uploads (evicting cached tiles to keep it). */
    var slotReserve = 16

    // camera and priority inputs of this frame (body space)
    private var camX = 0.0
    private var camY = 0.0
    private var camZ = 0.0
    private var camR = 1.0
    private var horizonCam = 0.0

    /** Priority focus (body space, see [Focus]). */
    val focus = Focus()

    private val stack = IntArray(4096)
    private val tmp = DoubleArray(6)
    private val corner = DoubleArray(3)

    init {
        // block 0 holds the six roots (two slots of it stay unused); blocks are 4 nodes
        for (b in (nodeCapacity / 4) - 1 downTo 2) freeBlocks[freeBlockCount++] = b
        for (f in 0 until 6) initNode(f, f, 0, 0, 0, -1)
        for (s in slotCapacity - 1 downTo 0) freeSlots[freeSlotCount++] = s
    }

    private fun initNode(n: Int, f: Int, l: Int, i: Int, j: Int, p: Int) {
        alive[n] = true
        face[n] = f
        level[n] = l
        ix[n] = i
        iy[n] = j
        parent[n] = p
        child[n] = -1
        slot[n] = -1
        jobState[n] = 0
        visited[n] = -1
        cutFrame[n] = -1
        geomError[n] = 0f
        // centre direction and angular radius from the corners and edge midpoints
        val m = 1 shl l
        val a0 = 2.0 * i / m - 1.0
        val a1 = 2.0 * (i + 1) / m - 1.0
        val b0 = 2.0 * j / m - 1.0
        val b1 = 2.0 * (j + 1) / m - 1.0
        CubeSphere.dir(f, (a0 + a1) * 0.5, (b0 + b1) * 0.5, tmp, 0)
        dirX[n] = tmp[0]; dirY[n] = tmp[1]; dirZ[n] = tmp[2]
        var minCos = 1.0
        for (k in 0 until 8) {
            val a = when (k) { 0, 3, 5 -> a0; 1, 2, 6 -> a1; else -> (a0 + a1) * 0.5 }
            val b = when (k) { 0, 1, 4 -> b0; 2, 3, 7 -> b1; else -> (b0 + b1) * 0.5 }
            CubeSphere.dir(f, a, b, corner, 0)
            minCos = min(minCos, corner[0] * tmp[0] + corner[1] * tmp[1] + corner[2] * tmp[2])
        }
        angular[n] = acos(minCos.coerceIn(-1.0, 1.0))
        // radius range: the parent's (tighter once it is built), else the field's
        if (p >= 0) {
            rMin[n] = rMin[p]; rMax[n] = rMax[p]
        } else {
            rMin[n] = field.minRadius; rMax[n] = field.maxRadius
        }
        updateBounds(n)
    }

    /** Bounding sphere of the patch between [rMin] and [rMax]: around the cone's axis. */
    fun updateBounds(n: Int) {
        val ang = angular[n]
        val c = kotlin.math.cos(ang)
        val s = kotlin.math.sin(ang)
        // the patch lies in the cone of half angle ang between radii rMin and rMax: its extent along
        // the axis is [rMin·cos(ang), rMax] and across it ≤ rMax·sin(ang)
        val lo = rMin[n] * c
        val hi = rMax[n]
        val mid = (lo + hi) * 0.5
        bx[n] = dirX[n] * mid
        by[n] = dirY[n] * mid
        bz[n] = dirZ[n] * mid
        val half = (hi - lo) * 0.5
        val across = rMax[n] * s
        br[n] = sqrt(half * half + across * across)
    }

    /** Tile edge length (m) at [lvl]. */
    fun edge(lvl: Int) = CubeSphere.edge(radius, lvl)

    /** Vertex spacing (m) of a tile at [lvl] (32 quads per edge). */
    fun spacing(lvl: Int) = edge(lvl) / 32.0

    /** Face coordinates of a node: a0, a1, b0, b1 into out[0..3]. */
    fun bounds(n: Int, out: DoubleArray) {
        val m = 1 shl level[n]
        out[0] = 2.0 * ix[n] / m - 1.0
        out[1] = 2.0 * (ix[n] + 1) / m - 1.0
        out[2] = 2.0 * iy[n] / m - 1.0
        out[3] = 2.0 * (iy[n] + 1) / m - 1.0
    }

    // ---------------------------------------------------------------- selection

    /**
     * Selects the cut for a camera at ([cx], [cy], [cz]) in body space (metres from the centre) at
     * [now] seconds: fills [drawList] and [wanted], marks [visited]. Allocation free.
     */
    fun update(cx: Double, cy: Double, cz: Double, now: Double) {
        frame++
        time = now
        camX = cx; camY = cy; camZ = cz
        camR = sqrt(cx * cx + cy * cy + cz * cz)
        val occ = field.minRadius
        horizonCam = if (camR > occ) acos(occ / camR) else 0.0
        drawCount = 0
        wantedCount = 0
        visitedCount = 0
        inUse = 0
        var sp = 0
        for (f in 5 downTo 0) stack[sp++] = f
        while (sp > 0) {
            val n = stack[--sp]
            visited[n] = frame
            visitedCount++
            if (slot[n] >= 0) inUse++
            if (beyondHorizon(n)) {
                // nothing behind the planet is drawn or refined; a non-resident root is still wanted
                if (slot[n] < 0 && level[n] == 0) want(n, 1e6f)
                continue
            }
            val e = edge(level[n])
            val d = distance(n)
            val split = rho * e
            need[n] = (d / split).toFloat()
            val c = child[n]
            val childrenResident = c >= 0 && slot[c] >= 0 && slot[c + 1] >= 0 && slot[c + 2] >= 0 && slot[c + 3] >= 0
            val resident = slot[n] >= 0
            val wantSplit = level[n] < maxLevel && d < split
            val keepSplit = childrenResident && d <= split * mergeFactor && level[n] < maxLevel
            if (childrenResident && (wantSplit || keepSplit || !resident)) {
                if (sp + 4 > stack.size) continue
                stack[sp++] = c + 3
                stack[sp++] = c + 2
                stack[sp++] = c + 1
                stack[sp++] = c
                // an evicted ancestor that wants to merge: the children stay drawn until it is back
                if (!resident && !wantSplit && !keepSplit) want(n, priority(n, d, e) * 0.5f)
                continue
            }
            if (!resident) {
                // not drawable: ask for it (a parent always covers it meanwhile, except the roots)
                want(n, priority(n, d, e) * 0.5f)
                continue
            }
            if (wantSplit) {
                val cc = if (c >= 0) c else allocChildren(n)
                if (cc >= 0) {
                    for (k in 0 until 4) {
                        val ch = cc + k
                        // in use: children arriving one by one must not be evicted before their siblings
                        if (visited[ch] != frame && slot[ch] >= 0) inUse++
                        visited[ch] = frame
                        if (slot[ch] < 0) want(ch, priority(ch, distance(ch), edge(level[ch])))
                    }
                }
            }
            // a leaf of the cut
            if (cutFrame[n] != frame - 1) {
                cutSince[n] = now
                // joined because its parent split (not because its children merged): morph in over time
                val p = parent[n]
                morphStart[n] = if (p >= 0 && cutFrame[p] == frame - 1) now else -1e9
            }
            cutFrame[n] = frame
            if (drawCount < drawList.size) drawList[drawCount++] = n
        }
        reclaimPending()
    }

    /** Horizon culling (§9.3) with the lowest possible surface as the occluder. */
    fun beyondHorizon(n: Int): Boolean {
        if (camR <= field.minRadius) return false
        val dot = (camX * dirX[n] + camY * dirY[n] + camZ * dirZ[n]) / camR
        val angle = acos(dot.coerceIn(-1.0, 1.0))
        val occ = field.minRadius
        val tileHorizon = acos(min(1.0, occ / max(rMax[n], occ)))
        return angle - angular[n] > horizonCam + tileHorizon
    }

    /**
     * Distance from the camera to the patch: the nearest direction inside the patch (the camera's
     * direction clamped to the tile's face rectangle) at the radius nearest the camera, clamped to
     * the tile's radius range. Falls back to the bounding sphere when the camera is behind the face.
     */
    fun distance(n: Int): Double {
        val f = face[n]
        val r = camR
        if (r < 1e-6) return 0.0
        if (!CubeSphere.coords(f, camX / r, camY / r, camZ / r, tmp, 4)) {
            val dx = camX - bx[n]
            val dy = camY - by[n]
            val dz = camZ - bz[n]
            return max(0.0, sqrt(dx * dx + dy * dy + dz * dz) - br[n])
        }
        bounds(n, tmp)
        val a = tmp[4].coerceIn(tmp[0], tmp[1])
        val b = tmp[5].coerceIn(tmp[2], tmp[3])
        CubeSphere.dir(f, a, b, corner, 0)
        val along = camX * corner[0] + camY * corner[1] + camZ * corner[2]
        val rr = along.coerceIn(rMin[n], rMax[n])
        val dx = camX - corner[0] * rr
        val dy = camY - corner[1] * rr
        val dz = camZ - corner[2] * rr
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Request priority (lower = sooner): distance in tile edges, ×0.25 under the path, ×0.5 at the focus / near the ship. */
    private fun priority(n: Int, d: Double, e: Double): Float {
        var p = d / e
        val f = focus
        if (f.active) {
            val pc = f.pathFactor(this, n, e)
            p *= pc
        }
        return p.toFloat()
    }

    private fun want(n: Int, p: Float) {
        if (wantedCount >= WANTED_CAPACITY) return
        wanted[wantedCount] = n
        wantedPriority[wantedCount] = p
        wantedCount++
    }

    // ---------------------------------------------------------------- pool management

    private fun allocChildren(n: Int): Int {
        if (freeBlockCount == 0) recycleBlocks()
        if (freeBlockCount == 0) return -1
        val b = freeBlocks[--freeBlockCount]
        val c = b * 4
        val l = level[n] + 1
        val i = ix[n] * 2
        val j = iy[n] * 2
        for (k in 0 until 4) gen[c + k]++
        initNode(c, face[n], l, i, j, n)
        initNode(c + 1, face[n], l, i + 1, j, n)
        initNode(c + 2, face[n], l, i, j + 1, n)
        initNode(c + 3, face[n], l, i + 1, j + 1, n)
        child[n] = c
        return c
    }

    /**
     * Frees child blocks nobody uses: all four not visited this frame, not resident, no job, no
     * children of their own. Called when the pool runs out.
     */
    fun recycleBlocks(): Int {
        var freed = 0
        // repeat a few passes: freeing leaves exposes their parents' blocks
        for (pass in 0 until 4) {
            var any = false
            for (b in 2 until nodeCapacity / 4) {
                val c = b * 4
                if (!alive[c]) continue
                var free = true
                for (k in 0 until 4) {
                    val n = c + k
                    if (visited[n] == frame || slot[n] >= 0 || jobState[n] != 0 || child[n] >= 0) {
                        free = false
                        break
                    }
                }
                if (!free) continue
                val p = parent[c]
                if (p >= 0 && child[p] == c) child[p] = -1
                for (k in 0 until 4) {
                    alive[c + k] = false
                    gen[c + k]++
                }
                freeBlocks[freeBlockCount++] = b
                freed++
                any = true
            }
            if (!any) break
        }
        return freed
    }

    /** A free slot, or −1. */
    fun allocSlot(): Int = if (freeSlotCount > 0) freeSlots[--freeSlotCount] else -1

    val freeSlotsNow get() = freeSlotCount

    /** Releases a resident node's slot (it becomes free 3 frames later: the GPU may still read it). */
    fun releaseSlot(n: Int) {
        val s = slot[n]
        if (s < 0) return
        slot[n] = -1
        residentCount--
        pendingSlot[pendingCount] = s
        pendingFrame[pendingCount] = frame
        pendingCount++
    }

    private fun reclaimPending() {
        var w = 0
        for (k in 0 until pendingCount) {
            if (frame - pendingFrame[k] >= SLOT_REUSE_FRAMES) {
                freeSlots[freeSlotCount++] = pendingSlot[k]
            } else {
                pendingSlot[w] = pendingSlot[k]
                pendingFrame[w] = pendingFrame[k]
                w++
            }
        }
        pendingCount = w
    }

    /** Makes [n] resident in slot [s] with the bounds of its build. */
    fun makeResident(n: Int, s: Int, rMinBuilt: Double, rMaxBuilt: Double, err: Float) {
        slot[n] = s
        residentCount++
        rMin[n] = rMinBuilt
        rMax[n] = rMaxBuilt
        geomError[n] = err
        updateBounds(n)
        // tighter estimates for children that are not built yet
        val c = child[n]
        if (c >= 0) {
            for (k in 0 until 4) {
                if (slot[c + k] < 0) {
                    rMin[c + k] = rMinBuilt; rMax[c + k] = rMaxBuilt; updateBounds(c + k)
                }
            }
        }
    }

    /**
     * Keeps [slotReserve] slots free (or pending): first by evicting the least recently visited
     * cached tiles (not part of this frame's tree), then, under memory pressure, ancestors of the
     * cut whose merge is least imminent (their children stay drawn; a merge waits until the
     * ancestor is rebuilt). Never a leaf of the cut, never a child waiting for its siblings.
     * Returns the number evicted.
     */
    fun evictForReserve(): Int {
        var evicted = 0
        // one pass per 64 slots: the least recently visited cached tiles (older first, finer first
        // among equals); a pass that finds fewer than it asked for has run out of cached tiles
        while (true) {
            val need = slotReserve - (freeSlotCount + pendingCount)
            if (need <= 0) return evicted
            val k = minOf(need, evictCand.size)
            var found = 0
            for (n in 0 until nodeCapacity) {
                if (!alive[n] || slot[n] < 0) continue
                val v = visited[n]
                if (v >= frame) continue
                val key = v.toLong() * 64 - level[n]
                if (found < k) {
                    var i = found++
                    while (i > 0 && evictKey[i - 1] > key) {
                        evictKey[i] = evictKey[i - 1]; evictCand[i] = evictCand[i - 1]; i--
                    }
                    evictKey[i] = key; evictCand[i] = n
                } else if (key < evictKey[k - 1]) {
                    var i = k - 1
                    while (i > 0 && evictKey[i - 1] > key) {
                        evictKey[i] = evictKey[i - 1]; evictCand[i] = evictCand[i - 1]; i--
                    }
                    evictKey[i] = key; evictCand[i] = n
                }
            }
            for (i in 0 until found) releaseSlot(evictCand[i])
            evicted += found
            if (found < k) break
        }
        // under memory pressure: ancestors of the cut, one scan per slot (rare)
        while (freeSlotCount + pendingCount < slotReserve) {
            val best = interiorToEvict()
            if (best < 0) break
            releaseSlot(best)
            evicted++
        }
        return evicted
    }

    private val evictCand = IntArray(64)
    private val evictKey = LongArray(64)

    /** The resident interior node of the current tree (all four children drawn or deeper) deepest inside its split range. */
    private fun interiorToEvict(): Int {
        var best = -1
        var bestNeed = Float.MAX_VALUE
        for (n in 0 until nodeCapacity) {
            if (!alive[n] || slot[n] < 0 || visited[n] != frame || cutFrame[n] == frame || level[n] < 3) continue
            val c = child[n]
            if (c < 0) continue
            if (slot[c] < 0 || slot[c + 1] < 0 || slot[c + 2] < 0 || slot[c + 3] < 0) continue
            if (need[n] < bestNeed) {
                bestNeed = need[n]
                best = n
            }
        }
        return best
    }

    /** Slots in use plus slots the selection still asks for: above [slotCapacity] the cut cannot be completed. */
    val slotDemand get() = inUse + wantedCount + slotReserve

    /** Drops everything (body switch): every slot is released, every block freed. */
    fun clear() {
        for (n in 0 until nodeCapacity) {
            if (!alive[n]) continue
            if (slot[n] >= 0) releaseSlot(n)
        }
        for (b in 2 until nodeCapacity / 4) {
            val c = b * 4
            if (alive[c]) {
                for (k in 0 until 4) {
                    alive[c + k] = false
                    gen[c + k]++
                }
                freeBlocks[freeBlockCount++] = b
            }
        }
        for (f in 0 until 6) {
            child[f] = -1
            jobState[f] = 0
        }
    }

    /**
     * The leaf of the last cut containing direction (x, y, z) (unit, body space), or −1 if none
     * (nothing resident there yet).
     */
    fun leafAt(x: Double, y: Double, z: Double): Int {
        var n = CubeSphere.faceOf(x, y, z)
        if (!CubeSphere.coords(face[n], x, y, z, tmp, 4)) return -1
        val a = tmp[4]
        val b = tmp[5]
        val last = frame
        while (true) {
            if (cutFrame[n] == last) return n
            val c = child[n]
            if (c < 0) return -1
            bounds(n, tmp)
            val am = (tmp[0] + tmp[1]) * 0.5
            val bm = (tmp[2] + tmp[3]) * 0.5
            n = c + (if (a >= am) 1 else 0) + (if (b >= bm) 2 else 0)
        }
    }

    /** Counts nodes that are alive (diagnostics). */
    fun aliveCount(): Int {
        var c = 0
        for (n in 0 until nodeCapacity) if (alive[n]) c++
        return c
    }

    /**
     * Priority shaping (§9.6): ×0.25 under the predicted path p + v·t, t ∈ [0, PathSeconds], ±0.75
     * tile sideways, when low; ×0.5 around the focus; ×0.5 within NearRadius of the ship when low.
     */
    class Focus {
        var active = false
        var shipX = 0.0
        var shipY = 0.0
        var shipZ = 0.0
        var velX = 0.0
        var velY = 0.0
        var velZ = 0.0
        var focusX = 0.0
        var focusY = 0.0
        var focusZ = 0.0
        var hasFocus = false

        /** Height above ground of the ship (m). */
        var height = 1e9

        internal fun pathFactor(t: TerrainQuadtree, n: Int, e: Double): Double {
            var k = 1.0
            val cx = t.bx[n]
            val cy = t.by[n]
            val cz = t.bz[n]
            if (height < PATH_HEIGHT) {
                // distance from the tile centre to the path segment
                val vx = velX * PATH_SECONDS
                val vy = velY * PATH_SECONDS
                val vz = velZ * PATH_SECONDS
                val len2 = vx * vx + vy * vy + vz * vz
                var u = 0.0
                val wx = cx - shipX
                val wy = cy - shipY
                val wz = cz - shipZ
                if (len2 > 1.0) u = ((wx * vx + wy * vy + wz * vz) / len2).coerceIn(0.0, 1.0)
                val dx = wx - vx * u
                val dy = wy - vy * u
                val dz = wz - vz * u
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist < 0.75 * e + t.br[n]) k *= 0.25
                if (sqrt(wx * wx + wy * wy + wz * wz) < NEAR_RADIUS + t.br[n]) k *= 0.5
            }
            if (hasFocus) {
                val fx = cx - focusX
                val fy = cy - focusY
                val fz = cz - focusZ
                if (sqrt(fx * fx + fy * fy + fz * fz) < 2.0 * e + t.br[n]) k *= 0.5
            }
            return k
        }
    }

    companion object {
        const val WANTED_CAPACITY = 4096
        const val SLOT_REUSE_FRAMES = 3
        const val PATH_HEIGHT = 1500.0
        const val PATH_SECONDS = 2.5
        const val NEAR_RADIUS = 640.0
    }
}
