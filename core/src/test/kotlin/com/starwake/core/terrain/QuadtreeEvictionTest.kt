package com.starwake.core.terrain

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Memory pressure (§9.3, [TerrainQuadtree.evictForReserve]): evicting resident ancestors of the cut
 * must leave the cut as it is (their children stay drawn), also when an evicted ancestor ends up
 * behind the horizon, and also while the camera moves and tiles keep arriving.
 */
class QuadtreeEvictionTest {
    private val radius = 6.36e6

    /** A field with no relief: tiles between R and R + 400 m (the spec's relief margin). */
    private fun tree(slots: Int): TerrainQuadtree {
        val style = CrateredStyle(StyleCatalog.LUNA)
        val field = SurfaceField(radius, style, 1, GenericMacro(radius, 1, Palettes.DEFAULT, style))
        val t = TerrainQuadtree(field, CubeSphere.maxLevel(radius, 2.5), 16384, slots)
        t.rho = 2.5
        t.slotReserve = 20
        for (n in 0 until 6) { t.rMin[n] = radius; t.rMax[n] = radius + 400; t.updateBounds(n) }
        return t
    }

    /**
     * Makes up to [max] wanted tiles resident at once ("instant builds"), lowest priority value
     * first as the workers take them, nothing at or beyond [maxPriority].
     */
    private fun build(t: TerrainQuadtree, max: Int = Int.MAX_VALUE, maxPriority: Float = Float.MAX_VALUE) {
        var built = 0
        while (built < max) {
            var best = -1
            for (k in 0 until t.wantedCount) {
                val n = t.wanted[k]
                if (t.slot[n] >= 0 || t.wantedPriority[k] >= maxPriority) continue
                if (best < 0 || t.wantedPriority[k] < t.wantedPriority[best]) best = k
            }
            if (best < 0) break
            val s = t.allocSlot()
            if (s < 0) break
            t.makeResident(t.wanted[best], s, radius, radius + 400, 0.5f)
            built++
        }
    }

    /** One frame: selection, eviction for the reserve, instant builds. */
    private fun frame(t: TerrainQuadtree, cam: DoubleArray, now: Double) {
        t.update(cam[0], cam[1], cam[2], now)
        t.evictForReserve()
        build(t)
    }

    private fun converge(t: TerrainQuadtree, cam: DoubleArray, t0: Double): Double {
        var now = t0
        for (f in 0 until 400) {
            frame(t, cam, now)
            now += 1.0 / 60
            if (t.wantedCount == 0) break
        }
        // three quiet frames: slots released meanwhile are free again
        repeat(4) { t.update(cam[0], cam[1], cam[2], now); now += 1.0 / 60 }
        return now
    }

    private fun camAbove(face: Int, a: Double, b: Double, alt: Double, dir: DoubleArray): DoubleArray {
        CubeSphere.dir(face, a, b, dir, 0)
        val r = radius + alt
        return doubleArrayOf(dir[0] * r, dir[1] * r, dir[2] * r)
    }

    private fun cachedCount(t: TerrainQuadtree): Int {
        var c = 0
        for (n in 0 until t.nodeCapacity) if (t.alive[n] && t.slot[n] >= 0 && t.visited[n] < t.frame) c++
        return c
    }

    private fun drawnSet(t: TerrainQuadtree): IntArray = t.drawList.copyOfRange(0, t.drawCount).sortedArray()

    /** The ancestor of [leaf] at [level]. */
    private fun ancestor(t: TerrainQuadtree, leaf: Int, level: Int): Int {
        var n = leaf
        while (t.level[n] > level) n = t.parent[n]
        return n
    }

