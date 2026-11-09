package com.starwake.core.terrain

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/** Relief tags (the Lua strings). */
object Tag {
    const val NONE = 0
    const val DUNE = 1
    const val SEA = 2
    const val CANYON = 3
    const val MESA = 4
    const val CRATER = 5
    const val RIM = 6
    const val BOULDER = 7
    const val PIT = 8
    const val LAVA = 9
    const val ICE = 10
    const val MARE = 11
    const val CRACK = 12
    const val CHAOS = 13
    const val BRIGHT = 14
    const val DARK = 15
    const val LAKE = 16
    const val STREAK = 17
    const val RIDGE = 18
    const val COUNT = 19

    private val NAMES = arrayOf(
        "-", "dune", "sea", "canyon", "mesa", "crater", "rim", "boulder", "pit", "lava", "ice", "mare", "crack", "chaos",
        "bright", "dark", "lake", "streak", "ridge",
    )

    fun name(tag: Int): String = NAMES[tag]
}

/**
 * One feature layer (§2.2.5): a grid of cells of edge [size]; each cell carries a feature with
 * probability [chance], radius rmin..rmax skewed by [skew], spread (reach) for the outer radius.
 * [kind] CRATER, BOULDER, PIT, or BIG_CRATER (the planet-scale layer: depth and rim scale down
 * with size so a 100 km crater is a few km deep, not 16 km).
 */
class FeatureLayer(
    val kind: Int,
    val size: Double,
    val chance: Double,
    val rmin: Double,
    val rmax: Double,
    val depth: Double = 0.0,
    val rim: Double = 0.0,
    val flat: Double = 0.0,
    val reach: Double = 1.6,
    val skew: Double = 2.0,
    val squash: Double = 0.75,
) {
    companion object {
        const val CRATER = 0
        const val BOULDER = 1
        const val PIT = 2
        const val BIG_CRATER = 3
    }
}

/** Fixed-capacity list of collected features (no allocation after construction). */
class FeatureList(val capacity: Int) {
    var count = 0
    val cx = DoubleArray(capacity)
    val cy = DoubleArray(capacity)
    val cz = DoubleArray(capacity)
    val r = DoubleArray(capacity)
    val outer2 = DoubleArray(capacity)
    val layer = IntArray(capacity)

    /** Features dropped because the list was full (diagnostics; should stay 0). */
    var overflow = 0

    fun clear() {
        count = 0
    }

    fun add(x: Double, y: Double, z: Double, radius: Double, outerSq: Double, layerIndex: Int) {
        val i = count
        if (i >= capacity) {
            overflow++
            return
        }
        cx[i] = x
        cy[i] = y
        cz[i] = z
        r[i] = radius
        outer2[i] = outerSq
        layer[i] = layerIndex
        count = i + 1
    }
}

/** Height (m) and strongest tag of [Features.evaluate]. */
class FeatureResult {
    @JvmField var height = 0.0
    @JvmField var tag = Tag.NONE
}

/**
 * Feature layers: Roblox's 2-D cells (plane mode, exact `collect`/`collectFar`/`features`) and the
 * port's 3-D shell lattice on the sphere (§9.4.3): one candidate per 3-D cell of edge `size`, kept
 * only inside a shell of thickness `size` around the reference sphere, which gives Roblox's areal
 * density `chance/size²` everywhere and is seamless across cube faces.
 */
object Features {
    /**
     * Roblox `collect` ([minSize] = 0) or `collectFar` ([minSize] > 0: skip layers and features
     * whose outer radius is below 1.5·minSize) for the box [u0, u1] × [v0, v1].
     */
    fun collectPlane(
        layers: Array<FeatureLayer>?, u0: Double, v0: Double, u1: Double, v1: Double, salt: Double,
        out: FeatureList, minSize: Double = 0.0,
    ) {
        out.clear()
        if (layers == null) return
        val least = minSize * 1.5
        for (index in layers.indices) {
            val layer = layers[index]
            val spread = layer.reach
            val reach = layer.rmax * spread
            if (minSize > 0.0 && reach < least) continue
            val size = layer.size
            val key = salt + (index + 1) * 7.31
            val i0 = floor((u0 - reach) / size).toInt()
            val i1 = floor((u1 + reach) / size).toInt()
            val j0 = floor((v0 - reach) / size).toInt()
            val j1 = floor((v1 + reach) / size).toInt()
            for (i in i0..i1) {
                val di = i.toDouble()
                for (j in j0..j1) {
                    val dj = j.toDouble()
                    if (TerrainMath.robloxHash(di, dj, key) < layer.chance) {
                        val r = layer.rmin + (layer.rmax - layer.rmin) * TerrainMath.robloxHash(di, dj, key + 1.7).pow(layer.skew)
                        val outer = r * spread
                        if (minSize > 0.0 && outer < least) continue
                        val cu = (di + 0.15 + 0.7 * TerrainMath.robloxHash(di, dj, key + 2.9)) * size
                        val cv = (dj + 0.15 + 0.7 * TerrainMath.robloxHash(di, dj, key + 4.3)) * size
                        if (cu + outer > u0 && cu - outer < u1 && cv + outer > v0 && cv - outer < v1) {
                            out.add(cu, cv, 0.0, r, outer * outer, index)
                        }
                    }
                }
            }
        }
    }

