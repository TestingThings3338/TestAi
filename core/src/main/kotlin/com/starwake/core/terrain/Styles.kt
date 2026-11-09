package com.starwake.core.terrain

import com.starwake.core.terrain.TerrainMath.dune
import com.starwake.core.terrain.TerrainMath.smooth
import com.starwake.core.terrain.TerrainMath.terrace
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Roblox terrain materials the styles use, and the detail class each maps to (§9.5 step 5). */
enum class RobloxMaterial(val detail: Int) {
    Grass(DetailClass.SOIL), LeafyGrass(DetailClass.SOIL), Ground(DetailClass.SOIL), Mud(DetailClass.SOIL),
    Rock(DetailClass.ROCK), Slate(DetailClass.ROCK), Basalt(DetailClass.ROCK), CrackedLava(DetailClass.ROCK),
    Concrete(DetailClass.ROCK), Limestone(DetailClass.ROCK), Sandstone(DetailClass.ROCK),
    Sand(DetailClass.GRANULAR), Salt(DetailClass.GRANULAR),
    Snow(DetailClass.ICE), Ice(DetailClass.ICE), Glacier(DetailClass.ICE),
}

/** The four detail texture classes. */
object DetailClass {
    const val SOIL = 0
    const val ROCK = 1
    const val GRANULAR = 2
    const val ICE = 3
}

/** One entry of a style's material list: name, Roblox base material, colour. */
class StyleMaterial(val name: String, val material: RobloxMaterial, val colour: Rgb)

/** Roblox `waterLook` → `Terrain.Water*`. */
class WaterLook(val colour: Rgb, val transparency: Double, val reflectance: Double, val wave: Double, val speed: Double)

/** Output of [TerrainStyle.relief]: height above the datum (m) and tag. */
class Relief {
    @JvmField var h = 0.0
    @JvmField var tag = Tag.NONE
}

/** Scratch a style needs for one evaluation (one per thread). */
class StyleScratch {
    val feature = FeatureResult()
}

/**
 * A terrain style (§2.2.6): relief(u, v) → height, tag and paint(h, slope, tag) → top, under,
 * transcribed literally from ShipSurfaceTerrain.lua against [Domain]. Directional patterns carry
 * the domain's polar weight and fade to their mean under the band limit; everything else is the
 * Lua expression with `noise/fbm/ridged` replaced by the domain's band-limited forms (identical at
 * spacing 0 in plane mode).
 *
 * Immutable after construction: shared by the game thread and the workers.
 */
abstract class TerrainStyle(
    /** "temperate", "desert", … (the Lua `key` before chooseStyle renames it). */
    val family: String,
    val materials: Array<StyleMaterial>,
    val layers: Array<FeatureLayer>?,
    /** Water level W above the datum (m), NaN = dry. */
    val water: Double,
    /** Lowest relief allowed (heights below are lifted to it). */
    val floor: Double,
    val usesTone: Boolean,
    val waterLook: WaterLook?,
) {
    /** chooseStyle's key: the Sol body name, or the title / class. */
    var key: String = family
        internal set

    val hasWater get() = !water.isNaN()

    /** Upper bound of the relief above the datum (m), for horizon culling and bounds. */
    abstract val reliefMax: Double

    /** Lower bound (m), at most [floor]. */
    open val reliefMin: Double get() = floor - 1.0

    /** Material index by name (construction only). */
    fun index(name: String): Int {
        for (i in materials.indices) if (materials[i].name == name) return i
        throw IllegalArgumentException("$family has no material $name")
    }

    abstract fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief)

    /** Returns `top or (under shl 8)` (material indices). */
    abstract fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int

    protected fun pack(top: Int, under: Int) = top or (under shl 8)

    /** A dune profile that averages out under the band limit. */
    protected fun duneTerm(d: Domain, w: Double, lambda: Double): Double {
        val f = d.fade(lambda)
        if (f <= 0.0) return TerrainMath.DUNE_MEAN
        return TerrainMath.DUNE_MEAN + (dune(w) - TerrainMath.DUNE_MEAN) * f
    }

    protected fun features(d: Domain, list: FeatureList, s: StyleScratch): FeatureResult {
        val f = s.feature
        val l = layers
        if (l == null || list.count == 0) {
            f.height = 0.0
            f.tag = Tag.NONE
        } else {
            Features.evaluate(list, l, d, f)
        }
        return f
    }

    companion object {
        fun top(packed: Int) = packed and 0xFF
        fun under(packed: Int) = (packed shr 8) and 0xFF
    }
}

// ============================================================================ styles

