package com.starwake.core.terrain

import com.starwake.core.terrain.TerrainMath.smooth
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** What the planet-scale layer says about one point (§9.4 + DECISIONS 17/17a). */
class MacroSample {
    /** Height of the local datum above the reference sphere + Lift (m): the Roblox relief stands on it. */
    @JvmField var height = 0.0

    /** Datum of the water level W (m): the local datum for lakes, 0 for the open sea. */
    @JvmField var waterDatum = 0.0

    /** Tone inputs of styles with `usesTone` (what Roblox read from the planet image). */
    @JvmField var wet = 0.0
    @JvmField var cold = 0.0
    @JvmField var dry = 0.0

    /** Colour factors of the other styles (Roblox's `applyTone` tint, evaluated per point). */
    @JvmField var tintR = 1.0
    @JvmField var tintG = 1.0
    @JvmField var tintB = 1.0

    /** Polar cap / frost cover 0..1 and its colour (sRGB). */
    @JvmField var frost = 0.0
    @JvmField var frostR = 1.0
    @JvmField var frostG = 1.0
    @JvmField var frostB = 1.0

    /** Open-sea colour (sRGB) for styles with water. */
    @JvmField var seaR = 0.1
    @JvmField var seaG = 0.25
    @JvmField var seaB = 0.4

    /** Share of sea look for water here (1 = open sea: opaque, sea colour; 0 = lake look). */
    @JvmField var seaLook = 0.0

    /** 0..1: lakes are filled here (steep macro terrain, where a level water table cannot stand). */
    @JvmField var lakeBlock = 0.0

    fun copy(o: MacroSample) {
        height = o.height; waterDatum = o.waterDatum; wet = o.wet; cold = o.cold; dry = o.dry
        tintR = o.tintR; tintG = o.tintG; tintB = o.tintB
        frost = o.frost; frostR = o.frostR; frostG = o.frostG; frostB = o.frostB
        seaR = o.seaR; seaG = o.seaG; seaB = o.seaB; seaLook = o.seaLook; lakeBlock = o.lakeBlock
    }

    fun reset() {
        height = 0.0; waterDatum = 0.0; wet = 0.0; cold = 0.0; dry = 0.0
        tintR = 1.0; tintG = 1.0; tintB = 1.0; frost = 0.0; seaLook = 0.0; lakeBlock = 0.0
    }
}

/** Scratch of one macro evaluation (one per thread). */
class MacroScratch {
    val result = FeatureResult()
    val domain = Domain()
    val colour = DoubleArray(3)
}

/**
 * The planet-scale layer under the Roblox styles (DECISIONS 17, 17a). Roblox's planets were images
 * made offline (not exported); its runtime fallback (ShipPlanetTexture, 03-world.md §2.13) built a
 * planet look from `n = fbm(s·2.2 + offset, 5)` with a sea threshold, the class colour ramp by
 * relative height and ice beyond `iceLatitude`. This layer starts from exactly that field, adds
 * finer octaves down to a few km, and derives from it everything the styles used to read from the
 * image (wet/cold/dry, tint, sea colour) plus a real relief: continents and seas, mountain ranges
 * of a few km, basins and planet-scale craters. Being one function of the direction, the orbit view
 * (coarse tiles, the impostor bake) and the ground always agree.
 *
 * Band-limited like the styles: octaves shorter than 4 vertex spacings fade out (`spacing` 0 = exact).
 * Immutable and thread-safe; callers pass a [MacroScratch].
 */
abstract class MacroField(val radius: Double, val seed: Int, val palette: ClassPalette) {
    /** Offset of the noise space (the fallback generator's Random(seed) offset, port hash). */
    protected val ox = (TerrainMath.robloxHash(seed.toDouble(), 11.0, 0.25) - 0.5) * 800.0
    protected val oy = (TerrainMath.robloxHash(seed.toDouble(), 12.0, 0.25) - 0.5) * 800.0
    protected val oz = (TerrainMath.robloxHash(seed.toDouble(), 13.0, 0.25) - 0.5) * 800.0

    /** Lattice period of the base field in metres (the fallback's s·2.2). */
    protected val baseLambda = radius / 2.2