    /**
     * The 3-D shell lattice: one candidate per 3-D cell whose centre lies within half a cell of the
     * sphere (on average chance/size² per m² at any orientation, one per column like Roblox's 2-D
     * cells), jittered in 3-D and projected onto the sphere. Collects every feature of [layers] whose outer sphere reaches into the box
     * [x0, x1] × [y0, y1] × [z0, z1] (body space, metres; the box of the reference-sphere patch to
     * be evaluated). Centres lie on the sphere of [radius]. [minSize]: as [collectPlane].
     */
    fun collectSphere(
        layers: Array<FeatureLayer>?, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double,
        radius: Double, salt: Double, out: FeatureList, minSize: Double = 0.0, append: Boolean = false,
    ) {
        if (!append) out.clear()
        if (layers == null) return
        val least = minSize * 1.5
        for (index in layers.indices) {
            val layer = layers[index]
            val spread = layer.reach
            val reach = layer.rmax * spread
            if (minSize > 0.0 && reach < least) continue
            val s = layer.size
            val key = salt + (index + 1) * 7.31
            val k0 = TerrainMath.keyBits(key)
            val k17 = TerrainMath.keyBits(key + 1.7)
            val k29 = TerrainMath.keyBits(key + 2.9)
            val k43 = TerrainMath.keyBits(key + 4.3)
            val k57 = TerrainMath.keyBits(key + 5.7)
            val half = s * 0.5
            // candidates whose centre (on the sphere, within reach of the box) can matter: the
            // cell may lie up to half a cell off the sphere, so widen the box by that too
            val pad = reach + half
            val i0 = floor((x0 - pad) / s).toInt()
            val i1 = floor((x1 + pad) / s).toInt()
            val j0 = floor((y0 - pad) / s).toInt()
            val j1 = floor((y1 + pad) / s).toInt()
            val l0 = floor((z0 - pad) / s).toInt()
            val l1 = floor((z1 + pad) / s).toInt()
            val rIn = radius - half
            val rOut = radius + half
            for (i in i0..i1) {
                // quick reject of whole rows: the cell's nearest/farthest distance from the centre
                val ax0 = i * s
                val ax1 = ax0 + s
                val nx = if (ax0 > 0.0) ax0 else if (ax1 < 0.0) -ax1 else 0.0
                val fx = max(abs(ax0), abs(ax1))
                for (j in j0..j1) {
                    val ay0 = j * s
                    val ay1 = ay0 + s
                    val ny = if (ay0 > 0.0) ay0 else if (ay1 < 0.0) -ay1 else 0.0
                    val fy = max(abs(ay0), abs(ay1))
                    for (l in l0..l1) {
                        val az0 = l * s
                        val az1 = az0 + s
                        val nz = if (az0 > 0.0) az0 else if (az1 < 0.0) -az1 else 0.0
                        val fz = max(abs(az0), abs(az1))
                        // cell entirely inside or outside the shell: skip
                        if (nx * nx + ny * ny + nz * nz > rOut * rOut) continue
                        if (fx * fx + fy * fy + fz * fz < rIn * rIn) continue
                        // the cell belongs to the shell when its centre does (half-open, so a column of
                        // cells across an axis-aligned shell has exactly one: Roblox's one per cell)
                        val mx = (i + 0.5) * s
                        val my = (j + 0.5) * s
                        val mz = (l + 0.5) * s
                        val dc = sqrt(mx * mx + my * my + mz * mz) - radius
                        if (dc < -half || dc >= half) continue
                        if (TerrainMath.hash4(i, j, l, k0) >= layer.chance) continue
                        val cxc = (i + 0.15 + 0.7 * TerrainMath.hash4(i, j, l, k29)) * s
                        val cyc = (j + 0.15 + 0.7 * TerrainMath.hash4(i, j, l, k43)) * s
                        val czc = (l + 0.15 + 0.7 * TerrainMath.hash4(i, j, l, k57)) * s
                        val len = sqrt(cxc * cxc + cyc * cyc + czc * czc)
                        val r = layer.rmin + (layer.rmax - layer.rmin) * TerrainMath.hash4(i, j, l, k17).pow(layer.skew)
                        val outer = r * spread
                        if (minSize > 0.0 && outer < least) continue
                        val k = radius / len
                        val ex = cxc * k
                        val ey = cyc * k
                        val ez = czc * k
                        // sphere (outer) vs box
                        val dx = if (ex < x0) x0 - ex else if (ex > x1) ex - x1 else 0.0
                        val dy = if (ey < y0) y0 - ey else if (ey > y1) ey - y1 else 0.0
                        val dz = if (ez < z0) z0 - ez else if (ez > z1) ez - z1 else 0.0
                        if (dx * dx + dy * dy + dz * dz >= outer * outer) continue
                        out.add(ex, ey, ez, r, outer * outer, index)
                    }
                }
            }
        }
    }