/** Temperate: hills, mountains with snow, lakes and beaches; sea, ice and desert from the tone (Earth). */
class TemperateStyle(c: Map<String, Rgb>, level: Double = 8.0) : TerrainStyle(
    "temperate",
    arrayOf(
        StyleMaterial("grass", RobloxMaterial.Grass, c.getValue("grass")),
        StyleMaterial("lush", RobloxMaterial.LeafyGrass, c.getValue("lush")),
        StyleMaterial("dirt", RobloxMaterial.Ground, c.getValue("dirt")),
        StyleMaterial("sand", RobloxMaterial.Sand, c.getValue("sand")),
        StyleMaterial("mud", RobloxMaterial.Mud, c.getValue("mud")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("cliff", RobloxMaterial.Slate, c.getValue("cliff")),
        StyleMaterial("snow", RobloxMaterial.Snow, c.getValue("snow")),
        StyleMaterial("dry", RobloxMaterial.Grass, c.getValue("dry")),
    ),
    null, level, -24.0, true,
    WaterLook(Rgb.of(26, 94, 108), 0.72, 0.45, 0.12, 8.0),
) {
    private val W = level
    private val GRASS = index("grass")
    private val LUSH = index("lush")
    private val DIRT = index("dirt")
    private val SAND = index("sand")
    private val MUD = index("mud")
    private val ROCK = index("rock")
    private val CLIFF = index("cliff")
    private val SNOW = index("snow")
    private val DRY = index("dry")
    override val reliefMax = W + 14.0 + 1.95 * 3 * 22 + 1.95 * 3 * 18 + 300.0 + 3.0 + 12.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val hills = d.fbm(900.0, o.x, o.y, 0.37, 5) * 3
        val roll = d.fbm(2600.0, o.y, o.x, 1.9, 3) * 3
        val mask = smooth(0.2, 0.5, d.fbm(5200.0, o.y, o.x, 4.1, 3) * 3)
        var peaks = 0.0
        if (mask > 0) peaks = d.ridged(1700.0, o.x, o.y, 7.7, 4) * mask
        // ice sheets flatter
        val calm = 1 - 0.6 * cold
        var h = W + 14 + (hills * 22 + roll * 18 + peaks * 300) * calm + d.n(48.0, 0.0, 0.0, 9.1) * 3
        var tag = Tag.NONE
        // no open water in the ice
        if (cold > 0.4) h = max(h, W + 1 + cold * 6)
        if (dry > 0.35) {
            // dry: hardly any lakes; dunes only in real desert
            h = max(h, W + 1.5)
            val w = d.phase(0.83, 0.56, 190.0) + d.n(650.0, 0.0, 0.0, 2.8) * 0.9
            val dd = duneTerm(d, w, 190.0) * 12 * smooth(0.6, 0.9, dry) * d.dirWeight
            h += dd
            if (dd > 3) tag = Tag.DUNE
        }
        if (wet > 0.02) {
            // coast: the image says sea, the noise draws the line
            val coast = wet + d.n(700.0, o.x, o.y, 2.2) * 0.2 + d.n(170.0, 0.0, 0.0, 3.3) * 0.06
            val share = smooth(0.58, 0.42, coast)
            if (share < 1) {
                val bed = W - 7 - 24 * smooth(0.62, 0.88, coast) + d.n(260.0, 0.0, 0.0, 6.1)
                h = bed + (h - bed) * share
                if (share < 0.5) tag = Tag.SEA
            }
        }
        out.h = h
        out.tag = tag
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (h < W - 1) {
            return pack(if (tag != Tag.SEA && d.n(90.0, 0.0, 0.0, 3.3) > 0.12) MUD else SAND, SAND)
        } else if (h < W + 2.5 + d.n(60.0, 0.0, 0.0, 5.5) * 4 && cold < 0.5) {
            return pack(SAND, SAND)
        } else if (slope > 1.05) {
            return pack(CLIFF, ROCK)
        } else if (h > 215 - cold * 260 + d.n(150.0, 0.0, 0.0, 8.8) * 50 && slope < 0.9) {
            return pack(SNOW, ROCK)
        } else if (slope > 0.62 + dry * 0.4) {
            return pack(if (d.n(40.0, 0.0, 0.0, 2.2) > 0) ROCK else DIRT, ROCK)
        }
        if (dry > 0.3) {
            // steppe to desert: dry grass, sand only where it is really dry, bare earth in patches
            val q = dry + d.n(160.0, 0.0, 0.0, 4.7) * 0.35
            if (tag == Tag.DUNE || q > 0.9) {
                return pack(SAND, SAND)
            } else if (q > 0.4) {
                val bare = d.n(150.0, 0.0, 0.0, 6.3) + d.n(36.0, 0.0, 0.0, 2.9) * 0.3
                return pack(if (bare > 0.42) SAND else DRY, DIRT)
            }
            return pack(GRASS, DIRT)
        }
        val p = d.n(260.0, 0.0, 0.0, 6.6) + d.n(70.0, 0.0, 0.0, 1.4) * 0.5
        if (p > 0.22) {
            return pack(LUSH, DIRT)
        } else if (p < -0.3) {
            return pack(DRY, DIRT)
        } else if (p < -0.2 && d.n(30.0, 0.0, 0.0, 4.4) > 0.2) {
            return pack(DIRT, DIRT)
        }
        return pack(GRASS, DIRT)
    }
}