    /** Largest |height| this layer produces (m), for bounds. */
    abstract val heightMax: Double
    abstract val heightMin: Double

    /** Planet-scale feature layers (BIG_CRATER), null if none. */
    open val bigCraterLayers: Array<FeatureLayer>? = null

    /** Salt of the big-crater lattice. */
    val bigSalt = Math.floorMod(seed * 31 + 7, 997).toDouble() + 0.457

    /**
     * Evaluates the layer at unit direction (nx, ny, nz). [bigCraters] must hold the planet-scale
     * features around the point (collected by the caller for its box), or be empty.
     */
    abstract fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample)

    /**
     * The fallback field: fbm of the direction scaled by 2.2 plus offsets, 5 Roblox octaves
     * normalised as Roblox (1.9375) and [extra] finer octaves on top, each faded by the band limit.
     */
    protected fun baseField(nx: Double, ny: Double, nz: Double, spacing: Double, extra: Int): Double {
        val x = nx * 2.2 + ox
        val y = ny * 2.2 + oy
        val z = nz * 2.2 + oz
        var total = 0.0
        var amplitude = 1.0
        var frequency = 1.0
        for (k in 0 until 5 + extra) {
            val w = fade(baseLambda / frequency, spacing)
            if (w <= 0.0) break
            total += LuauNoise.noise(x * frequency, y * frequency, z * frequency) * amplitude * w
            amplitude *= 0.5
            frequency *= 2.07
        }
        return total / 1.9375
    }

    /** fbm of the direction at lattice period [lambda] metres, [octaves], band-limited, normalised. */
    protected fun fbmDir(nx: Double, ny: Double, nz: Double, lambda: Double, octaves: Int, spacing: Double, sx: Double, sy: Double, sz: Double): Double {
        val k = radius / lambda
        val x = nx * k + sx
        val y = ny * k + sy
        val z = nz * k + sz
        var total = 0.0
        var amplitude = 1.0
        var frequency = 1.0
        var norm = 0.0
        for (o in 0 until octaves) {
            norm += amplitude
            val w = fade(lambda / frequency, spacing)
            if (w > 0.0) total += LuauNoise.noise(x * frequency, y * frequency, z * frequency) * amplitude * w
            amplitude *= 0.5
            frequency *= 2.07
        }
        return total / norm
    }

    /**
     * Ridged multifractal of the direction (ridge lines = 1), octaves 2.03 apart, each octave weighted
     * by the previous one (sharper peaks, smoother valleys), band-limited to its mean.
     */
    protected fun ridgedDir(nx: Double, ny: Double, nz: Double, lambda: Double, octaves: Int, spacing: Double, sx: Double, sy: Double, sz: Double): Double {
        val k = radius / lambda
        val x = nx * k + sx
        val y = ny * k + sy
        val z = nz * k + sz
        var total = 0.0
        var amplitude = 1.0
        var frequency = 1.0
        var norm = 0.0
        var weight = 1.0
        for (o in 0 until octaves) {
            norm += amplitude
            val w = fade(lambda / frequency, spacing)
            var r = Domain.RIDGED_OCTAVE_MEAN
            if (w > 0.0) {
                var n = 1.0 - abs(LuauNoise.noise(x * frequency, y * frequency, z * frequency)) * 2.2
                if (n < 0.0) n = 0.0
                r = Domain.RIDGED_OCTAVE_MEAN + (n * n - Domain.RIDGED_OCTAVE_MEAN) * w
            }
            total += r * amplitude * weight
            weight = min(1.0, 0.35 + r * 1.3)
            amplitude *= 0.5
            frequency *= 2.03
        }
        return total / norm
    }

    /** Latitude as the fallback generator measures it: |lat|/(π/2), 0 at the equator, 1 at the poles. */
    protected fun latFraction(ny: Double): Double = abs(asin(TerrainMath.clamp(ny, -1.0, 1.0))) / (PI / 2)

    protected fun fade(lambda: Double, spacing: Double): Double =
        if (spacing <= 0.0) 1.0 else smooth(2.0 * spacing, 4.0 * spacing, lambda)

    /** The fallback's polar ice: `|lat| − iceLatitude + n·0.12`, ×8, as a 0..1 cover. */
    protected fun polarIce(ny: Double, n: Double): Double {
        if (palette.iceLatitude >= 0.99) return 0.0
        return TerrainMath.clamp((latFraction(ny) - palette.iceLatitude + n * 0.12) * 8.0, 0.0, 1.0)
    }

    /** Ramp shore → low → high → peak by relative height t (ShipPlanetTexture's colour rule). */
    protected fun ramp(t: Double, shore: Rgb, low: Rgb, high: Rgb, peak: Rgb, out: DoubleArray) {
        val a: Rgb
        val b: Rgb
        val k: Double
        when {
            t < 0.10 -> { a = shore; b = shore; k = 0.0 }
            t < 0.45 -> { a = shore; b = low; k = (t - 0.10) / 0.35 }
            t < 0.78 -> { a = low; b = high; k = (t - 0.45) / 0.33 }
            else -> { a = high; b = peak; k = min(1.0, (t - 0.78) / 0.22) }
        }
        out[0] = a.r + (b.r - a.r) * k
        out[1] = a.g + (b.g - a.g) * k
        out[2] = a.b + (b.b - a.b) * k
    }

    /** The style's tint towards a wanted colour (Roblox applyTone per point). */
    protected fun tint(wantR: Double, wantG: Double, wantB: Double, have: Rgb, out: MacroSample) {
        out.tintR = TerrainMath.tintFactor(wantR, have.r)
        out.tintG = TerrainMath.tintFactor(wantG, have.g)
        out.tintB = TerrainMath.tintFactor(wantB, have.b)
    }

    protected fun bigCraterHeight(s: MacroScratch, bigCraters: FeatureList, nx: Double, ny: Double, nz: Double, spacing: Double): Double {
        val layers = bigCraterLayers ?: return 0.0
        if (bigCraters.count == 0) return 0.0
        s.domain.setSphere(nx, ny, nz, radius, spacing)
        Features.evaluate(bigCraters, layers, s.domain, s.result)
        return s.result.height
    }

    companion object {
        /**
         * The layer for a style and class: TERRAN for styles that read the tone (temperate), OCEAN for
         * ocean worlds, CRATERED for cratered styles, ROCKY for desert/ridges, GENERIC otherwise.
         */
        fun forStyle(style: TerrainStyle, radius: Double, seed: Int, bodyClass: String): MacroField {
            val palette = Palettes.colours(bodyClass)
            return when (style) {
                is TemperateStyle -> TerranMacro(radius, seed, palette, style, seaShare = if (bodyClass == "Terrestrial World") 0.6 else 0.55)
                is OceanStyle -> OceanMacro(radius, seed, palette, style)
                is CrateredStyle -> CrateredMacro(radius, seed, palette, style)
                is DesertStyle, is RidgesStyle -> RockyMacro(radius, seed, palette, style)
                else -> GenericMacro(radius, seed, palette, style)
            }
        }

        /** The base-field value below which [share] of the sphere lies (deterministic sampling). */
        fun quantile(f: (Double, Double, Double) -> Double, share: Double): Double {
            val n = 4096
            val v = DoubleArray(n)
            // Fibonacci sphere
            val golden = PI * (3.0 - sqrt(5.0))
            for (i in 0 until n) {
                val y = 1.0 - 2.0 * (i + 0.5) / n
                val r = sqrt(max(0.0, 1.0 - y * y))
                val a = golden * i
                v[i] = f(r * kotlin.math.cos(a), y, r * kotlin.math.sin(a))
            }
            v.sort()
            return v[(share * (n - 1)).toInt().coerceIn(0, n - 1)]
        }
    }
}