    /**
     * Roblox `features(list, u, v)`: the summed height of every feature reaching the point and the
     * tag of the strongest marked one. Plane mode uses (u, v); sphere mode the chord distance from
     * P = (px, py, pz) on the reference sphere.
     */
    fun evaluate(list: FeatureList, layers: Array<FeatureLayer>, d: Domain, out: FeatureResult) {
        var total = 0.0
        var tag = Tag.NONE
        var strongest = 0.0
        val sphere = d.sphere
        val qx = if (sphere) d.px else d.u
        val qy = if (sphere) d.py else d.v
        val qz = if (sphere) d.pz else 0.0
        for (f in 0 until list.count) {
            val du = qx - list.cx[f]
            val dv = qy - list.cy[f]
            val dw = qz - list.cz[f]
            val d2 = du * du + dv * dv + dw * dw
            if (d2 >= list.outer2[f]) continue
            val r = list.r[f]
            val layer = layers[list.layer[f]]
            val dd = sqrt(d2) / r
            var h = 0.0
            var mark = Tag.NONE
            when (layer.kind) {
                FeatureLayer.CRATER -> {
                    val flat = layer.flat
                    val q = if (dd < flat) flat else dd
                    if (dd < 1.0) {
                        h = layer.depth * (q * q - 1.0)
                        mark = Tag.CRATER
                    }
                    val x = (dd - 1.0) * 4.2
                    h = (h + layer.rim * exp(-x * x)) * r
                    if (dd > 0.82 && dd < 1.3) mark = Tag.RIM
                }
                FeatureLayer.BIG_CRATER -> {
                    // the same profile, its depth and rim scaled down with size (complex craters)
                    val flat = layer.flat
                    val q = if (dd < flat) flat else dd
                    var bowl = 0.0
                    if (dd < 1.0) bowl = layer.depth * (q * q - 1.0)
                    val x = (dd - 1.0) * 4.2
                    val k = bigCraterScale(r)
                    h = (bowl + layer.rim * exp(-x * x)) * r * k
                }
                FeatureLayer.BOULDER -> if (dd < 1.0) {
                    h = r * sqrt(1.0 - dd * dd) * layer.squash
                    mark = Tag.BOULDER
                }
                FeatureLayer.PIT -> if (dd < 1.0) {
                    val w = 1.0 - dd * dd
                    h = -r * layer.depth * w * sqrt(w)
                    mark = Tag.PIT
                }
            }
            total += h
            val weight = abs(h)
            if (mark != Tag.NONE && weight > strongest) {
                strongest = weight
                tag = mark
            }
        }
        out.height = total
        out.tag = tag
    }

    /** Depth scale of planet-scale craters: 1 up to 5 km radius, then ∝ r^-0.6 (a 50 km crater is ~2 km deep). */
    fun bigCraterScale(r: Double): Double = if (r <= 5000.0) 1.0 else (5000.0 / r).pow(0.6)

    /** Largest |height| a layer's features can reach (relief bounds for horizon culling). */
    fun maxHeight(layer: FeatureLayer): Double = when (layer.kind) {
        FeatureLayer.CRATER -> max(layer.depth, layer.rim) * layer.rmax
        FeatureLayer.BIG_CRATER -> max(layer.depth, layer.rim) * layer.rmax * bigCraterScale(layer.rmax)
        FeatureLayer.BOULDER -> layer.rmax * layer.squash
        else -> layer.rmax * layer.depth
    }
}