/** Desert: plains, dune fields, layered mesas, craters (Mars, CANYON MESAS, …). */
class DesertStyle(
    c: Map<String, Rgb>,
    val dunes: Double = 1.0,
    val mesas: Double = 1.0,
    val canyons: Double = 0.0,
    val craters: Double = 1.0,
) : TerrainStyle(
    "desert",
    arrayOf(
        StyleMaterial("dust", RobloxMaterial.Ground, c.getValue("dust")),
        StyleMaterial("sand", RobloxMaterial.Sand, c.getValue("sand")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("dark", RobloxMaterial.Basalt, c.getValue("dark")),
        StyleMaterial("layered", RobloxMaterial.Sandstone, c.getValue("layered")),
        StyleMaterial("pale", RobloxMaterial.Salt, c.getValue("pale")),
    ),
    arrayOf(
        FeatureLayer(FeatureLayer.CRATER, 900.0, 0.3 * craters, 60.0, 320.0, depth = 0.16, rim = 0.05, flat = 0.35),
        FeatureLayer(FeatureLayer.CRATER, 220.0, 0.35 * craters, 10.0, 55.0, depth = 0.22, rim = 0.07),
        FeatureLayer(FeatureLayer.BOULDER, 48.0, 0.2, 3.0, 8.0),
    ),
    Double.NaN, 0.0, false, null,
) {
    private val DUST = index("dust")
    private val SAND = index("sand")
    private val ROCK = index("rock")
    private val DARK = index("dark")
    private val LAYERED = index("layered")
    private val PALE = index("pale")
    override val reliefMax = 14.0 + 1.95 * 72 + 150.0 * mesas + 24.0 * dunes + 320 * 0.05 + 8.0 + 4.0
    override val reliefMin = -320 * 0.16 - 70 * canyons - 10.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val plains = d.fbm(1100.0, o.x, o.y, 0.37, 4) * 3 * 24
        val m = d.fbm(2600.0, o.y, o.x, 3.9, 3) * 3
        val mesa = terrace(smooth(0.15, 0.9, m), 3.0, 0.08) * 150 * mesas
        var h = 14 + plains + mesa
        var tag = Tag.NONE
        if (canyons > 0) {
            val cut = smooth(0.55, 0.85, d.ridged(1500.0, o.x, o.y, 5.3, 2)) * 70 * canyons
            h -= cut
            if (cut > 8) tag = Tag.CANYON
        }
        val field = smooth(0.0, 0.5, d.fbm(1900.0, o.x * 0.7, o.y * 0.7, 6.2, 3) * 3) * (1 - smooth(0.05, 0.3, m)) * dunes
        if (field > 0.01) {
            val w = d.phase(0.83, 0.56, 210.0) + d.n(700.0, 0.0, 0.0, 2.8) * 0.9
            val dd = duneTerm(d, w, 210.0) * (14 + 10 * d.n(400.0, 0.0, 0.0, 5.1)) * field * d.dirWeight
            h += dd
            if (dd > 4) tag = Tag.DUNE
        }
        val f = features(d, list, s)
        h += f.height + d.n(36.0, 0.0, 0.0, 9.4) * 2
        if (mesa > 10 && tag == Tag.NONE) tag = Tag.MESA
        out.h = h
        out.tag = if (f.tag != Tag.NONE) f.tag else tag
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.BOULDER) return pack(DARK, ROCK)
        if (slope > 0.85) {
            // layering on steep faces
            val band = sin(h * 0.21 + d.n(80.0, 0.0, 0.0, 1.1) * 2)
            return pack(if (band > 0.2) LAYERED else ROCK, ROCK)
        }
        if (tag == Tag.CRATER || tag == Tag.CANYON) return pack(DUST, ROCK)
        if (tag == Tag.RIM) return pack(PALE, ROCK)
        if (tag == Tag.DUNE) return pack(SAND, SAND)
        val q = d.n(180.0, 0.0, 0.0, 7.7)
        if (q > 0.18) return pack(SAND, DUST)
        if (q < -0.25) return pack(ROCK, ROCK)
        return pack(DUST, ROCK)
    }
}