/**
 * Earth-like worlds (temperate styles): continents and seas from the fallback field with a sea
 * share, coastal plains, interior plateaus, long mountain belts of up to ~5 km, polar ice and an
 * altitude snowline (→ cold), subtropical desert belts in continental interiors (→ dry), a sea
 * colour from the fallback's shallow/deep rule. The Roblox temperate relief then draws the coast
 * line, beaches, lakes, hills and dunes inside these fields exactly as it did from the image.
 */
class TerranMacro(radius: Double, seed: Int, palette: ClassPalette, style: TemperateStyle, seaShare: Double) :
    MacroField(radius, seed, palette) {
    private val water = style.water
    private val seaLevel: Double = quantile({ x, y, z -> continent(x, y, z, 0.0) }, seaShare)
    private val shallow = palette.shallow ?: Rgb.of(38, 92, 140)
    private val deep = palette.deep ?: Rgb.of(14, 42, 84)
    override val heightMax = 5600.0
    override val heightMin = 0.0

    /** Continent field: the fallback field, domain-warped a little for less blobby coasts, 11 octaves (to ~2 km on Earth). */
    private fun continent(nx: Double, ny: Double, nz: Double, spacing: Double): Double {
        val wx = fbmDir(nx, ny, nz, radius * 0.9, 3, spacing, 31.7, 5.1, 12.9)
        val wy = fbmDir(nx, ny, nz, radius * 0.9, 3, spacing, 7.3, 44.2, 3.3)
        val wz = fbmDir(nx, ny, nz, radius * 0.9, 3, spacing, 19.9, 2.6, 57.5)
        val k = 0.22
        return baseField(nx + wx * k, ny + wy * k, nz + wz * k, spacing, extra = 6)
    }

    override fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample) {
        out.reset()
        val c = continent(nx, ny, nz, spacing)
        val land = c - seaLevel
        // the coast band (~20 km on Earth): wet runs 1 → 0 across it, the Roblox coast noise draws the line inside
        out.wet = smooth(0.012, -0.012, land)
        val inland = smooth(0.0, 0.3, land)
        val coastRamp = smooth(0.004, 0.06, land)
        // mountain belts: long sinuous lines (zero crossings of a low-frequency noise)
        val beltLine = 1.0 - abs(LuauNoise.noise(nx * radius / 2.6e6 + ox * 0.37, ny * radius / 2.6e6 + oy * 0.37, nz * radius / 2.6e6 + oz * 0.37)) * 3.2
        val beltNoise = fbmDir(nx, ny, nz, 900_000.0, 2, spacing, 3.1, 8.7, 1.4)
        val belt = smooth(0.25, 0.75, beltLine + beltNoise * 0.6) * smooth(0.01, 0.12, land)
        var mountains = 0.0
        if (belt > 0.0) {
            val r = ridgedDir(nx, ny, nz, 420_000.0, 10, spacing, 21.0, 17.0, 23.0)
            mountains = 5200.0 * belt * r.pow(1.7)
        }
        val rolling = 140.0 * fbmDir(nx, ny, nz, 80_000.0, 5, spacing, 71.0, 13.0, 41.0)
        val plateau = 420.0 * inland
        val height = coastRamp * max(0.0, plateau + mountains + rolling + 30.0)
        out.height = height
        out.waterDatum = height
        out.lakeBlock = smooth(0.08, 0.35, belt)
        // cold: the fallback's polar ice, plus a snowline falling from ~4.6 km at the equator to sea level at the poles
        val lat = latFraction(ny)
        val cap = polarIce(ny, c)
        val snowline = 4600.0 * (1.0 - lat).pow(0.8) + 150.0
        val alpine = smooth(snowline - 300.0, snowline + 500.0, height)
        out.cold = max(cap, alpine)
        // dry: subtropical belts in continental interiors, wetter towards the coasts and in the mountains
        val belt30 = smooth(0.10, 0.22, lat) * smooth(0.45, 0.32, lat)
        val noise = fbmDir(nx, ny, nz, 1_400_000.0, 4, spacing, 5.5, 9.5, 2.5)
        val desert = 0.75 * belt30 + 0.4 * inland + 1.5 * noise - 0.35 * belt - 0.15
        out.dry = smooth(0.2, 0.5, desert) * (1.0 - out.wet) * (1.0 - out.cold)
        // the sea: the fallback's shallow → deep by depth below the threshold, darkened (Roblox: sea colour ×0.6 near the sea)
        val depth = TerrainMath.clamp((seaLevel - c) * 4.0, 0.0, 1.0)
        out.seaR = (shallow.r + (deep.r - shallow.r) * depth) * 0.8
        out.seaG = (shallow.g + (deep.g - shallow.g) * depth) * 0.8
        out.seaB = (shallow.b + (deep.b - shallow.b) * depth) * 0.8
        out.seaLook = smooth(0.25, 0.6, out.wet + 0.4 * smooth(0.06, 0.0, land))
        if (water.isNaN()) out.seaLook = 0.0
    }
}

