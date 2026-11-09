package com.starwake.core.terrain

import com.starwake.core.terrain.TerrainMath.smooth
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Result of [SurfaceField.sample] at one direction. */
class SurfaceSample {
    @JvmField var nx = 0.0
    @JvmField var ny = 1.0
    @JvmField var nz = 0.0

    /** Roblox relief above the local datum after the floor clamp (m): what paint() sees as h. */
    @JvmField var hLocal = 0.0
    @JvmField var tag = Tag.NONE

    /** Radius of the ground, of the water surface (NaN without water) and of what one stands on (m). */
    @JvmField var groundR = 0.0
    @JvmField var waterR = Double.NaN
    @JvmField var floorR = 0.0

    /** Tone inputs actually given to the style (0 for styles without `usesTone`). */
    @JvmField var wet = 0.0
    @JvmField var cold = 0.0
    @JvmField var dry = 0.0

    val macro = MacroSample()

    val isWater get() = !waterR.isNaN() && waterR > groundR
}

/** Per-thread scratch of a [SurfaceField]: domains, feature lists, style and macro scratch. */
class FieldScratch {
    val domain = Domain()
    val style = StyleScratch()
    val relief = Relief()
    val macro = MacroScratch()

    /** Style (Roblox) features of the current region: a tile's box, or a point query's box. */
    val features = FeatureList(4096)

    /** Planet-scale craters of the current region. */
    val bigCraters = FeatureList(2048)

    /** Where [features] / [bigCraters] were collected for (a query reuses them while inside). */
    @JvmField var boxValid = false
    @JvmField var bx0 = 0.0
    @JvmField var by0 = 0.0
    @JvmField var bz0 = 0.0
    @JvmField var bx1 = 0.0
    @JvmField var by1 = 0.0
    @JvmField var bz1 = 0.0
    @JvmField var boxMinSize = 0.0

    val mean = MeanColourTable.Out()

    /** Query cache of the planet-scale lattice cell (see [SurfaceField.macroLevel]). */
    val cell = MacroLattice(4)
    @JvmField var cellFace = -1
    @JvmField var cellK = Int.MIN_VALUE
    @JvmField var cellJ = Int.MIN_VALUE
    val cellAb = DoubleArray(2)
    val macroTmp = MacroSample()
}

/** Output of [SurfaceField.colour]: sRGB colour 0..1, water share, detail class weights. */
class VertexColour {
    @JvmField var r = 0.0
    @JvmField var g = 0.0
    @JvmField var b = 0.0
    @JvmField var water = 0.0
    @JvmField var material = 0
    val classes = DoubleArray(4)
}

/**
 * The height function of one body (§9.4): the Roblox style on the sphere domain over the
 * planet-scale layer. Pure and thread-safe; every caller passes its own [FieldScratch].
 *
 * ```
 * P = n·R;  macro = MacroField(n)                     -- datum height, water datum, wet/cold/dry, tint
 * h = max(style.relief(D(P), features, o, wet, cold, dry), style.floor)
 * rGround = R + Lift + macro.height + h
 * rWater  = R + Lift + macro.waterDatum + W           -- styles with water
 * rFloor  = max(rGround, rWater)                      -- "what one stands on" (floorAt)
 * ```
 *
 * Tiles call [sample] with their vertex spacing (band limit) after [collect] for their box;
 * physics and every query call it with spacing 0 (exact): the finest tiles are exact too, so the
 * picture and the queries meet at the vertices.
 */