/** Titan: long dunes, dark methane lakes, ice as bedrock. */
class TitanStyle(c: Map<String, Rgb>) : TerrainStyle(
    "titan",
    arrayOf(
        StyleMaterial("sand", RobloxMaterial.Sand, c.getValue("sand")),
        StyleMaterial("plain", RobloxMaterial.Ground, c.getValue("plain")),
        StyleMaterial("mud", RobloxMaterial.Mud, c.getValue("mud")),
        StyleMaterial("ice", RobloxMaterial.Glacier, c.getValue("ice")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("dune", RobloxMaterial.Sand, c.getValue("dune")),
    ),
    null, 8.0, -24.0, false,
    WaterLook(Rgb.of(40, 30, 20), 0.18, 0.85, 0.04, 4.0),
) {
    private val W = 8.0
    private val SAND = index("sand")
    private val PLAIN = index("plain")
    private val MUD = index("mud")
    private val ICE = index("ice")
    private val ROCK = index("rock")
    private val DUNE = index("dune")
    override val reliefMax = 14.0 + 1.95 * 3 * 18 + 30.0 + 90.0 + 1.5

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val basin = d.fbm(1500.0, o.x, o.y, 0.37, 4) * 3
        val field = smooth(-0.2, 0.3, basin)
        val w = d.phase(0.97, 0.24, 300.0) + d.n(1200.0, 0.0, 0.0, 4.4) * 0.5
        val dd = duneTerm(d, w, 300.0) * (20 + 10 * d.n(600.0, 0.0, 0.0, 6.6)) * field * d.dirWeight
        val rise = smooth(0.25, 0.6, d.fbm(4000.0, o.y, o.x, 2.2, 2) * 3)
        var hills = 0.0
        if (rise > 0) hills = d.ridged(1300.0, o.x, o.y, 8.8, 3) * rise * 90
        out.h = 14 + basin * 18 + dd + hills + d.n(40.0, 0.0, 0.0, 9.9) * 1.5
        out.tag = if (dd > 5) Tag.DUNE else if (hills > 8) Tag.ICE else Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (h < W - 0.5) return pack(MUD, MUD)
        if (h < W + 1.5) return pack(PLAIN, MUD)
        if (slope > 1) return pack(ROCK, ROCK)
        if (tag == Tag.ICE || slope > 0.75) return pack(ICE, ROCK)
        if (tag == Tag.DUNE) return pack(DUNE, SAND)
        return pack(if (d.n(150.0, 0.0, 0.0, 3.1) > 0.1) SAND else PLAIN, SAND)
    }
}

/** Crater fields without air (Luna, Mercury, Callisto). */
class CrateredStyle(
    c: Map<String, Rgb>,
    val dense: Double = 1.0,
    val boulders: Double = 1.0,
    val scarps: Double = 0.0,
    val basins: Double = 0.0,
) : TerrainStyle(
    "cratered",
    arrayOf(
        StyleMaterial("regolith", RobloxMaterial.Sand, c.getValue("regolith")),
        // dust: the same surface as the regolith, a little darker (soft patches, not camouflage)
        StyleMaterial("dust", RobloxMaterial.Sand, c.getValue("dust")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("bright", RobloxMaterial.Concrete, c.getValue("bright")),
        StyleMaterial("mare", RobloxMaterial.Basalt, c.getValue("mare")),
    ),
    craterLayers(dense, boulders),
    Double.NaN, 0.0, false, null,
) {
    private val REGOLITH = index("regolith")
    private val DUST = index("dust")
    private val ROCK = index("rock")
    private val BRIGHT = index("bright")
    private val MARE = index("mare")
    override val reliefMax = 18.0 + 1.95 * 48 + 40 * scarps + 520 * 0.045 + (if (boulders > 1) 18 * 0.75 else 7 * 0.75) + 1.2
    override val reliefMin = -520 * 0.16 - 2.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        var h = 18 + d.fbm(1400.0, o.x, o.y, 0.37, 4) * 3 * 16
        if (scarps > 0) {
            val sc = d.fbm(2400.0, o.y, o.x, 6.1, 3) * 3
            h += smooth(-0.05, 0.05, sc) * 40 * scarps
        }
        var tag = Tag.NONE
        if (basins > 0) {
            val flat = smooth(0.05, 0.3, d.fbm(3000.0, o.x, o.y, 2.6, 3) * 3) * basins
            if (flat > 0) {
                h = h * (1 - flat) + 6 * flat
                if (flat > 0.6) tag = Tag.MARE
            }
        }
        val f = features(d, list, s)
        out.h = h + f.height + d.n(30.0, 0.0, 0.0, 5.5) * 1.2
        out.tag = if (f.tag != Tag.NONE) f.tag else tag
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.BOULDER || slope > 0.9) return pack(ROCK, ROCK)
        if (tag == Tag.RIM) return pack(BRIGHT, REGOLITH)
        if (tag == Tag.MARE) return pack(MARE, REGOLITH)
        if (tag == Tag.CRATER && d.n(200.0, 0.0, 0.0, 2.2) > 0.1) return pack(DUST, REGOLITH)
        if (d.n(700.0, 0.0, 0.0, 4.4) < -0.4) return pack(MARE, REGOLITH)
        val spot = d.n(240.0, 0.0, 0.0, 7.1) + d.n(60.0, 0.0, 0.0, 3.7) * 0.25
        return pack(if (spot > 0.3) DUST else REGOLITH, REGOLITH)
    }

    companion object {
        fun craterLayers(dense: Double, boulders: Double): Array<FeatureLayer> {
            val l = arrayListOf(
                FeatureLayer(FeatureLayer.CRATER, 1400.0, 0.45 * dense, 120.0, 520.0, depth = 0.16, rim = 0.045, flat = 0.4),
                FeatureLayer(FeatureLayer.CRATER, 360.0, 0.5 * dense, 24.0, 120.0, depth = 0.22, rim = 0.07),
                FeatureLayer(FeatureLayer.CRATER, 90.0, 0.45 * dense, 5.0, 26.0, depth = 0.25, rim = 0.08),
                FeatureLayer(FeatureLayer.BOULDER, 44.0, 0.16 * boulders, 2.5, 7.0),
            )
            if (boulders > 1) l += FeatureLayer(FeatureLayer.BOULDER, 140.0, 0.25, 8.0, 18.0)
            return l.toTypedArray()
        }
    }
}

