package com.starwake.core.terrain

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The tile vertex format (24 bytes, §9.8) and the shared topology.
 *
 * | offset | attribute | type | content |
 * |---|---|---|---|
 * | 0 | a_pos | short4 norm | x, y, z in the tile's box around its origin; w = morph delta / morph scale |
 * | 8 | a_nrm | byte4 norm | octahedral normal (own), octahedral normal of the parent surface (morph target) |
 * | 12 | a_col | ubyte4 norm | sRGB colour, water share |
 * | 16 | a_par | ubyte4 norm | the parent surface's colour and water share (colour morph) |
 * | 20 | a_cls | ubyte4 norm | detail class weights: soil, rock, granular, ice |
 *
 * Vertices: the 33 × 33 grid (index j·33 + i, i along the face's U, j along V), then four skirt
 * rows of 33 (bottom j = 0, top j = 32, left i = 0, right i = 32). Triangles: two per quad with
 * the diagonal (0,0)–(1,1), counter-clockwise seen from outside; skirt walls facing outwards.
 */
object TileFormat {
    const val N = 33
    const val QUADS = 32
    const val GRID_VERTICES = N * N
    const val SKIRT_VERTICES = 4 * N
    const val VERTICES = GRID_VERTICES + SKIRT_VERTICES
    const val STRIDE = 24
    const val TILE_BYTES = VERTICES * STRIDE
    const val GRID_INDICES = QUADS * QUADS * 6
    const val INDICES = GRID_INDICES + 4 * QUADS * 6

    /** Triangles drawn per tile (grid + skirts). */
    const val TRIANGLES = INDICES / 3

    fun skirtBottom(i: Int) = GRID_VERTICES + i
    fun skirtTop(i: Int) = GRID_VERTICES + N + i
    fun skirtLeft(j: Int) = GRID_VERTICES + 2 * N + j
    fun skirtRight(j: Int) = GRID_VERTICES + 3 * N + j

    /** The shared index buffer. */
    fun indices(): ShortArray {
        val idx = ShortArray(INDICES)
        var o = 0
        fun tri(a: Int, b: Int, c: Int) {
            idx[o++] = a.toShort(); idx[o++] = b.toShort(); idx[o++] = c.toShort()
        }
        for (j in 0 until QUADS) for (i in 0 until QUADS) {
            val v00 = j * N + i
            val v10 = v00 + 1
            val v01 = v00 + N
            val v11 = v01 + 1
            tri(v00, v10, v11)
            tri(v00, v11, v01)
        }
        for (k in 0 until QUADS) {
            // bottom (j = 0), outward −V
            val e0 = k
            val e1 = k + 1
            tri(skirtBottom(k), skirtBottom(k + 1), e1)
            tri(skirtBottom(k), e1, e0)
            // top (j = 32), outward +V
            val t0 = QUADS * N + k
            val t1 = t0 + 1
            tri(skirtTop(k + 1), skirtTop(k), t0)
            tri(skirtTop(k + 1), t0, t1)
            // left (i = 0), outward −U
            val l0 = k * N
            val l1 = (k + 1) * N
            tri(skirtLeft(k + 1), skirtLeft(k), l0)
            tri(skirtLeft(k + 1), l0, l1)
            // right (i = 32), outward +U
            val r0 = k * N + QUADS
            val r1 = (k + 1) * N + QUADS
            tri(skirtRight(k), skirtRight(k + 1), r1)
            tri(skirtRight(k), r1, r0)
        }
        return idx
    }

    /** Octahedral encoding of a unit vector into two bytes (−127..127). */
    fun octEncode(x: Double, y: Double, z: Double, out: ByteArray, o: Int) {
        val l1 = abs(x) + abs(y) + abs(z)
        var u = x / l1
        var v = y / l1
        if (z < 0) {
            val uu = (1 - abs(v)) * (if (u >= 0) 1.0 else -1.0)
            val vv = (1 - abs(u)) * (if (v >= 0) 1.0 else -1.0)
            u = uu
            v = vv
        }
        out[o] = (u * 127.0).roundToInt().coerceIn(-127, 127).toByte()
        out[o + 1] = (v * 127.0).roundToInt().coerceIn(-127, 127).toByte()
    }

    /** Decodes [octEncode] (tests). */
    fun octDecode(bx: Byte, by: Byte, out: DoubleArray) {
        var x = bx / 127.0
        var y = by / 127.0
        val z = 1 - abs(x) - abs(y)
        if (z < 0) {
            val xx = (1 - abs(y)) * (if (x >= 0) 1.0 else -1.0)
            val yy = (1 - abs(x)) * (if (y >= 0) 1.0 else -1.0)
            x = xx
            y = yy
        }
        val l = sqrt(x * x + y * y + z * z)
        out[0] = x / l; out[1] = y / l; out[2] = z / l
    }
}

/** Samples of a grid stored as arrays (the fields [SurfaceField.colour] needs later). */
class SampleGrid(val capacity: Int) {
    val nx = DoubleArray(capacity)
    val ny = DoubleArray(capacity)
    val nz = DoubleArray(capacity)
    val hLocal = DoubleArray(capacity)
    val tag = IntArray(capacity)
    val groundR = DoubleArray(capacity)
    val waterR = DoubleArray(capacity)
    val floorR = DoubleArray(capacity)
    val wet = DoubleArray(capacity)
    val cold = DoubleArray(capacity)
    val dry = DoubleArray(capacity)
    private val m = DoubleArray(capacity * 12)

    fun store(i: Int, s: SurfaceSample) {
        nx[i] = s.nx; ny[i] = s.ny; nz[i] = s.nz
        hLocal[i] = s.hLocal; tag[i] = s.tag
        groundR[i] = s.groundR; waterR[i] = s.waterR; floorR[i] = s.floorR
        wet[i] = s.wet; cold[i] = s.cold; dry[i] = s.dry
        val o = i * 12
        val q = s.macro
        m[o] = q.height; m[o + 1] = q.waterDatum; m[o + 2] = q.tintR; m[o + 3] = q.tintG; m[o + 4] = q.tintB
        m[o + 5] = q.frost; m[o + 6] = q.seaR; m[o + 7] = q.seaG; m[o + 8] = q.seaB; m[o + 9] = q.seaLook
        m[o + 10] = q.wet; m[o + 11] = q.cold
    }

    fun macroHeight(i: Int) = m[i * 12]

    fun load(i: Int, s: SurfaceSample, frostR: Double, frostG: Double, frostB: Double) {
        s.nx = nx[i]; s.ny = ny[i]; s.nz = nz[i]
        s.hLocal = hLocal[i]; s.tag = tag[i]
        s.groundR = groundR[i]; s.waterR = waterR[i]; s.floorR = floorR[i]
        s.wet = wet[i]; s.cold = cold[i]; s.dry = dry[i]
        val o = i * 12
        val q = s.macro
        q.height = m[o]; q.waterDatum = m[o + 1]; q.tintR = m[o + 2]; q.tintG = m[o + 3]; q.tintB = m[o + 4]
        q.frost = m[o + 5]; q.seaR = m[o + 6]; q.seaG = m[o + 7]; q.seaB = m[o + 8]; q.seaLook = m[o + 9]
        q.wet = m[o + 10]; q.cold = m[o + 11]
        q.frostR = frostR; q.frostG = frostG; q.frostB = frostB
    }
}

/**
 * Builds one tile (§9.6 steps 1–8) on a worker: 35 × 35 samples (the 33 × 33 vertices and a border
 * ring for normals and slopes) at the tile's band limit, the parent surface at the even vertices
 * (19 × 19 samples at the parent's band limit) for geomorphing, normals, slopes, paint and colours,
 * skirts, packing into the 24-byte format, bounds and the geometric error. One instance per worker.
 */
class TileBuilder(private val field: SurfaceField) {
    private val fs = FieldScratch()
    private val sample = SurfaceSample()
    private val colour = VertexColour()
    private val grid = SampleGrid(G * G)
    private val pgrid = SampleGrid(PG * PG)
    private val dir = DoubleArray(3)
    private val ga = DoubleArray(G * G)
    private val gb = DoubleArray(G * G)
    private val lattice = MacroLattice(21 * 21)
    private val macroIn = MacroSample()

    // per vertex results (33 × 33)
    private val px = DoubleArray(G * G)
    private val py = DoubleArray(G * G)
    private val pz = DoubleArray(G * G)
    private val target = DoubleArray(N * N)
    private val nrm = DoubleArray(N * N * 3)
    private val pnrm = DoubleArray(N * N * 3)
    private val col = DoubleArray(N * N * 4)
    private val pcol = DoubleArray(N * N * 4)
    private val cls = ByteArray(N * N * 4)
    private val pParentNrm = DoubleArray(PG * PG * 3)
    private val pParentCol = DoubleArray(PG * PG * 4)
    private val oct = ByteArray(4)

    /** Statistics of the last build. */
    var lastFeatures = 0
        private set

    private val frostR = field.macro.palette.ice.r
    private val frostG = field.macro.palette.ice.g
    private val frostB = field.macro.palette.ice.b

    /**
     * Builds the tile (face, level, i, j) with vertex spacing [spacing] (m) into [out]; [hasParent]
     * false for the roots (no morph).
     */
    fun build(face: Int, level: Int, ti: Int, tj: Int, out: TileStaging) {
        val radius = field.radius
        val m = 1 shl level
        val den = (TileFormat.QUADS * m).toDouble()
        val spacing = CubeSphere.edge(radius, level) / TileFormat.QUADS
        val hasParent = level > 0
        // 1. directions of the 35 × 35 grid (exact dyadic face coordinates) and the feature box
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var z0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        var z1 = -Double.MAX_VALUE
        for (j in 0 until G) for (i in 0 until G) {
            val a = 2.0 * (ti * TileFormat.QUADS + i - 1) / den - 1.0
            val b = 2.0 * (tj * TileFormat.QUADS + j - 1) / den - 1.0
            CubeSphere.dir(face, a, b, dir, 0)
            val k = j * G + i
            ga[k] = a
            gb[k] = b
            grid.nx[k] = dir[0]; grid.ny[k] = dir[1]; grid.nz[k] = dir[2]
            val x = dir[0] * radius
            val y = dir[1] * radius
            val z = dir[2] * radius
            if (x < x0) x0 = x
            if (y < y0) y0 = y
            if (z < z0) z0 = z
            if (x > x1) x1 = x
            if (y > y1) y1 = y
            if (z > z1) z1 = z
        }
        // the parent's border needs one more parent spacing; the planet-scale lattice one more cell
        val lm = field.macroLevel
        val useLattice = lm >= 0 && level > lm
        var pad = if (hasParent) 2.0 * spacing + 1.0 else 1.0
        if (useLattice) pad += 2.0 * CubeSphere.edge(radius, lm) / TileFormat.QUADS
        x0 -= pad; y0 -= pad; z0 -= pad; x1 += pad; y1 += pad; z1 += pad
        // 2.–3. samples at the tile's band limit
        field.collect(x0, y0, z0, x1, y1, z1, spacing, fs)
        lastFeatures = fs.features.count + fs.bigCraters.count
        if (useLattice) {
            // the lattice points of level lm around the tile (and its parent border)
            val div = 1 shl (level - lm)
            val q = TileFormat.QUADS
            lattice.build(
                field, face, lm,
                Math.floorDiv(ti * q - 2, div), Math.floorDiv(ti * q + q + 2, div) + 1,
                Math.floorDiv(tj * q - 2, div), Math.floorDiv(tj * q + q + 2, div) + 1, fs,
            )
        }
        for (k in 0 until G * G) {
            if (useLattice) lattice.interpolate(ga[k], gb[k], macroIn)
            field.sample(grid.nx[k], grid.ny[k], grid.nz[k], spacing, fs, sample, if (useLattice) macroIn else null)
            grid.store(k, sample)
            val r = sample.floorR
            px[k] = sample.nx * r
            py[k] = sample.ny * r
            pz[k] = sample.nz * r
        }
        // 4. the parent surface at the even vertices (−2..34 step 2), at the parent's band limit
        if (hasParent) {
            val ps = spacing * 2.0
            field.collect(x0, y0, z0, x1, y1, z1, ps, fs)
            // where the parent's band limit equals the tile's (the finest levels), its samples at the
            // even vertices are the tile's own: copy them
            val same = field.exactAt(ps)
            for (pj in 0 until PG) for (pi in 0 until PG) {
                val i = pi * 2 - 2
                val j = pj * 2 - 2
                val k = pj * PG + pi
                val a = 2.0 * (ti * TileFormat.QUADS + i) / den - 1.0
                val b = 2.0 * (tj * TileFormat.QUADS + j) / den - 1.0
                if (i in -1..33 && j in -1..33) {
                    val gk = (j + 1) * G + (i + 1)
                    if (same) {
                        grid.load(gk, sample, frostR, frostG, frostB)
                        pgrid.store(k, sample)
                        continue
                    }
                    pgrid.nx[k] = grid.nx[gk]; pgrid.ny[k] = grid.ny[gk]; pgrid.nz[k] = grid.nz[gk]
                } else {
                    CubeSphere.dir(face, a, b, dir, 0)
                    pgrid.nx[k] = dir[0]; pgrid.ny[k] = dir[1]; pgrid.nz[k] = dir[2]
                }
                if (useLattice) lattice.interpolate(a, b, macroIn)
                field.sample(pgrid.nx[k], pgrid.ny[k], pgrid.nz[k], ps, fs, sample, if (useLattice) macroIn else null)
                pgrid.store(k, sample)
            }
            // parent normals and colours at the parent's own vertices (inner 17 × 17)
            for (pj in 1 until PG - 1) for (pi in 1 until PG - 1) {
                val k = pj * PG + pi
                normalAt(pgrid, PG, pi, pj, pParentNrm, k * 3)
                val slope = slopeAt(pgrid, PG, pi, pj, radius)
                val mslope = macroSlopeAt(pgrid, PG, pi, pj, radius)
                pgrid.load(k, sample, frostR, frostG, frostB)
                field.colour(sample, slope, ps, fs, colour, mslope)
                pParentCol[k * 4] = colour.r; pParentCol[k * 4 + 1] = colour.g; pParentCol[k * 4 + 2] = colour.b
                pParentCol[k * 4 + 3] = colour.water
            }
        }
        // 5.–8. normals, slopes, colours, morph targets for the 33 × 33 vertices
        var maxDelta = 0.0
        for (j in 0 until N) for (i in 0 until N) {
            val v = j * N + i
            val gk = (j + 1) * G + (i + 1)
            normalAtGrid(i + 1, j + 1, nrm, v * 3)
            val slope = slopeAt(grid, G, i + 1, j + 1, radius)
            val mslope = macroSlopeAt(grid, G, i + 1, j + 1, radius)
            grid.load(gk, sample, frostR, frostG, frostB)
            field.colour(sample, slope, spacing, fs, colour, mslope)
            col[v * 4] = colour.r; col[v * 4 + 1] = colour.g; col[v * 4 + 2] = colour.b; col[v * 4 + 3] = colour.water
            val co = v * 4
            for (c in 0 until 4) cls[co + c] = (colour.classes[c] * 255.0).roundToInt().coerceIn(0, 255).toByte()
            if (!hasParent) {
                target[v] = grid.floorR[gk]
                for (c in 0 until 3) pnrm[v * 3 + c] = nrm[v * 3 + c]
                for (c in 0 until 4) pcol[v * 4 + c] = col[v * 4 + c]
                continue
            }
            // parent vertex indices (in the 19 × 19 parent grid, where vertex i ↔ pi = i/2 + 1)
            val ie = i and 1 == 0
            val je = j and 1 == 0
            val pa: Int
            val pb: Int
            if (ie && je) {
                pa = (j / 2 + 1) * PG + (i / 2 + 1); pb = pa
            } else if (!ie && je) {
                pa = (j / 2 + 1) * PG + (i / 2 + 1); pb = pa + 1
            } else if (ie) {
                pa = (j / 2 + 1) * PG + (i / 2 + 1); pb = pa + PG
            } else {
                // quad centre: on the parent's diagonal (0,0)–(1,1)
                pa = (j / 2 + 1) * PG + (i / 2 + 1); pb = pa + PG + 1
            }
            target[v] = 0.5 * (pgrid.floorR[pa] + pgrid.floorR[pb])
            var tx = pParentNrm[pa * 3] + pParentNrm[pb * 3]
            var ty = pParentNrm[pa * 3 + 1] + pParentNrm[pb * 3 + 1]
            var tz = pParentNrm[pa * 3 + 2] + pParentNrm[pb * 3 + 2]
            val tl = sqrt(tx * tx + ty * ty + tz * tz)
            if (tl > 1e-12) {
                tx /= tl; ty /= tl; tz /= tl
            } else {
                tx = nrm[v * 3]; ty = nrm[v * 3 + 1]; tz = nrm[v * 3 + 2]
            }
            pnrm[v * 3] = tx; pnrm[v * 3 + 1] = ty; pnrm[v * 3 + 2] = tz
            for (c in 0 until 4) pcol[v * 4 + c] = 0.5 * (pParentCol[pa * 4 + c] + pParentCol[pb * 4 + c])
            maxDelta = max(maxDelta, abs(grid.floorR[gk] - target[v]))
        }
        // positions: origin at the centre vertex; skirts dropped below the edges
        val centre = (N / 2 + 1) * G + (N / 2 + 1)
        val cx = px[centre]
        val cy = py[centre]
        val cz = pz[centre]
        val skirt = max(2.0 * maxDelta, spacing) + 0.5
        var hx = 1e-3
        var hy = 1e-3
        var hz = 1e-3
        var rMin = Double.MAX_VALUE
        var rMax = 0.0
        for (j in 0 until N) for (i in 0 until N) {
            val gk = (j + 1) * G + (i + 1)
            val v = j * N + i
            hx = max(hx, abs(px[gk] - cx))
            hy = max(hy, abs(py[gk] - cy))
            hz = max(hz, abs(pz[gk] - cz))
            val r = grid.floorR[gk]
            rMin = min(rMin, min(r, target[v]))
            rMax = max(rMax, max(r, target[v]))
            val edge = i == 0 || j == 0 || i == N - 1 || j == N - 1
            if (edge) {
                val rs = r - skirt
                rMin = min(rMin, rs - maxDelta)
                hx = max(hx, abs(grid.nx[gk] * rs - cx))
                hy = max(hy, abs(grid.ny[gk] * rs - cy))
                hz = max(hz, abs(grid.nz[gk] * rs - cz))
            }
        }
        // pack
        val buf = out.buffer
        buf.clear()
        val morphScale = if (maxDelta > 1e-9) maxDelta else 1.0
        for (j in 0 until N) for (i in 0 until N) {
            val gk = (j + 1) * G + (i + 1)
            writeVertex(buf, j * N + i, px[gk] - cx, py[gk] - cy, pz[gk] - cz, hx, hy, hz, morphScale, gk)
        }
        for (k in 0 until N) {
            writeSkirt(buf, TileFormat.skirtBottom(k), k, 0, skirt, cx, cy, cz, hx, hy, hz, morphScale)
            writeSkirt(buf, TileFormat.skirtTop(k), k, N - 1, skirt, cx, cy, cz, hx, hy, hz, morphScale)
            writeSkirt(buf, TileFormat.skirtLeft(k), 0, k, skirt, cx, cy, cz, hx, hy, hz, morphScale)
            writeSkirt(buf, TileFormat.skirtRight(k), N - 1, k, skirt, cx, cy, cz, hx, hy, hz, morphScale)
        }
        buf.position(0)
        buf.limit(TileFormat.TILE_BYTES)
        out.cx = cx; out.cy = cy; out.cz = cz
        out.hx = hx.toFloat(); out.hy = hy.toFloat(); out.hz = hz.toFloat()
        out.morphScale = if (maxDelta > 1e-9) maxDelta.toFloat() else 0f
        out.rMin = rMin
        out.rMax = rMax
    }

    private fun writeVertex(
        buf: ByteBuffer, v: Int, x: Double, y: Double, z: Double, hx: Double, hy: Double, hz: Double, morphScale: Double, gk: Int,
    ) {
        val o = v * TileFormat.STRIDE
        buf.putShort(o, q16(x / hx))
        buf.putShort(o + 2, q16(y / hy))
        buf.putShort(o + 4, q16(z / hz))
        buf.putShort(o + 6, q16((grid.floorR[gk] - target[v]) / morphScale))
        TileFormat.octEncode(nrm[v * 3], nrm[v * 3 + 1], nrm[v * 3 + 2], oct, 0)
        TileFormat.octEncode(pnrm[v * 3], pnrm[v * 3 + 1], pnrm[v * 3 + 2], oct, 2)
        buf.put(o + 8, oct[0]); buf.put(o + 9, oct[1]); buf.put(o + 10, oct[2]); buf.put(o + 11, oct[3])
        for (c in 0 until 4) buf.put(o + 12 + c, u8(col[v * 4 + c]))
        for (c in 0 until 4) buf.put(o + 16 + c, u8(pcol[v * 4 + c]))
        for (c in 0 until 4) buf.put(o + 20 + c, cls[v * 4 + c])
    }

    private fun writeSkirt(
        buf: ByteBuffer, sv: Int, i: Int, j: Int, depth: Double, cx: Double, cy: Double, cz: Double,
        hx: Double, hy: Double, hz: Double, morphScale: Double,
    ) {
        val v = j * N + i
        val gk = (j + 1) * G + (i + 1)
        val r = grid.floorR[gk] - depth
        val o = sv * TileFormat.STRIDE
        buf.putShort(o, q16((grid.nx[gk] * r - cx) / hx))
        buf.putShort(o + 2, q16((grid.ny[gk] * r - cy) / hy))
        buf.putShort(o + 4, q16((grid.nz[gk] * r - cz) / hz))
        buf.putShort(o + 6, q16((grid.floorR[gk] - target[v]) / morphScale))
        // the skirt keeps its edge vertex's normal and colours (it continues the edge downwards)
        val src = v * TileFormat.STRIDE
        for (b in 8 until TileFormat.STRIDE) buf.put(o + b, buf.get(src + b))
    }

    /** Outward normal of the floor surface at grid point (i, j) of the 35 × 35 grid. */
    private fun normalAtGrid(i: Int, j: Int, out: DoubleArray, o: Int) {
        val l = j * G + i - 1
        val r = j * G + i + 1
        val d = (j - 1) * G + i
        val u = (j + 1) * G + i
        val ax = px[r] - px[l]
        val ay = py[r] - py[l]
        val az = pz[r] - pz[l]
        val bx = px[u] - px[d]
        val by = py[u] - py[d]
        val bz = pz[u] - pz[d]
        cross(ax, ay, az, bx, by, bz, out, o)
    }

    /** Outward normal of a sample grid's floor at (i, j) from its four neighbours. */
    private fun normalAt(g: SampleGrid, w: Int, i: Int, j: Int, out: DoubleArray, o: Int) {
        val l = j * w + i - 1
        val r = j * w + i + 1
        val d = (j - 1) * w + i
        val u = (j + 1) * w + i
        val ax = g.nx[r] * g.floorR[r] - g.nx[l] * g.floorR[l]
        val ay = g.ny[r] * g.floorR[r] - g.ny[l] * g.floorR[l]
        val az = g.nz[r] * g.floorR[r] - g.nz[l] * g.floorR[l]
        val bx = g.nx[u] * g.floorR[u] - g.nx[d] * g.floorR[d]
        val by = g.ny[u] * g.floorR[u] - g.ny[d] * g.floorR[d]
        val bz = g.nz[u] * g.floorR[u] - g.nz[d] * g.floorR[d]
        cross(ax, ay, az, bx, by, bz, out, o)
    }

    private fun cross(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, out: DoubleArray, o: Int) {
        // U × V points outwards (the faces are right handed)
        var x = ay * bz - az * by
        var y = az * bx - ax * bz
        var z = ax * by - ay * bx
        val l = sqrt(x * x + y * y + z * z)
        if (l > 1e-30) {
            x /= l; y /= l; z /= l
        }
        out[o] = x; out[o + 1] = y; out[o + 2] = z
    }

    /** |∇ ground| (rise over run) at (i, j) of a sample grid, from central differences of the ground radius. */
    private fun slopeAt(g: SampleGrid, w: Int, i: Int, j: Int, radius: Double): Double {
        val l = j * w + i - 1
        val r = j * w + i + 1
        val d = (j - 1) * w + i
        val u = (j + 1) * w + i
        val runU = radius * chord(g, l, r)
        val runV = radius * chord(g, d, u)
        val gu = if (runU > 1e-9) (g.groundR[r] - g.groundR[l]) / runU else 0.0
        val gv = if (runV > 1e-9) (g.groundR[u] - g.groundR[d]) / runV else 0.0
        return sqrt(gu * gu + gv * gv)
    }

    /** |∇ planet-scale datum| at (i, j) of a sample grid. */
    private fun macroSlopeAt(g: SampleGrid, w: Int, i: Int, j: Int, radius: Double): Double {
        val l = j * w + i - 1
        val r = j * w + i + 1
        val d = (j - 1) * w + i
        val u = (j + 1) * w + i
        val runU = radius * chord(g, l, r)
        val runV = radius * chord(g, d, u)
        val gu = if (runU > 1e-9) (g.macroHeight(r) - g.macroHeight(l)) / runU else 0.0
        val gv = if (runV > 1e-9) (g.macroHeight(u) - g.macroHeight(d)) / runV else 0.0
        return sqrt(gu * gu + gv * gv)
    }

    private fun chord(g: SampleGrid, a: Int, b: Int): Double {
        val dx = g.nx[a] - g.nx[b]
        val dy = g.ny[a] - g.ny[b]
        val dz = g.nz[a] - g.nz[b]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** Floor radius of vertex (i, j) of the last build, in double (tests). */
    internal fun vertexFloor(i: Int, j: Int): Double = grid.floorR[(j + 1) * G + (i + 1)]

    /** Ground radius of vertex (i, j) of the last build (tests). */
    internal fun vertexGround(i: Int, j: Int): Double = grid.groundR[(j + 1) * G + (i + 1)]

    /** Unit direction of vertex (i, j) of the last build (tests). */
    internal fun vertexDir(i: Int, j: Int, out: DoubleArray) {
        val k = (j + 1) * G + (i + 1)
        out[0] = grid.nx[k]; out[1] = grid.ny[k]; out[2] = grid.nz[k]
    }

    /** Geomorph target radius (the parent surface) of vertex (i, j) of the last build (tests). */
    internal fun vertexTarget(i: Int, j: Int): Double = target[j * N + i]

    private fun q16(v: Double): Short = (v * 32767.0).roundToInt().coerceIn(-32767, 32767).toShort()

    private fun u8(v: Double): Byte = (v * 255.0).roundToInt().coerceIn(0, 255).toByte()

    companion object {
        private const val N = TileFormat.N
        private const val G = N + 2
        private const val PG = 19
    }
}