class SurfaceField(
    val radius: Double,
    val style: TerrainStyle,
    val seed: Int,
    val macro: MacroField,
    val lift: Double = LIFT,
) {
    val offsets = SeedOffsets(seed)
    val salt = offsets.salt
    private val waterLevel = style.water
    private val hasWater = style.hasWater
    private val usesTone = style.usesTone

    /** Material colours in sRGB (as Roblox stores them) and their detail classes. */
    private val matR = DoubleArray(style.materials.size) { style.materials[it].colour.r }
    private val matG = DoubleArray(style.materials.size) { style.materials[it].colour.g }
    private val matB = DoubleArray(style.materials.size) { style.materials[it].colour.b }
    private val matClass = IntArray(style.materials.size) { style.materials[it].material.detail }

    /** Lowest and highest radius any ground or water can have (bounds, horizon culling). */
    val minRadius = radius + lift + macro.heightMin + min(style.floor, style.reliefMin) - 2.0
    val maxRadius = radius + lift + macro.heightMax + max(style.reliefMax, if (hasWater) waterLevel else 0.0) + 2.0

    /** The paint pattern scale of the style (m): coarse tiles show the mean colour beyond it. */
    val patchScale: Double = PATCH_SCALE[style.family] ?: 200.0

    /**
     * Level whose vertex grid carries the planet-scale layer for finer tiles and for every query
     * (−1: evaluated everywhere). The layer varies over ≥ 700 m, so on the finest tiles it is
     * interpolated bilinearly (in face coordinates) from this lattice instead of being evaluated
     * per vertex (a third of a fine tile's cost); queries use the identical lattice, so picture and
     * physics still agree at the vertices.
     */
    @Volatile var macroLevel = -1

    /** Mean colours (filled once by [buildMeanColours], usually on a worker). */
    @Volatile var meanColours: MeanColourTable? = null
        private set

    fun buildMeanColours(): MeanColourTable {
        val t = meanColours ?: MeanColourTable.build(this)
        meanColours = t
        return t
    }

    /**
     * Collects the style and planet-scale features for the box of a region of the reference
     * sphere (body space, metres) at band limit [spacing] (features smaller than ~1.5 spacings are skipped).
     */
    fun collect(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, spacing: Double, s: FieldScratch) {
        val minSize = if (spacing > 0.0) spacing * 0.5 else 0.0
        Features.collectSphere(style.layers, x0, y0, z0, x1, y1, z1, radius, salt, s.features, minSize)
        Features.collectSphere(macro.bigCraterLayers, x0, y0, z0, x1, y1, z1, radius, macro.bigSalt, s.bigCraters, minSize)
        s.boxValid = true
        s.bx0 = x0; s.by0 = y0; s.bz0 = z0; s.bx1 = x1; s.by1 = y1; s.bz1 = z1
        s.boxMinSize = minSize
    }

    /** Makes sure the scratch holds the exact features around the point (a 2 m box), for queries. */
    fun collectPoint(nx: Double, ny: Double, nz: Double, s: FieldScratch) {
        val px = nx * radius
        val py = ny * radius
        val pz = nz * radius
        if (s.boxValid && s.boxMinSize == 0.0 &&
            px > s.bx0 && px < s.bx1 && py > s.by0 && py < s.by1 && pz > s.bz0 && pz < s.bz1
        ) {
            return
        }
        // a few metres around the point: the next queries (±2 m differences, the flight path a frame
        // later) usually stay inside
        val m = QUERY_BOX
        collect(px - m, py - m, pz - m, px + m, py + m, pz + m, 0.0, s)
    }

    /**
     * The surface at unit direction (nx, ny, nz) with band limit [spacing] (0 = exact), using the
     * features in [s] (see [collect] / [collectPoint]).
     */
    fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: FieldScratch, out: SurfaceSample, macroIn: MacroSample? = null) {
        out.nx = nx; out.ny = ny; out.nz = nz
        val m = out.macro
        if (macroIn != null) m.copy(macroIn) else macro.sample(nx, ny, nz, spacing, s.macro, s.bigCraters, m)
        val wet: Double
        val cold: Double
        val dry: Double
        if (usesTone) {
            wet = m.wet; cold = m.cold; dry = m.dry
        } else {
            wet = 0.0; cold = 0.0; dry = 0.0
        }
        out.wet = wet; out.cold = cold; out.dry = dry
        val d = s.domain
        d.setSphere(nx, ny, nz, radius, spacing)
        val rel = s.relief
        style.relief(d, s.features, s.style, offsets, wet, cold, dry, rel)
        var h = max(rel.h, style.floor)
        if (hasWater && m.lakeBlock > 0.0 && rel.tag != Tag.SEA) {
            // no tilted lakes on steep planet-scale slopes: fill them like Roblox's dry rule
            val fill = waterLevel + 1.5
            if (h < fill) h += (fill - h) * m.lakeBlock
        }
        out.hLocal = h
        out.tag = rel.tag
        out.groundR = radius + lift + m.height + h
        if (hasWater) {
            out.waterR = radius + lift + m.waterDatum + waterLevel
            out.floorR = max(out.groundR, out.waterR)
        } else {
            out.waterR = Double.NaN
            out.floorR = out.groundR
        }
    }

    /** Exact sample at a direction (collects the features around it first). */
    fun sampleAt(nx: Double, ny: Double, nz: Double, s: FieldScratch, out: SurfaceSample) {
        collectPoint(nx, ny, nz, s)
        val lm = macroLevel
        if (lm < 0) {
            sample(nx, ny, nz, 0.0, s, out)
            return
        }
        // the planet-scale layer from the lattice cell around the point (cached per cell)
        val f = CubeSphere.faceOf(nx, ny, nz)
        CubeSphere.coords(f, nx, ny, nz, s.cellAb, 0)
        val inv = MacroLattice.cellsPerUnit(lm)
        val k = kotlin.math.floor((s.cellAb[0] + 1.0) * inv).toInt()
        val j = kotlin.math.floor((s.cellAb[1] + 1.0) * inv).toInt()
        if (f != s.cellFace || k != s.cellK || j != s.cellJ) {
            s.cell.build(this, f, lm, k, k + 1, j, j + 1, s)
            s.cellFace = f; s.cellK = k; s.cellJ = j
        }
        s.cell.interpolate(s.cellAb[0], s.cellAb[1], s.macroTmp)
        sample(nx, ny, nz, 0.0, s, out, s.macroTmp)
    }

    /** Smallest outer radius of any feature (style and planet-scale layers). */
    private val minOuter: Double = run {
        var m = Double.MAX_VALUE
        style.layers?.forEach { m = min(m, it.rmin * it.reach) }
        macro.bigCraterLayers?.forEach { m = min(m, it.rmin * it.reach) }
        m
    }

    /**
     * True when sampling at [spacing] gives exactly the exact surface: every noise term resolved
     * (the smallest Roblox wavelength is 30 m; the planet-scale layer is far coarser) and no
     * feature skipped.
     */
    fun exactAt(spacing: Double): Boolean = spacing <= 0.0 || (4.0 * spacing <= 30.0 && 0.75 * spacing < minOuter)

    /** Slope boost for coarse tiles (Roblox's FieldSlope 1.6 on its coarse mesh, §9.5). */
    fun slopeBoost(spacing: Double) = 1.0 + 0.6 * smooth(8.0, 64.0, spacing)

    /**
     * The colour of a sampled point (§9.5): paint(h, slope, tag) → the style colour, tinted (styles
     * without tone), frost cover, water over the bed; at coarse spacing blended towards the mean
     * colour of the style for the same slow inputs. [slope] = |∇ ground| (rise over run) as the
     * tile or query measured it, [macroSlope] the slope of the planet-scale datum alone (the mean
     * table adds it to the fine-scale slopes). Uses the scratch domain; writes [VertexColour] (sRGB).
     */
    fun colour(p: SurfaceSample, slope: Double, spacing: Double, s: FieldScratch, out: VertexColour, macroSlope: Double = slope) {
        val d = s.domain
        d.setSphere(p.nx, p.ny, p.nz, radius, spacing)
        val packed = style.paint(p.hLocal, slope * slopeBoost(spacing), p.tag, d, p.wet, p.cold, p.dry)
        val top = TerrainStyle.top(packed)
        out.material = top
        val m = p.macro
        var r = matR[top]
        var g = matG[top]
        var b = matB[top]
        if (!usesTone) {
            r = min(1.0, r * m.tintR)
            g = min(1.0, g * m.tintG)
            b = min(1.0, b * m.tintB)
        }
        for (i in 0 until 4) out.classes[i] = 0.0
        out.classes[matClass[top]] = 1.0
        val k = smooth(patchScale / 8.0, patchScale / 2.0, spacing)
        val table = meanColours
        var water = 0.0
        if (k > 0.0 && table != null) {
            table.lookup(p.wet, p.cold, p.dry, macroSlope, s.mean)
            // the table holds linear means of the untinted paint colours and the water share
            val c = s.mean
            // Roblox's FieldShade 0.9: far colours as the lit voxels look
            var mr = sqrt(c.r) * FIELD_SHADE
            var mg = sqrt(c.g) * FIELD_SHADE
            var mb = sqrt(c.b) * FIELD_SHADE
            if (!usesTone) {
                mr = min(1.0, mr * m.tintR); mg = min(1.0, mg * m.tintG); mb = min(1.0, mb * m.tintB)
            }
            r += (mr - r) * k
            g += (mg - g) * k
            b += (mb - b) * k
            water = c.water * k
        }
        // frost / polar caps
        if (m.frost > 0.0) {
            r += (m.frostR - r) * m.frost
            g += (m.frostG - g) * m.frost
            b += (m.frostB - b) * m.frost
        }
        // water over the bed (this point), or the unresolved water share of the mean
        val look = style.waterLook
        if (look != null) {
            val sea = m.seaLook
            var cover = 0.0
            if (p.isWater) {
                // lakes show their bed through Roblox's transparency, fading with depth; the sea is opaque
                val lake = 1.0 - look.transparency * exp(-(p.waterR - p.groundR) / 1.5)
                cover = lake + (1.0 - lake) * sea
            }
            cover = max(cover, water)
            if (cover > 0.0) {
                val wr = look.colour.r + (m.seaR - look.colour.r) * sea
                val wg = look.colour.g + (m.seaG - look.colour.g) * sea
                val wb = look.colour.b + (m.seaB - look.colour.b) * sea
                r += (wr - r) * cover
                g += (wg - g) * cover
                b += (wb - b) * cover
                out.water = cover
            } else {
                out.water = 0.0
            }
        } else {
            out.water = 0.0
        }
        out.r = r
        out.g = g
        out.b = b
    }

    /** Detail class of a material index. */
    fun materialClass(material: Int) = matClass[material]

    /** Name of a material index. */
    fun materialName(material: Int) = style.materials[material].name

    /** The raw sRGB colour of a material (tests, the mean table). */
    fun materialColour(material: Int, out: DoubleArray) {
        out[0] = matR[material]; out[1] = matG[material]; out[2] = matB[material]
    }

    companion object {
        /** Roblox `Lift`: the datum sits this far above the reference sphere (kept so heights match). */
        const val LIFT = 4.0

        /** Roblox `FieldShade`: far-field colours ×0.9. */
        const val FIELD_SHADE = 0.9

        /** Half edge of the feature box a point query collects (m). */
        const val QUERY_BOX = 24.0

        /** Dominant scale of each style's paint pattern (m). */
        val PATCH_SCALE = mapOf(
            "temperate" to 260.0, "desert" to 180.0, "titan" to 150.0, "cratered" to 240.0, "volcanic" to 320.0,
            "icecrack" to 400.0, "grooved" to 120.0, "nitrogen" to 220.0, "ridges" to 200.0, "ocean" to 120.0, "floes" to 140.0,
        )

        /** Builds the style, planet-scale layer and field of a body. */
        fun create(radius: Double, style: TerrainStyle, seed: Int, bodyClass: String): SurfaceField =
            SurfaceField(radius, style, seed, MacroField.forStyle(style, radius, seed, bodyClass))
    }
}