/** Volcanic: sulfur plains, lava in the hollows, calderas (Io, Venus). */
class VolcanicStyle(c: Map<String, Rgb>, val flows: Double = 0.0, val lava: Double = 1.0) : TerrainStyle(
    "volcanic",
    arrayOf(
        StyleMaterial("sulfur", RobloxMaterial.Sand, c.getValue("sulfur")),
        StyleMaterial("orange", RobloxMaterial.Ground, c.getValue("orange")),
        StyleMaterial("pale", RobloxMaterial.Salt, c.getValue("pale")),
        StyleMaterial("basalt", RobloxMaterial.Basalt, c.getValue("basalt")),
        StyleMaterial("lava", RobloxMaterial.CrackedLava, c.getValue("lava")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
    ),
    arrayOf(
        FeatureLayer(FeatureLayer.CRATER, 1600.0, 0.35, 150.0, 400.0, depth = 0.1, rim = 0.03, flat = 0.6),
        FeatureLayer(FeatureLayer.CRATER, 300.0, 0.2, 20.0, 70.0, depth = 0.18, rim = 0.05),
        FeatureLayer(FeatureLayer.BOULDER, 60.0, 0.12, 3.0, 7.0),
    ),
    Double.NaN, 0.0, false, null,
) {
    private val SULFUR = index("sulfur")
    private val ORANGE = index("orange")
    private val PALE = index("pale")
    private val BASALT = index("basalt")
    private val LAVA = index("lava")
    override val reliefMax = 20.0 + 1.95 * 3 * 16 + 400 * 0.03 + 7 * 0.75 + 1.5
    override val reliefMin = 9.0 - 60.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val plain = d.fbm(1200.0, o.x, o.y, 0.37, 4) * 3
        var channel = 0.0
        if (flows > 0) channel = d.ridged(900.0, o.y, o.x, 3.4, 2) * 14 * flows
        val f = features(d, list, s)
        val h = 20 + plain * 16 + f.height + d.n(50.0, 0.0, 0.0, 3.3) * 1.5 - channel
        if (h < 9) {
            // lava lake: almost flat
            out.h = 9 - (9 - h) * 0.12
            out.tag = Tag.LAVA
            return
        }
        out.h = h
        out.tag = f.tag
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.LAVA) return pack(if (lava > 0) LAVA else BASALT, BASALT)
        if (slope > 0.8 || tag == Tag.BOULDER) return pack(BASALT, BASALT)
        if (tag == Tag.RIM) return pack(PALE, SULFUR)
        val q = d.n(320.0, 0.0, 0.0, 1.9) + d.n(90.0, 0.0, 0.0, 6.4) * 0.4
        if (q > 0.25) return pack(ORANGE, SULFUR)
        if (q < -0.3) return pack(PALE, SULFUR)
        if (q < -0.15 && d.n(40.0, 0.0, 0.0, 2.7) > 0.25) return pack(BASALT, BASALT)
        return pack(SULFUR, SULFUR)
    }
}