/** Ocean worlds: the sea level stays put; the field raises archipelago belts and lowers basins by a few tens of metres. */
class OceanMacro(radius: Double, seed: Int, palette: ClassPalette, style: OceanStyle) : MacroField(radius, seed, palette) {
    private val shallow = palette.shallow ?: Rgb.of(34, 100, 150)
    private val deep = palette.deep ?: Rgb.of(10, 36, 78)
    private val lookColour = style.waterLook!!.colour
    override val heightMax = 30.0
    override val heightMin = -40.0

    override fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample) {
        out.reset()
        val n = baseField(nx, ny, nz, spacing, extra = 5)
        out.height = TerrainMath.clamp(n * 70.0, -40.0, 30.0)
        out.waterDatum = 0.0
        val depth = TerrainMath.clamp(-n * 3.0, 0.0, 1.0)
        out.seaR = (shallow.r + (deep.r - shallow.r) * depth) * 0.5 + lookColour.r * 0.5
        out.seaG = (shallow.g + (deep.g - shallow.g) * depth) * 0.5 + lookColour.g * 0.5
        out.seaB = (shallow.b + (deep.b - shallow.b) * depth) * 0.5 + lookColour.b * 0.5
        out.seaLook = 1.0
        val ice = polarIce(ny, n)
        out.frost = ice
        out.frostR = palette.ice.r; out.frostG = palette.ice.g; out.frostB = palette.ice.b
    }
}