/**
 * The mean colour of a style for its slow inputs (§9.5, the colour half of "the same function at
 * every scale"): linear mean of the paint colour and the share of water over many fine-scale
 * samples (plane mode, exact), per (wet, cold, dry, slope offset) cell. Coarse tiles blend towards
 * it, so the orbit view shows the area average of what the ground shows.
 */
class MeanColourTable private constructor(
    private val nw: Int,
    private val nc: Int,
    private val nd: Int,
    private val ns: Int,
    private val data: FloatArray,
) {
    class Out {
        @JvmField var r = 0.0
        @JvmField var g = 0.0
        @JvmField var b = 0.0
        @JvmField var water = 0.0
    }

    private fun idx(w: Int, c: Int, d: Int, s: Int) = (((w * nc + c) * nd + d) * ns + s) * 4

    /** Quadrilinear lookup; [slope] is the extra (planet-scale) slope, axis 0..[SLOPE_MAX]. */
    fun lookup(wet: Double, cold: Double, dry: Double, slope: Double, out: Out) {
        val fw = axis(wet, nw)
        val fc = axis(cold, nc)
        val fd = axis(dry, nd)
        val fs = axis(min(slope, SLOPE_MAX) / SLOPE_MAX, ns)
        val w0 = fw.toInt().coerceAtMost(nw - 1)
        val c0 = fc.toInt().coerceAtMost(nc - 1)
        val d0 = fd.toInt().coerceAtMost(nd - 1)
        val s0 = fs.toInt().coerceAtMost(ns - 1)
        val tw = fw - w0
        val tc = fc - c0
        val td = fd - d0
        val ts = fs - s0
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var a = 0.0
        for (k in 0 until 16) {
            val iw = min(w0 + (k and 1), nw - 1)
            val ic = min(c0 + ((k shr 1) and 1), nc - 1)
            val id = min(d0 + ((k shr 2) and 1), nd - 1)
            val iS = min(s0 + ((k shr 3) and 1), ns - 1)
            val wt = (if (k and 1 != 0) tw else 1 - tw) * (if (k and 2 != 0) tc else 1 - tc) *
                (if (k and 4 != 0) td else 1 - td) * (if (k and 8 != 0) ts else 1 - ts)
            if (wt == 0.0) continue
            val o = idx(iw, ic, id, iS)
            r += data[o] * wt
            g += data[o + 1] * wt
            b += data[o + 2] * wt
            a += data[o + 3] * wt
        }
        out.r = r; out.g = g; out.b = b; out.water = a
    }

    private fun axis(v: Double, n: Int): Double = if (n <= 1) 0.0 else TerrainMath.clamp(v, 0.0, 1.0) * (n - 1)

    companion object {
        const val SLOPE_MAX = 1.2

        fun build(field: SurfaceField, samples: Int = 0): MeanColourTable {
            val style = field.style
            val tone = style.usesTone
            val nw = if (tone) 5 else 1
            val nc = if (tone) 5 else 1
            val nd = if (tone) 5 else 1
            val ns = 4
            val perCell = if (samples > 0) samples else if (tone) 112 else 1200
            val data = FloatArray(nw * nc * nd * ns * 4)
            val d = Domain()
            val list = FeatureList(512)
            val ss = StyleScratch()
            val rel = Relief()
            val o = field.offsets
            val col = DoubleArray(3)
            val hs = DoubleArray(5)
            val tags = IntArray(1)
            for (iw in 0 until nw) for (ic in 0 until nc) for (id in 0 until nd) for (iS in 0 until ns) {
                val wet = if (nw > 1) iw / (nw - 1.0) else 0.0
                val cold = if (nc > 1) ic / (nc - 1.0) else 0.0
                val dry = if (nd > 1) id / (nd - 1.0) else 0.0
                val base = iS / (ns - 1.0) * SLOPE_MAX
                var sr = 0.0
                var sg = 0.0
                var sb = 0.0
                var land = 0
                var water = 0
                for (k in 0 until perCell) {
                    // quasi-random points in ±40 km (R2 sequence), a different set per cell
                    val q = k + 977 * (iS + ns * (id + nd * (ic + nc * iw)))
                    val u = ((q * 0.7548776662466927) % 1.0 - 0.5) * 80_000.0
                    val v = ((q * 0.5698402909980532) % 1.0 - 0.5) * 80_000.0
                    val phi = ((q * 0.6180339887498949) % 1.0) * 2.0 * Math.PI
                    Features.collectPlane(style.layers, u - 6.0, v - 6.0, u + 6.0, v + 6.0, field.salt, list)
                    for (j in 0 until 5) {
                        val du = when (j) { 1 -> 4.0; 2 -> -4.0; else -> 0.0 }
                        val dv = when (j) { 3 -> 4.0; 4 -> -4.0; else -> 0.0 }
                        d.setPlane(u + du, v + dv)
                        style.relief(d, list, ss, o, wet, cold, dry, rel)
                        hs[j] = max(rel.h, style.floor)
                        if (j == 0) tags[0] = rel.tag
                    }
                    val gx = (hs[1] - hs[2]) / 8.0 + base * kotlin.math.cos(phi)
                    val gz = (hs[3] - hs[4]) / 8.0 + base * kotlin.math.sin(phi)
                    val slope = sqrt(gx * gx + gz * gz)
                    val h = hs[0]
                    if (style.hasWater && h < style.water) {
                        water++
                        continue
                    }
                    d.setPlane(u, v)
                    val top = TerrainStyle.top(style.paint(h, slope, tags[0], d, wet, cold, dry))
                    field.materialColour(top, col)
                    sr += col[0] * col[0]
                    sg += col[1] * col[1]
                    sb += col[2] * col[2]
                    land++
                }
                val oo = ((((iw * nc + ic) * nd + id) * ns + iS)) * 4
                if (land > 0) {
                    data[oo] = (sr / land).toFloat()
                    data[oo + 1] = (sg / land).toFloat()
                    data[oo + 2] = (sb / land).toFloat()
                } else {
                    // all water: the bed is invisible, any colour will do; use the first material
                    field.materialColour(0, col)
                    data[oo] = (col[0] * col[0]).toFloat()
                    data[oo + 1] = (col[1] * col[1]).toFloat()
                    data[oo + 2] = (col[2] * col[2]).toFloat()
                }
                data[oo + 3] = (water.toDouble() / perCell).toFloat()
            }
            return MeanColourTable(nw, nc, nd, ns, data)
        }
    }
}