/** Ice shell with double ridges and chaos blocks (Europa). */
class IcecrackStyle(c: Map<String, Rgb>) : TerrainStyle(
    "icecrack",
    arrayOf(
        StyleMaterial("ice", RobloxMaterial.Ice, c.getValue("ice")),
        StyleMaterial("snow", RobloxMaterial.Snow, c.getValue("snow")),
        StyleMaterial("glacier", RobloxMaterial.Glacier, c.getValue("glacier")),
        StyleMaterial("crack", RobloxMaterial.Salt, c.getValue("crack")),
        StyleMaterial("stain", RobloxMaterial.Ground, c.getValue("stain")),
    ),
    null, Double.NaN, 0.0, false, null,
) {
    private val ICE = index("ice")
    private val SNOW = index("snow")
    private val GLACIER = index("glacier")
    private val CRACK = index("crack")
    private val STAIN = index("stain")
    override val reliefMax = 14.0 + 1.95 * 18 + 16.0 + 18.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val plain = d.fbm(1600.0, o.x, o.y, 0.37, 3) * 3 * 6
        val a = d.n(650.0, o.x, o.y, 2.3)
        val b = d.n(1500.0, o.y, o.x, 5.9)
        // double ridge: a broad wall with a narrow groove; ~25 m and ~40 m wide, faded when unresolved
        val ridgeA = ((1 - smooth(0.0, 0.05, abs(a))) - (1 - smooth(0.0, 0.014, abs(a))) * 0.75) * d.fade(25.0)
        val ridgeB = ((1 - smooth(0.0, 0.035, abs(b))) - (1 - smooth(0.0, 0.01, abs(b))) * 0.6) * 0.8 * d.fade(40.0)
        val ridge = max(ridgeA, ridgeB)
        val chaos = smooth(0.2, 0.35, d.fbm(2800.0, o.x, o.y, 7.2, 2) * 3)
        var blocks = 0.0
        if (chaos > 0) blocks = terrace(d.n(140.0, 0.0, 0.0, 4.4) + 0.5, 2.0, 0.06) * 18 * chaos
        out.h = 14 + plain + ridge * 16 + blocks
        out.tag = if (ridge > 0.2) Tag.CRACK else if (blocks > 3) Tag.CHAOS else Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.CRACK) return pack(CRACK, ICE)
        if (slope > 0.9) return pack(GLACIER, ICE)
        if (tag == Tag.CHAOS) return pack(if (d.n(60.0, 0.0, 0.0, 3.6) > 0) GLACIER else STAIN, ICE)
        val q = d.n(400.0, 0.0, 0.0, 8.1)
        if (q > 0.2) return pack(SNOW, ICE)
        if (q < -0.32) return pack(STAIN, ICE)
        return pack(ICE, ICE)
    }
}

/** Grooved ice: bright bands with parallel grooves, dark plains (Ganymede). */
class GroovedStyle(c: Map<String, Rgb>) : TerrainStyle(
    "grooved",
    arrayOf(
        StyleMaterial("ice", RobloxMaterial.Ice, c.getValue("ice")),
        StyleMaterial("frost", RobloxMaterial.Snow, c.getValue("frost")),
        StyleMaterial("dark", RobloxMaterial.Slate, c.getValue("dark")),
        StyleMaterial("dust", RobloxMaterial.Ground, c.getValue("dust")),
    ),
    arrayOf(
        FeatureLayer(FeatureLayer.CRATER, 700.0, 0.35, 40.0, 220.0, depth = 0.18, rim = 0.06, flat = 0.3),
        FeatureLayer(FeatureLayer.CRATER, 160.0, 0.3, 8.0, 40.0, depth = 0.22, rim = 0.07),
    ),
    Double.NaN, 0.0, false, null,
) {
    private val ICE = index("ice")
    private val FROST = index("frost")
    private val DARK = index("dark")
    private val DUST = index("dust")
    override val reliefMax = 18.0 + 1.95 * 30 + 14.0 + 220 * 0.06
    override val reliefMin = -220 * 0.18 - 2.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val band = d.fbm(2200.0, o.x, o.y, 0.37, 3) * 3
        val bright = smooth(-0.05, 0.12, band)
        // grooves along a seed direction
        val w = d.phase(o.ca, o.sa, 46.0) + d.n(400.0, 0.0, 0.0, 6.6) * 1.2
        val g = sin(w * 6.2832)
        val fade = d.fade(46.0)
        val g2 = 0.5 + (g * g - 0.5) * fade
        val f = features(d, list, s)
        out.h = 18 + band * 10 + g2 * 14 * bright * d.dirWeight + f.height
        out.tag = if (f.tag != Tag.NONE) f.tag else if (bright > 0.5) Tag.BRIGHT else Tag.DARK
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.RIM) return pack(FROST, ICE)
        if (tag == Tag.CRATER) return pack(DUST, DARK)
        if (slope > 0.9) return pack(DARK, DARK)
        if (tag == Tag.BRIGHT) return pack(if (d.n(120.0, 0.0, 0.0, 2.4) > 0.15) FROST else ICE, ICE)
        return pack(if (d.n(90.0, 0.0, 0.0, 5.2) > 0.2) DUST else DARK, DARK)
    }
}