/**
 * Airless cratered bodies (Luna, Mercury, Callisto, moons): dark smooth maria in the low regions
 * of the field, bright rough highlands, and planet-scale craters from 1 km to a few hundred km
 * (the Roblox crater profile, depth and rim scaled down with size) on top of which the Roblox
 * cratered style adds its own craters and boulders.
 */
class CrateredMacro(radius: Double, seed: Int, palette: ClassPalette, private val style: CrateredStyle) : MacroField(radius, seed, palette) {
    private val mareLevel = quantile({ x, y, z -> baseField(x, y, z, 0.0, 2) }, 0.22)
    private val have = style.materials[0].colour
    private val mareColour = style.materials[style.index("mare")].colour
    private val brightColour = style.materials[style.index("bright")].colour
    private val regolith = style.materials[style.index("regolith")].colour

    override val bigCraterLayers: Array<FeatureLayer> = run {
        val k = style.dense.coerceIn(0.5, 1.6)
        // r in metres; scaled so small moons keep a sensible count (cells never bigger than the body)
        val size = { s: Double -> min(s, radius * 0.9) }
        arrayOf(
            FeatureLayer(FeatureLayer.BIG_CRATER, size(520_000.0), 0.30 * k, 60_000.0, 210_000.0, depth = 0.16, rim = 0.05, flat = 0.55),
            FeatureLayer(FeatureLayer.BIG_CRATER, size(170_000.0), 0.40 * k, 14_000.0, 66_000.0, depth = 0.16, rim = 0.05, flat = 0.5),
            FeatureLayer(FeatureLayer.BIG_CRATER, 52_000.0, 0.45 * k, 4_500.0, 21_000.0, depth = 0.16, rim = 0.05, flat = 0.45),
            FeatureLayer(FeatureLayer.BIG_CRATER, 15_000.0, 0.45 * k, 1_400.0, 6_000.0, depth = 0.16, rim = 0.05, flat = 0.4),
            FeatureLayer(FeatureLayer.BIG_CRATER, 4_400.0, 0.45 * k, 560.0, 1_800.0, depth = 0.16, rim = 0.05, flat = 0.4),
        )
    }
    override val heightMax = 2600.0
    override val heightMin = -6500.0