    /**
     * The reviewer's case: 150 m above the ground, a converged cut (L17 under the camera), no cached
     * tiles left, the reserve short. The interior evictions keep every evicted ancestor between a
     * resident parent and four resident children, and the next frame draws exactly the same cut.
     * Then everything evictable is evicted: still the same cut.
     */
    @Test
    fun interiorEvictionKeepsTheCut() {
        val t = tree(1600)
        val d = DoubleArray(3)
        val cam = camAbove(4, 0.123, -0.217, 150.0, d)
        var now = converge(t, cam, 0.0)
        val leaf0 = t.leafAt(d[0], d[1], d[2])
        assertEquals(t.maxLevel, t.level[leaf0], "converged to the finest level under the camera")
        val drawn0 = drawnSet(t)
        val before = IntArray(t.nodeCapacity) { t.slot[it] }
        // no cached tiles left and three slots short: three ancestors must go
        t.slotReserve = t.freeSlotsNow + cachedCount(t) + 3
        val evicted = t.evictForReserve()
        assertTrue(evicted >= 3, "evicted $evicted")
        var interior = 0
        for (n in 0 until t.nodeCapacity) {
            if (before[n] < 0 || t.slot[n] >= 0 || t.visited[n] != t.frame) continue
            interior++
            val p = t.parent[n]
            val c = t.child[n]
            assertTrue(p >= 0 && t.slot[p] >= 0, "evicted L${t.level[n]} has a resident parent")
            assertTrue(c >= 0 && (0 until 4).all { t.slot[c + it] >= 0 }, "evicted L${t.level[n]} keeps four resident children")
        }
        assertEquals(3, interior, "interior evictions")
        t.update(cam[0], cam[1], cam[2], now)
        now += 1.0 / 60
        val leaf1 = t.leafAt(d[0], d[1], d[2])
        assertEquals(leaf0, leaf1, "the leaf under the camera is the same tile")
        assertTrue(drawn0.contentEquals(drawnSet(t)), "the same cut is drawn (${t.drawCount} vs ${drawn0.size})")

        // everything that may go, goes: alternate levels at most, the cut is still the same
        t.slotReserve = t.slotCapacity
        val more = t.evictForReserve()
        assertTrue(more > 20, "evicted $more more")
        t.update(cam[0], cam[1], cam[2], now)
        assertTrue(drawn0.contentEquals(drawnSet(t)), "the same cut after $more more evictions")
        assertEquals(leaf0, t.leafAt(d[0], d[1], d[2]))
    }

    /**
     * An evicted ancestor behind the horizon: its parent still descends through it (the camera is
     * within the parent's split distance), but nothing behind the horizon is refined. Its children
     * are what make it drawable, so they must survive the cache eviction while it waits to be
     * rebuilt (here: never, the workers never reach the lowest priority). Otherwise its parent, a
     * 625 km tile, would turn into a leaf of the cut.
     */
    @Test
    fun evictedAncestorBehindTheHorizonKeepsItsCover() {
        val t = tree(1600)
        val d = DoubleArray(3)
        val camA = camAbove(4, 0.123, -0.217, 150.0, d)
        var now = converge(t, camA, 0.0)
        val l5 = ancestor(t, t.leafAt(d[0], d[1], d[2]), 5)
        val l4 = t.parent[l5]
        assertTrue(t.slot[l5] >= 0 && t.child[l5] >= 0, "L5 above A is resident and split")
        t.releaseSlot(l5)
        // B: on the side of the L4 tile away from the evicted L5, where the L4 tile is still in front
        // of the horizon (the field's lowest radius is the occluder) and the L5 tile is behind it
        val c4 = doubleArrayOf(t.dirX[l4], t.dirY[l4], t.dirZ[l4])
        val c5 = doubleArrayOf(t.dirX[l5], t.dirY[l5], t.dirZ[l5])
        val k = c4[0] * c5[0] + c4[1] * c5[1] + c4[2] * c5[2]
        val away = DoubleArray(3) { c4[it] * k - c5[it] }   // tangent at c4, away from c5
        val al = sqrt(away[0] * away[0] + away[1] * away[1] + away[2] * away[2])
        val dB = DoubleArray(3)
        val camB = DoubleArray(3)
        fun behind(n: Int): Boolean {
            val cr = radius + 150.0
            val occ = t.field.minRadius
            val angle = acos((dB[0] * t.dirX[n] + dB[1] * t.dirY[n] + dB[2] * t.dirZ[n]).coerceIn(-1.0, 1.0))
            return angle - t.angular[n] > acos(occ / cr) + acos(min(1.0, occ / max(t.rMax[n], occ)))
        }
        var km = 0
        while (km < 3000) {
            km += 10
            val th = km * 1000.0 / radius
            for (i in 0 until 3) dB[i] = c4[i] * cos(th) + away[i] / al * sin(th)
            if (behind(l5) && !behind(l4)) break
        }
        assertTrue(km < 3000, "a place for B")
        for (i in 0 until 3) camB[i] = dB[i] * (radius + 150.0)
        // every cached tile goes, every frame (A's old neighbourhood with them); eight tiles arrive
        // per frame, so B refines over a few hundred frames
        var lastLevel = -1
        for (f in 0 until 480) {
            t.update(camB[0], camB[1], camB[2], now)
            assertTrue(t.beyondHorizon(l5) && !t.beyondHorizon(l4), "B ($km km from the L4 tile's centre) sees the L4 tile, not the L5")
            assertTrue(t.need[l4] < 1f, "the L4 tile wants to split (need ${t.need[l4]})")
            assertTrue(t.visited[l4] == t.frame && t.cutFrame[l4] != t.frame, "frame $f: the L4 tile is an interior node of the cut")
            assertTrue(t.visited[l5] == t.frame, "frame $f: the evicted L5 is part of the tree")
            t.slotReserve = t.slotCapacity - t.inUse
            t.evictForReserve()
            build(t, 8, 1e5f)
            now += 1.0 / 60
            assertTrue(t.slot[l5] < 0, "not rebuilt (lowest priority)")
            val leaf = t.leafAt(dB[0], dB[1], dB[2])
            val lvl = if (leaf >= 0) t.level[leaf] else -1
            assertTrue(lvl >= lastLevel, "frame $f: the leaf under B went from L$lastLevel to L$lvl")
            lastLevel = lvl
        }
        assertEquals(t.maxLevel, lastLevel, "B refined to the finest level")
        val c = t.child[l5]
        assertTrue((0 until 4).all { t.slot[c + it] >= 0 }, "the evicted L5's children are still resident")
    }