/** Nitrogen ice: cantaloupe pits, smooth frozen lakes, dark streaks (Triton). */
class NitrogenStyle(c: Map<String, Rgb>) : TerrainStyle(
    "nitrogen",
    arrayOf(
        StyleMaterial("frost", RobloxMaterial.Snow, c.getValue("frost")),
        StyleMaterial("nitro", RobloxMaterial.Glacier, c.getValue("nitro")),
        StyleMaterial("ice", RobloxMaterial.Ice, c.getValue("ice")),
        StyleMaterial("streak", RobloxMaterial.Ground, c.getValue("streak")),
    ),
    arrayOf(FeatureLayer(FeatureLayer.PIT, 90.0, 0.85, 26.0, 46.0, depth = 0.16, reach = 1.0, skew = 1.0)),
    Double.NaN, 0.0, false, null,
) {
    private val FROST = index("frost")
    private val NITRO = index("nitro")
    private val ICE = index("ice")
    private val STREAK = index("streak")
    override val reliefMax = 22.0 + 1.95 * 3 * 12 + 4.0
    override val reliefMin = 22.0 - 1.95 * 3 * 12 - 46 * 0.16 - 2.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val plain = d.fbm(1800.0, o.x, o.y, 0.37, 3) * 3
        val lake = smooth(0.18, 0.3, plain)
        val cells = features(d, list, s).height
        var h = 22 + plain * 12 + cells * (1 - lake)
        if (lake > 0) h = h * (1 - lake) + 26 * lake
        val streak = d.streak(o.x, o.y)
        out.h = h
        out.tag = if (lake > 0.5) Tag.LAKE else if (streak > 0.3) Tag.STREAK else Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (tag == Tag.LAKE) return pack(ICE, ICE)
        if (tag == Tag.STREAK) return pack(STREAK, NITRO)
        if (slope > 0.8) return pack(NITRO, ICE)
        return pack(if (d.n(220.0, 0.0, 0.0, 1.7) > 0.12) NITRO else FROST, ICE)
    }
}

/** Jagged ridges and ranges; icy = glaciers instead of rock. */
class RidgesStyle(c: Map<String, Rgb>, val icy: Boolean = false, val snowline: Double = 230.0) : TerrainStyle(
    "ridges",
    arrayOf(
        StyleMaterial("low", if (icy) RobloxMaterial.Ice else RobloxMaterial.Ground, c.getValue("low")),
        StyleMaterial("rock", if (icy) RobloxMaterial.Glacier else RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("peak", RobloxMaterial.Snow, c.getValue("peak")),
        StyleMaterial("scree", if (icy) RobloxMaterial.Ice else RobloxMaterial.Slate, c.getValue("scree")),
        StyleMaterial("sand", RobloxMaterial.Sand, c.getValue("sand")),
    ),
    null, Double.NaN, 0.0, false, null,
) {
    private val LOW = index("low")
    private val ROCK = index("rock")
    private val PEAK = index("peak")
    private val SCREE = index("scree")
    private val SAND = index("sand")
    override val reliefMax = 14.0 + 1.95 * 72 + 320.0

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val base = d.fbm(1200.0, o.x, o.y, 0.37, 4) * 3 * 24
        val r = d.ridged(1400.0, o.y, o.x, 2.9, 5)
        val mask = smooth(-0.15, 0.2, d.fbm(4200.0, o.x, o.y, 6.6, 2) * 3)
        out.h = 14 + base + r * r * 320 * (0.25 + 0.75 * mask)
        out.tag = Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (h > snowline + d.n(140.0, 0.0, 0.0, 3.9) * 40 && slope < 1.1) return pack(PEAK, ROCK)
        if (slope > 1) return pack(ROCK, ROCK)
        if (slope > 0.6) return pack(SCREE, ROCK)
        if (h < 40 && d.n(200.0, 0.0, 0.0, 5.5) > 0.2) return pack(SAND, LOW)
        return pack(LOW, ROCK)
    }
}