    override fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample) {
        out.reset()
        val n = baseField(nx, ny, nz, spacing, extra = 7)
        // maria: the low regions, flooded flat
        val mare = smooth(mareLevel + 0.03, mareLevel - 0.05, n)
        val rough = fbmDir(nx, ny, nz, 120_000.0, 6, spacing, 4.4, 2.2, 9.9)
        val highlands = 1400.0 * (n - mareLevel) + 700.0 * rough
        val craters = bigCraterHeight(s, bigCraters, nx, ny, nz, spacing)
        out.height = highlands * (1.0 - mare) - 1500.0 * mare + craters * (1.0 - 0.65 * mare)
        out.waterDatum = out.height
        // colour: dark maria, bright highlands (and brighter crater rims/ejecta where craters are fresh: rough > 0.3)
        val hi = TerrainMath.clamp(0.35 + (n - mareLevel) * 2.0 + rough * 0.4, 0.0, 1.0)
        var r = regolith.r + (brightColour.r - regolith.r) * hi * 0.7
        var g = regolith.g + (brightColour.g - regolith.g) * hi * 0.7
        var b = regolith.b + (brightColour.b - regolith.b) * hi * 0.7
        val mareTone = 0.82
        r += (mareColour.r * mareTone - r) * mare
        g += (mareColour.g * mareTone - g) * mare
        b += (mareColour.b * mareTone - b) * mare
        tint(r, g, b, have, out)
        out.frost = polarIce(ny, n)
        out.frostR = palette.ice.r; out.frostG = palette.ice.g; out.frostB = palette.ice.b
    }
}

/**
 * Rocky deserts (Mars-like, desert and ridge styles): a hemispheric dichotomy of ±2 km, long
 * mountain belts of a few km, planet-scale craters (shallower and fewer than on airless moons),
 * bright dusty and dark rocky regions from the fallback ramp of the style's own colours, small
 * polar caps beyond the class ice latitude.
 */
class RockyMacro(radius: Double, seed: Int, palette: ClassPalette, private val style: TerrainStyle) : MacroField(radius, seed, palette) {
    private val have = style.materials[0].colour
    private val shore: Rgb
    private val low: Rgb
    private val high: Rgb
    private val peak: Rgb
    private val axisX: Double
    private val axisY: Double
    private val axisZ: Double

    init {
        if (style is DesertStyle) {
            shore = style.materials[style.index("sand")].colour
            low = style.materials[style.index("dust")].colour
            high = style.materials[style.index("rock")].colour
            peak = style.materials[style.index("pale")].colour
        } else {
            shore = style.materials[style.index("sand")].colour
            low = style.materials[style.index("low")].colour
            high = style.materials[style.index("rock")].colour
            peak = style.materials[style.index("scree")].colour
        }
        // dichotomy axis: a seed direction tilted from the pole
        val a = TerrainMath.robloxHash(seed.toDouble(), 21.0, 0.5) * 2 * PI
        val tilt = 0.35 + 0.5 * TerrainMath.robloxHash(seed.toDouble(), 22.0, 0.5)
        axisX = kotlin.math.sin(tilt) * kotlin.math.cos(a)
        axisY = kotlin.math.cos(tilt)
        axisZ = kotlin.math.sin(tilt) * kotlin.math.sin(a)
    }

    override val bigCraterLayers: Array<FeatureLayer> = arrayOf(
        FeatureLayer(FeatureLayer.BIG_CRATER, min(600_000.0, radius * 0.9), 0.18, 60_000.0, 240_000.0, depth = 0.12, rim = 0.04, flat = 0.6),
        FeatureLayer(FeatureLayer.BIG_CRATER, 180_000.0, 0.22, 12_000.0, 60_000.0, depth = 0.12, rim = 0.04, flat = 0.5),
        FeatureLayer(FeatureLayer.BIG_CRATER, 50_000.0, 0.25, 3_500.0, 16_000.0, depth = 0.14, rim = 0.045, flat = 0.45),
        FeatureLayer(FeatureLayer.BIG_CRATER, 14_000.0, 0.25, 1_000.0, 4_500.0, depth = 0.14, rim = 0.05, flat = 0.4),
    )
    override val heightMax = 7000.0
    override val heightMin = -6000.0