    /**
     * Sustained pressure while flying: every frame all cached tiles and eight more ancestors are
     * evicted and at most eight tiles arrive (HIGH's upload cap). The leaf under the camera never
     * jumps to a coarser level by more than one, and every direction in front of the horizon stays
     * covered.
     */
    @Test
    fun pressureWhileFlying() {
        val t = tree(1600)
        val d = DoubleArray(3)
        val u0 = DoubleArray(3)
        var cam = camAbove(4, 0.05, 0.1, 150.0, u0)
        var now = converge(t, cam, 0.0)
        // east along a great circle at 300 m/s, 150 m up
        var ex = u0[2]
        var ez = -u0[0]
        val el = sqrt(ex * ex + ez * ez)
        ex /= el; ez /= el
        var prev = t.level[t.leafAt(u0[0], u0[1], u0[2])]
        var evictedTotal = 0
        var worstDrop = 0
        for (f in 0 until 900) {
            val a = f * 5.0 / radius
            d[0] = u0[0] * cos(a) + ex * sin(a)
            d[1] = u0[1] * cos(a)
            d[2] = u0[2] * cos(a) + ez * sin(a)
            val r = radius + 150.0
            cam = doubleArrayOf(d[0] * r, d[1] * r, d[2] * r)
            t.update(cam[0], cam[1], cam[2], now)
            t.slotReserve = t.slotCapacity - t.inUse + 8
            evictedTotal += t.evictForReserve()
            build(t, 8)
            now += 1.0 / 60
            val leaf = t.leafAt(d[0], d[1], d[2])
            assertTrue(leaf >= 0, "frame $f: no leaf under the camera")
            val lvl = t.level[leaf]
            worstDrop = maxOf(worstDrop, prev - lvl)
            prev = lvl
            if (f % 100 == 0) {
                // complete cover around the camera (±40 km)
                for (i in -4..4) for (j in -4..4) {
                    val x = d[0] + (i * 1e-3) * ex
                    val y = d[1] + j * 1e-3
                    val z = d[2] + (i * 1e-3) * ez
                    val l = sqrt(x * x + y * y + z * z)
                    assertTrue(t.leafAt(x / l, y / l, z / l) >= 0, "frame $f: hole near the camera")
                }
            }
        }
        println("pressure while flying: $evictedTotal evictions in 900 frames, worst drop of the leaf under the camera $worstDrop levels")
        assertTrue(evictedTotal > 900, "the pressure evicted ($evictedTotal)")
        assertTrue(worstDrop <= 1, "the leaf under the camera dropped $worstDrop levels in one frame")
    }
}