/** Ocean worlds: islands, open sea, reefs. */
class OceanStyle(
    c: Map<String, Rgb>,
    level: Double = 10.0,
    val bias: Double = -6.0,
    val amp: Double = 30.0,
    val hills: Double = 80.0,
    val deep: Boolean = false,
) : TerrainStyle(
    "ocean",
    arrayOf(
        StyleMaterial("sand", RobloxMaterial.Sand, c.getValue("sand")),
        StyleMaterial("reef", RobloxMaterial.Limestone, c.getValue("reef")),
        StyleMaterial("grass", RobloxMaterial.Grass, c.getValue("grass")),
        StyleMaterial("rock", RobloxMaterial.Rock, c.getValue("rock")),
        StyleMaterial("lush", RobloxMaterial.LeafyGrass, c.getValue("lush")),
    ),
    null, level, -24.0, false,
    if (deep) WaterLook(Rgb.of(14, 58, 92), 0.4, 0.4, 0.2, 10.0) else WaterLook(Rgb.of(20, 104, 126), 0.6, 0.4, 0.16, 9.0),
) {
    private val W = level
    private val SAND = index("sand")
    private val REEF = index("reef")
    private val GRASS = index("grass")
    private val ROCK = index("rock")
    private val LUSH = index("lush")
    override val reliefMax = W + bias + 1.95 * amp + hills + 1.5

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val n = d.fbm(1100.0, o.x, o.y, 0.37, 5) * 3
        val hill = smooth(0.3, 0.7, n) * d.ridged(700.0, o.y, o.x, 4.2, 3) * hills
        out.h = W + bias + n * amp + hill + d.n(36.0, 0.0, 0.0, 8.2) * 1.5
        out.tag = Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (h < W - 3) return pack(if (d.n(70.0, 0.0, 0.0, 1.3) > 0.1) REEF else SAND, SAND)
        if (h < W + 3) return pack(SAND, SAND)
        if (slope > 0.8) return pack(ROCK, ROCK)
        return pack(if (d.n(120.0, 0.0, 0.0, 6.2) > 0.15) LUSH else GRASS, SAND)
    }
}

/** Giants: ice floes on a dense calm sea (unreachable: giants are not landable). */
class FloesStyle(c: Map<String, Rgb>, val cover: Double = 0.45) : TerrainStyle(
    "floes",
    arrayOf(
        StyleMaterial("ice", RobloxMaterial.Ice, c.getValue("ice")),
        StyleMaterial("snow", RobloxMaterial.Snow, c.getValue("snow")),
        StyleMaterial("glacier", RobloxMaterial.Glacier, c.getValue("glacier")),
        StyleMaterial("slush", RobloxMaterial.Salt, c.getValue("slush")),
    ),
    null, 10.0, -24.0, false,
    WaterLook(c.getValue("sea"), 0.3, 0.6, 0.08, 5.0),
) {
    private val W = 10.0
    private val edge = (0.5 - cover) * 1.4 - 0.082
    private val ICE = index("ice")
    private val SNOW = index("snow")
    private val GLACIER = index("glacier")
    private val SLUSH = index("slush")
    override val reliefMax = W + 3 + 1.95 * 3 * 6 + 18.0 + 0.8

    override fun relief(d: Domain, list: FeatureList, s: StyleScratch, o: SeedOffsets, wet: Double, cold: Double, dry: Double, out: Relief) {
        val n = d.fbm(1100.0, o.x, o.y, 0.37, 4) * 3 + d.n(260.0, 0.0, 0.0, 3.7) * 0.12
        // floes: flat tables with steep edges
        val floe = smooth(edge, edge + 0.08, n)
        // pressure ridges on the floes
        val ridge = d.ridged(700.0, o.y, o.x, 5.1, 2) * floe
        val bed = W - 34 + d.n(300.0, 0.0, 0.0, 6.4) * 2
        val top = W + 3 + max(n, 0.0) * 6 + ridge * ridge * 18 + d.n(45.0, 0.0, 0.0, 2.9) * 0.8
        out.h = bed + (top - bed) * floe
        out.tag = if (floe < 0.5) Tag.SEA else if (ridge > 0.55) Tag.RIDGE else Tag.NONE
    }

    override fun paint(h: Double, slope: Double, tag: Int, d: Domain, wet: Double, cold: Double, dry: Double): Int {
        if (h < W - 1) return pack(SLUSH, SLUSH)
        if (slope > 0.8 || tag == Tag.RIDGE) return pack(GLACIER, ICE)
        val q = d.n(140.0, 0.0, 0.0, 4.1) + d.n(40.0, 0.0, 0.0, 1.3) * 0.3
        return pack(if (q > 0.15) SNOW else if (q < -0.35) SLUSH else ICE, ICE)
    }
}