    override fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample) {
        out.reset()
        val n = baseField(nx, ny, nz, spacing, extra = 7)
        val side = nx * axisX + ny * axisY + nz * axisZ + 0.3 * fbmDir(nx, ny, nz, radius * 0.7, 3, spacing, 2.5, 6.5, 8.5)
        val highland = smooth(-0.25, 0.25, side)
        val beltLine = 1.0 - abs(LuauNoise.noise(nx * 2.4 + ox * 0.5, ny * 2.4 + oy * 0.5, nz * 2.4 + oz * 0.5)) * 3.0
        val belt = smooth(0.3, 0.8, beltLine + 0.5 * fbmDir(nx, ny, nz, radius * 0.25, 2, spacing, 1.3, 3.7, 5.9))
        var ranges = 0.0
        if (belt > 0.0) ranges = 3800.0 * belt * ridgedDir(nx, ny, nz, 300_000.0, 9, spacing, 13.0, 29.0, 7.0).pow(1.6)
        val craters = bigCraterHeight(s, bigCraters, nx, ny, nz, spacing)
        out.height = -2000.0 + 4000.0 * highland + 1100.0 * n + ranges + craters
        out.waterDatum = out.height
        val t = TerrainMath.clamp(n * 0.5 + 0.5 + (highland - 0.5) * 0.2, 0.0, 1.0)
        val c = s.colour
        ramp(t, shore, low, high, peak, c)
        tint(c[0], c[1], c[2], have, out)
        out.frost = polarIce(ny, n)
        out.frostR = palette.ice.r; out.frostG = palette.ice.g; out.frostB = palette.ice.b
    }
}

/** Ice, volcanic and haze worlds: gentle regional relief, albedo regions from the style's own colours, polar frost. */
class GenericMacro(radius: Double, seed: Int, palette: ClassPalette, private val style: TerrainStyle) : MacroField(radius, seed, palette) {
    private val have = style.materials[0].colour
    private val m = style.materials
    override val heightMax = 1600.0
    override val heightMin = -1600.0

    override fun sample(nx: Double, ny: Double, nz: Double, spacing: Double, s: MacroScratch, bigCraters: FeatureList, out: MacroSample) {
        out.reset()
        val n = baseField(nx, ny, nz, spacing, extra = 6)
        val ridge = ridgedDir(nx, ny, nz, 250_000.0, 7, spacing, 6.0, 2.0, 4.0)
        out.height = 900.0 * n + 500.0 * (ridge - 0.4)
        out.waterDatum = out.height
        out.lakeBlock = smooth(0.4, 0.8, ridge)
        val t = TerrainMath.clamp(n * 0.5 + 0.5, 0.0, 1.0)
        val c = s.colour
        ramp(t, m[min(1, m.size - 1)].colour, m[0].colour, m[min(2, m.size - 1)].colour, m[min(3, m.size - 1)].colour, c)
        // keep the regions subtle: half way between the style's base colour and the ramp
        tint((c[0] + have.r) * 0.5, (c[1] + have.g) * 0.5, (c[2] + have.b) * 0.5, have, out)
        out.frost = polarIce(ny, n)
        out.frostR = palette.ice.r; out.frostG = palette.ice.g; out.frostB = palette.ice.b
    }
}

/**
 * The planet-scale layer sampled on the vertex grid of one quadtree level (a rectangle of lattice
 * points on one face) and interpolated bilinearly in face coordinates: see [SurfaceField.macroLevel].
 * Fixed capacity, no allocation after construction.
 */
class MacroLattice(private val capacity: Int) {
    private val v = DoubleArray(capacity * F)
    private var k0 = 0
    private var j0 = 0
    private var nk = 0
    private var nj = 0
    private var inv = 1.0
    private val dir = DoubleArray(3)
    private val m = MacroSample()
    private var frostR = 1.0
    private var frostG = 1.0
    private var frostB = 1.0

    /** Evaluates the lattice points ka..kb × ja..jb of [lvl] on face [f] (exact, no band limit). */
    fun build(field: SurfaceField, f: Int, lvl: Int, ka: Int, kb: Int, ja: Int, jb: Int, s: FieldScratch) {
        k0 = ka
        j0 = ja
        nk = kb - ka + 1
        nj = jb - ja + 1
        require(nk * nj <= capacity) { "lattice ${nk}x$nj over capacity $capacity" }
        inv = cellsPerUnit(lvl)
        val den = 32.0 * (1 shl lvl)
        for (j in 0 until nj) for (k in 0 until nk) {
            val a = 2.0 * (ka + k) / den - 1.0
            val b = 2.0 * (ja + j) / den - 1.0
            CubeSphere.dir(f, a, b, dir, 0)
            field.macro.sample(dir[0], dir[1], dir[2], 0.0, s.macro, s.bigCraters, m)
            val o = (j * nk + k) * F
            v[o] = m.height; v[o + 1] = m.waterDatum; v[o + 2] = m.wet; v[o + 3] = m.cold; v[o + 4] = m.dry
            v[o + 5] = m.tintR; v[o + 6] = m.tintG; v[o + 7] = m.tintB; v[o + 8] = m.frost
            v[o + 9] = m.seaR; v[o + 10] = m.seaG; v[o + 11] = m.seaB; v[o + 12] = m.seaLook; v[o + 13] = m.lakeBlock
        }
        frostR = m.frostR; frostG = m.frostG; frostB = m.frostB
    }

    /** Bilinear value at face coordinates (a, b) of the lattice's face (clamped to the lattice). */
    fun interpolate(a: Double, b: Double, out: MacroSample) {
        val x = (a + 1.0) * inv - k0
        val y = (b + 1.0) * inv - j0
        var k = kotlin.math.floor(x).toInt()
        var j = kotlin.math.floor(y).toInt()
        if (k < 0) k = 0
        if (j < 0) j = 0
        if (k > nk - 2) k = nk - 2
        if (j > nj - 2) j = nj - 2
        val fx = x - k
        val fy = y - j
        val o00 = (j * nk + k) * F
        val o10 = o00 + F
        val o01 = o00 + nk * F
        val o11 = o01 + F
        val w00 = (1 - fx) * (1 - fy)
        val w10 = fx * (1 - fy)
        val w01 = (1 - fx) * fy
        val w11 = fx * fy
        out.height = v[o00] * w00 + v[o10] * w10 + v[o01] * w01 + v[o11] * w11
        out.waterDatum = v[o00 + 1] * w00 + v[o10 + 1] * w10 + v[o01 + 1] * w01 + v[o11 + 1] * w11
        out.wet = v[o00 + 2] * w00 + v[o10 + 2] * w10 + v[o01 + 2] * w01 + v[o11 + 2] * w11
        out.cold = v[o00 + 3] * w00 + v[o10 + 3] * w10 + v[o01 + 3] * w01 + v[o11 + 3] * w11
        out.dry = v[o00 + 4] * w00 + v[o10 + 4] * w10 + v[o01 + 4] * w01 + v[o11 + 4] * w11
        out.tintR = v[o00 + 5] * w00 + v[o10 + 5] * w10 + v[o01 + 5] * w01 + v[o11 + 5] * w11
        out.tintG = v[o00 + 6] * w00 + v[o10 + 6] * w10 + v[o01 + 6] * w01 + v[o11 + 6] * w11
        out.tintB = v[o00 + 7] * w00 + v[o10 + 7] * w10 + v[o01 + 7] * w01 + v[o11 + 7] * w11
        out.frost = v[o00 + 8] * w00 + v[o10 + 8] * w10 + v[o01 + 8] * w01 + v[o11 + 8] * w11
        out.seaR = v[o00 + 9] * w00 + v[o10 + 9] * w10 + v[o01 + 9] * w01 + v[o11 + 9] * w11
        out.seaG = v[o00 + 10] * w00 + v[o10 + 10] * w10 + v[o01 + 10] * w01 + v[o11 + 10] * w11
        out.seaB = v[o00 + 11] * w00 + v[o10 + 11] * w10 + v[o01 + 11] * w01 + v[o11 + 11] * w11
        out.seaLook = v[o00 + 12] * w00 + v[o10 + 12] * w10 + v[o01 + 12] * w01 + v[o11 + 12] * w11
        out.lakeBlock = v[o00 + 13] * w00 + v[o10 + 13] * w10 + v[o01 + 13] * w01 + v[o11 + 13] * w11
        out.frostR = frostR; out.frostG = frostG; out.frostB = frostB
    }

    companion object {
        private const val F = 14

        /** Lattice cells per unit of face coordinate at [lvl]: 32·2^lvl / 2. */
        fun cellsPerUnit(lvl: Int): Double = 16.0 * (1 shl lvl)
    }
}
