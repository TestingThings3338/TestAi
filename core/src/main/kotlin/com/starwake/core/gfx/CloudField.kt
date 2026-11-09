package com.starwake.core.gfx

import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The cloud deck of the worlds with air, as numbers (DECISIONS 17a: clouds are a procedural layer
 * of their own): one weather map for the whole sphere and a small tiling 3-D noise for the detail
 * a map cannot hold. The planet impostor and the terrain shade the same deck from these
 * ([CloudShaders]), so the clouds one sees from the station are the clouds one later flies through,
 * and the game samples the same field on the CPU ([value]) for the fog inside the deck.
 *
 * - [map]: equirect, [MAP_WIDTH] × [MAP_HEIGHT], one byte per texel (row 0 = south pole, u = 0 at
 *   longitude −π about +Y). A domain-warped fractal with a few cyclones wound into it and the
 *   climate's latitude bands (cloudy tropics and storm tracks, clear subtropics).
 * - [noise]: [NOISE_SIZE]³ × 2 bytes, tiling. R: billows, used at a period of [BILLOW_PERIOD]
 *   metres; G: puffs, at [PUFF_PERIOD].
 *
 * The field value at a point is the map plus three octaves of the noise (banks, billows, puffs:
 * [BANK_GAIN], [BILLOW_GAIN], [PUFF_GAIN]); a deck of cover c is opaque where it exceeds
 * [threshold] (c). Built once on background threads
 * ([start]); deterministic.
 */
class CloudField {
    val map = ByteArray(MAP_WIDTH * MAP_HEIGHT)
    val noise = ByteArray(NOISE_SIZE * NOISE_SIZE * NOISE_SIZE * 2)

    /** True once [map] and [noise] are complete (the renderer uploads them then). */
    @Volatile var ready = false
        private set

    private var started = false

    /** Builds the field on background threads (once). */
    fun start() {
        if (started) return
        started = true
        val t = Thread({ build(BUILD_THREADS) }, "cloud-field")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY - 1
        t.start()
    }

    /** Builds the field on the calling thread and [threads] − 1 helpers (tests, the harness). */
    fun build(threads: Int = 1) {
        if (ready) return
        val n = threads.coerceIn(1, 8)
        val done = CountDownLatch(n - 1)
        for (k in 1 until n) {
            val t = Thread({
                try {
                    buildMapRows(k, n)
                } finally {
                    done.countDown()
                }
            }, "cloud-field-$k")
            t.isDaemon = true
            t.priority = Thread.NORM_PRIORITY - 1
            t.start()
        }
        buildNoise()
        buildMapRows(0, n)
        done.await()
        started = true
        ready = true
    }

    // ------------------------------------------------------------------ the weather map

    private fun buildMapRows(first: Int, step: Int) {
        var y = first
        val p = FloatArray(3)
        while (y < MAP_HEIGHT) {
            val lat = ((y + 0.5) / MAP_HEIGHT - 0.5) * PI
            val cl = cos(lat).toFloat()
            val sl = sin(lat).toFloat()
            for (x in 0 until MAP_WIDTH) {
                val lon = ((x + 0.5) / MAP_WIDTH - 0.5) * 2.0 * PI
                p[0] = cl * cos(lon).toFloat()
                p[1] = sl
                p[2] = cl * sin(lon).toFloat()
                map[y * MAP_WIDTH + x] = (weather(p, lat.toFloat()).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
            y += step
        }
    }

    /** The weather at unit direction [p] (overwritten), latitude [lat]: about 0.5 ± 0.2. */
    private fun weather(p: FloatArray, lat: Float): Float {
        // cyclones: the neighbourhood of each centre is turned about it, most at the centre
        for (c in CYCLONES) {
            val dx = p[0] - c[0]
            val dy = p[1] - c[1]
            val dz = p[2] - c[2]
            val d2 = dx * dx + dy * dy + dz * dz
            val r2 = c[3] * c[3]
            if (d2 > r2 * 9f) continue
            rotate(p, c[0], c[1], c[2], c[4] * exp(-d2 / r2))
        }
        val x = p[0]
        val y = p[1]
        val z = p[2]
        val wx = fbm(x * 1.9f + 11.3f, y * 1.9f + 4.1f, z * 1.9f + 7.7f, 3) - 0.5f
        val wy = fbm(x * 1.9f + 3.9f, y * 1.9f + 17.2f, z * 1.9f + 1.3f, 3) - 0.5f
        val wz = fbm(x * 1.9f + 8.6f, y * 1.9f + 2.4f, z * 1.9f + 13.9f, 3) - 0.5f
        // streets along the parallels in the trade winds: the field is stretched east-west
        val base = fbm(x * 3.1f + wx * 1.7f, y * 4.6f + wy * 1.7f, z * 3.1f + wz * 1.7f, 5)
        val fine = fbm(x * 11f + wx * 3.2f + 5.5f, y * 13f + wy * 3.2f, z * 11f + wz * 3.2f, 4)
        val bands = 0.085f * cos(lat * 6f) + 0.03f * cos(lat * 2f)
        val v = base * 0.72f + fine * 0.28f + bands
        return (v - 0.5f) * 2.1f + 0.5f
    }

    /** Rotates [p] about the unit axis (ax, ay, az) by [angle] radians (Rodrigues). */
    private fun rotate(p: FloatArray, ax: Float, ay: Float, az: Float, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        val d = (ax * p[0] + ay * p[1] + az * p[2]) * (1f - c)
        val cx = ay * p[2] - az * p[1]
        val cy = az * p[0] - ax * p[2]
        val cz = ax * p[1] - ay * p[0]
        p[0] = p[0] * c + cx * s + ax * d
        p[1] = p[1] * c + cy * s + ay * d
        p[2] = p[2] * c + cz * s + az * d
    }

    // ------------------------------------------------------------------ the detail noise

    private fun buildNoise() {
        val n = NOISE_SIZE
        var o = 0
        for (z in 0 until n) for (y in 0 until n) for (x in 0 until n) {
            val fx = (x + 0.5f) / n
            val fy = (y + 0.5f) / n
            val fz = (z + 0.5f) / n
            // billows: three octaves from 3 cycles per tile
            val b = tile(fx, fy, fz, 3) * 0.55f + tile(fx, fy, fz, 6) * 0.30f + tile(fx, fy, fz, 12) * 0.15f
            // puffs: rounded cells (inverted distance to the nearest of 4³ points) with finer noise on top
            val w = 1f - worley(fx, fy, fz, 4)
            val g = w * 0.62f + tile(fx, fy, fz, 8) * 0.24f + tile(fx, fy, fz, 16) * 0.14f
            noise[o++] = (((b - 0.5f) * 1.9f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            noise[o++] = (((g - 0.5f) * 1.7f + 0.5f).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
        }
    }

    // ------------------------------------------------------------------ CPU sampling

    /**
     * The field at the point of unit direction (nx, ny, nz) and position (qx, qy, qz) metres, both
     * in the deck's own frame: what the shaders compute there at full detail. 0.5 before [ready].
     */
    fun value(nx: Double, ny: Double, nz: Double, qx: Double, qy: Double, qz: Double): Float {
        if (!ready) return 0.5f
        val lon = if (kotlin.math.abs(nx) + kotlin.math.abs(nz) < 1e-9) 0.0 else atan2(nz, nx)
        val u = (lon / (2.0 * PI) + 0.5) * MAP_WIDTH - 0.5
        val v = (asin(ny.coerceIn(-1.0, 1.0)) / PI + 0.5) * MAP_HEIGHT - 0.5
        val x0 = floor(u).toInt()
        val y0 = floor(v).toInt()
        val fx = (u - x0).toFloat()
        val fy = (v - y0).toFloat()
        val m = lerp(lerp(mapAt(x0, y0), mapAt(x0 + 1, y0), fx), lerp(mapAt(x0, y0 + 1), mapAt(x0 + 1, y0 + 1), fx), fy)
        val d0 = noiseAt(
            (BANK[0] * qx + BANK[1] * qy + BANK[2] * qz) / BANK_PERIOD,
            (BANK[3] * qx + BANK[4] * qy + BANK[5] * qz) / BANK_PERIOD,
            (BANK[6] * qx + BANK[7] * qy + BANK[8] * qz) / BANK_PERIOD, 0,
        )
        val d1 = noiseAt(qx / BILLOW_PERIOD, qy / BILLOW_PERIOD, qz / BILLOW_PERIOD, 0)
        val d2 = noiseAt(qx / PUFF_PERIOD, qy / PUFF_PERIOD, qz / PUFF_PERIOD, 1)
        return m + (d0 - 0.5f) * BANK_GAIN + (d1 - 0.5f) * BILLOW_GAIN + (d2 - 0.5f) * PUFF_GAIN
    }

    private fun mapAt(x: Int, y: Int): Float {
        val xx = Math.floorMod(x, MAP_WIDTH)
        val yy = y.coerceIn(0, MAP_HEIGHT - 1)
        return (map[yy * MAP_WIDTH + xx].toInt() and 255) / 255f
    }

    /** Trilinear sample of channel [ch] of the tiling noise at tile coordinates (x, y, z). */
    private fun noiseAt(x: Double, y: Double, z: Double, ch: Int): Float {
        val n = NOISE_SIZE
        val px = (x - floor(x)) * n - 0.5
        val py = (y - floor(y)) * n - 0.5
        val pz = (z - floor(z)) * n - 0.5
        val x0 = floor(px).toInt()
        val y0 = floor(py).toInt()
        val z0 = floor(pz).toInt()
        val fx = (px - x0).toFloat()
        val fy = (py - y0).toFloat()
        val fz = (pz - z0).toFloat()
        fun at(i: Int, j: Int, k: Int): Float =
            (noise[((Math.floorMod(k, n) * n + Math.floorMod(j, n)) * n + Math.floorMod(i, n)) * 2 + ch].toInt() and 255) / 255f
        val a = lerp(lerp(at(x0, y0, z0), at(x0 + 1, y0, z0), fx), lerp(at(x0, y0 + 1, z0), at(x0 + 1, y0 + 1, z0), fx), fy)
        val b = lerp(lerp(at(x0, y0, z0 + 1), at(x0 + 1, y0, z0 + 1), fx), lerp(at(x0, y0 + 1, z0 + 1), at(x0 + 1, y0 + 1, z0 + 1), fx), fy)
        return lerp(a, b, fz)
    }

    /** The image as a direct buffer for the upload. */
    fun mapBuffer(): ByteBuffer = Buffers.of(map)

    fun noiseBuffer(): ByteBuffer = Buffers.of(noise)

    companion object {
        const val MAP_WIDTH = 1024
        const val MAP_HEIGHT = 512
        const val NOISE_SIZE = 64

        /** Metres one tile of the noise spans for the billows (R) and for the puffs (G); the first is a multiple of the second. */
        const val BILLOW_PERIOD = 48_000.0
        const val PUFF_PERIOD = 6_000.0
        const val BILLOW_GAIN = 0.22f
        const val PUFF_GAIN = 0.16f

        /**
         * A third octave from the billow channel: cloud banks, at a period of [BANK_PERIOD] metres
         * along axes turned by [BANK] (a rotation, row-major) against the other two. The periods
         * have no common multiple in sight and the lattices do not line up, so no tile repeats
         * across a view from orbit.
         */
        const val BANK_PERIOD = 131_000.0
        const val BANK_GAIN = 0.30f
        val BANK = doubleArrayOf(
            0.8138, -0.4698, 0.3420,
            0.5438, 0.8232, -0.1632,
            -0.2049, 0.3188, 0.9254,
        )

        /** The deck fades in from [threshold] − [EDGE_LOW] to [threshold] + [EDGE_HIGH]. */
        const val EDGE_LOW = 0.05f
        const val EDGE_HIGH = 0.17f

        /** The deck drifts about the body's axis at this rate (rad/s): about 12 m/s on the Earth's equator. */
        const val DRIFT = 1.9e-6

        private const val BUILD_THREADS = 3

        /**
         * The field value above which a deck of cover [cover] (0..1, the surface profile's) is
         * cloud. The field is about normal around 0.5; the curve runs through a fifth of the sky
         * at cover 0.2 (Mars), broken cloud with clear stretches at 0.56 (the Earth) and a closed
         * deck at 0.92 (Venus).
         */
        fun threshold(cover: Float): Float {
            val c = cover.coerceIn(0f, 1f)
            var i = 0
            while (i < COVER.size - 4 && c > COVER[i + 2]) i += 2
            val t = (c - COVER[i]) / (COVER[i + 2] - COVER[i])
            return COVER[i + 1] + (COVER[i + 3] - COVER[i + 1]) * t
        }

        /** (cover, threshold) pairs [threshold] runs through. */
        private val COVER = floatArrayOf(0f, 0.95f, 0.2f, 0.80f, 0.56f, 0.62f, 0.92f, 0.05f, 1f, -0.2f)

        /** Opacity of the deck where the field is [v], for the threshold [thr] (the shaders' `cloudAlpha`). */
        fun alpha(v: Float, thr: Float): Float {
            val t = ((v - (thr - EDGE_LOW)) / (EDGE_LOW + EDGE_HIGH)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Cyclone centres (unit x, y, z), radius (chord) and turn at the centre (rad; the sign is the hemisphere's). */
        private val CYCLONES: Array<FloatArray> = run {
            val out = ArrayList<FloatArray>()
            var s = 0x2545F491
            fun rnd(): Float {
                s = s xor (s shl 13)
                s = s xor (s ushr 17)
                s = s xor (s shl 5)
                return (s ushr 8) / 16777216f
            }
            for (i in 0 until 14) {
                val north = i % 2 == 0
                // storm tracks at 35..62° and a few tropical ones at 12..24°
                val latDeg = if (i < 10) 35f + rnd() * 27f else 12f + rnd() * 12f
                val lat = latDeg * (PI.toFloat() / 180f) * (if (north) 1f else -1f)
                val lon = rnd() * 2f * PI.toFloat()
                val r = if (i < 10) 0.16f + rnd() * 0.16f else 0.07f + rnd() * 0.06f
                val turn = (2.2f + rnd() * 2.2f) * (if (north) 1f else -1f)
                out += floatArrayOf(cos(lat) * cos(lon), sin(lat), cos(lat) * sin(lon), r, turn)
            }
            out.toTypedArray()
        }

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        private fun hash(x: Int, y: Int, z: Int): Float {
            var h = x * 0x8DA6B343.toInt() + y * 0xD8163841.toInt() + z * 0xCB1AB31F.toInt()
            h = (h xor (h ushr 15)) * 0x2C1B3C6D
            h = (h xor (h ushr 12)) * 0x297A2D39
            h = h xor (h ushr 15)
            return (h ushr 8) / 16777216f
        }

        /** Value noise, about 0..1. */
        fun vnoise(x: Float, y: Float, z: Float): Float {
            val fx = floor(x)
            val fy = floor(y)
            val fz = floor(z)
            val ix = fx.toInt()
            val iy = fy.toInt()
            val iz = fz.toInt()
            var u = x - fx
            var v = y - fy
            var w = z - fz
            u = u * u * (3f - 2f * u)
            v = v * v * (3f - 2f * v)
            w = w * w * (3f - 2f * w)
            val a = lerp(lerp(hash(ix, iy, iz), hash(ix + 1, iy, iz), u), lerp(hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz), u), v)
            val b = lerp(lerp(hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1), u), lerp(hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1), u), v)
            return lerp(a, b, w)
        }

        fun fbm(x: Float, y: Float, z: Float, octaves: Int): Float {
            var s = 0f
            var a = 0.5f
            var norm = 0f
            var px = x
            var py = y
            var pz = z
            for (i in 0 until octaves) {
                s += vnoise(px, py, pz) * a
                norm += a
                px = px * 2.03f + 17.1f
                py = py * 2.03f + 9.3f
                pz = pz * 2.03f + 4.7f
                a *= 0.5f
            }
            return s / norm
        }

        /** Value noise of [cells] cells per unit that tiles with period 1. */
        private fun tile(x: Float, y: Float, z: Float, cells: Int): Float {
            val px = x * cells
            val py = y * cells
            val pz = z * cells
            val fx = floor(px)
            val fy = floor(py)
            val fz = floor(pz)
            val ix = fx.toInt()
            val iy = fy.toInt()
            val iz = fz.toInt()
            var u = px - fx
            var v = py - fy
            var w = pz - fz
            u = u * u * (3f - 2f * u)
            v = v * v * (3f - 2f * v)
            w = w * w * (3f - 2f * w)
            fun h(i: Int, j: Int, k: Int) = hash(Math.floorMod(i, cells) + cells * 131, Math.floorMod(j, cells), Math.floorMod(k, cells))
            val a = lerp(lerp(h(ix, iy, iz), h(ix + 1, iy, iz), u), lerp(h(ix, iy + 1, iz), h(ix + 1, iy + 1, iz), u), v)
            val b = lerp(lerp(h(ix, iy, iz + 1), h(ix + 1, iy, iz + 1), u), lerp(h(ix, iy + 1, iz + 1), h(ix + 1, iy + 1, iz + 1), u), v)
            return lerp(a, b, w)
        }

        /** Distance (0..1, in cells, clamped) to the nearest feature point of a tiling grid of [cells]³ cells. */
        private fun worley(x: Float, y: Float, z: Float, cells: Int): Float {
            val px = x * cells
            val py = y * cells
            val pz = z * cells
            val ix = floor(px).toInt()
            val iy = floor(py).toInt()
            val iz = floor(pz).toInt()
            var best = 9f
            for (k in -1..1) for (j in -1..1) for (i in -1..1) {
                val cx = ix + i
                val cy = iy + j
                val cz = iz + k
                val mx = Math.floorMod(cx, cells)
                val my = Math.floorMod(cy, cells)
                val mz = Math.floorMod(cz, cells)
                val dx = cx + hash(mx + 977, my, mz) - px
                val dy = cy + hash(mx, my + 977, mz) - py
                val dz = cz + hash(mx, my, mz + 977) - pz
                val d = dx * dx + dy * dy + dz * dz
                if (d < best) best = d
            }
            return sqrt(best).coerceAtMost(1f)
        }
    }
}

/**
 * The GPU side of a [CloudField]: the weather map (R8, mipmapped) and the detail noise (RG8 3-D,
 * mipmapped, repeating). Until the field is built both are single neutral texels, under which no
 * deck shows ([upload] is tried every frame and takes effect once).
 */
class CloudTextures(private val gl: Gl, val field: CloudField) {
    val map: Int = gl.genTexture()
    val noise: Int = gl.genTexture()

    /** True once the field's data is on the GPU. */
    var uploaded = false
        private set

    /** Session time (s) the deck became visible at: it fades in over [FADE_SECONDS] ([fade]). */
    private var shownAt = -1f

    init {
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(GL.TEXTURE_2D, map)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.R8, 1, 1, GL.RED, GL.UNSIGNED_BYTE, Buffers.of(byteArrayOf(0)))
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.bindTexture(GL.TEXTURE_3D, noise)
        gl.texImage3D(GL.TEXTURE_3D, 0, GL.RG8, 1, 1, 1, GL.RG, GL.UNSIGNED_BYTE, Buffers.of(byteArrayOf(127, 127)))
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_MIN_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.bindTexture(GL.TEXTURE_3D, 0)
        upload()
    }

    /** Uploads the field once it is built. GL thread; leaves texture bindings of unit 0 changed. */
    fun upload() {
        if (uploaded || !field.ready) return
        uploaded = true
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.bindTexture(GL.TEXTURE_2D, map)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.R8, CloudField.MAP_WIDTH, CloudField.MAP_HEIGHT, GL.RED, GL.UNSIGNED_BYTE, field.mapBuffer())
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.generateMipmap(GL.TEXTURE_2D)
        val n = CloudField.NOISE_SIZE
        gl.bindTexture(GL.TEXTURE_3D, noise)
        gl.texImage3D(GL.TEXTURE_3D, 0, GL.RG8, n, n, n, GL.RG, GL.UNSIGNED_BYTE, field.noiseBuffer())
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_WRAP_S, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_WRAP_T, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_3D, GL.TEXTURE_WRAP_R, GL.REPEAT)
        gl.generateMipmap(GL.TEXTURE_3D)
        gl.bindTexture(GL.TEXTURE_3D, 0)
    }

    /** Opacity of every deck at [time]: 0 until the field is on the GPU, then rising to 1 over [FADE_SECONDS]. */
    fun fade(time: Float): Float {
        if (!uploaded) return 0f
        if (shownAt < 0f || time < shownAt) shownAt = time
        return ((time - shownAt) / FADE_SECONDS).coerceIn(0f, 1f)
    }

    /** The decks show at once (a renderer rebuilt while they were already visible). */
    fun showNow() {
        shownAt = -1e9f
    }

    fun dispose() {
        gl.deleteTexture(map)
        gl.deleteTexture(noise)
    }

    companion object {
        const val FADE_SECONDS = 1.5f
    }
}

/** The uniforms of one body's cloud deck for one frame, as both shaders take them ([CloudShaders]). */
class CloudDeckState {
    /** False: the body has no deck (the shaders skip it). */
    var on = false

    /** The field threshold of the deck's cover ([CloudField.threshold]). */
    var threshold = 2f

    /** How much a cloud takes from the sunlight on the ground under it, 0..1. */
    var shadow = 0f

    /** Radius of the deck (m from the body's centre) and its height above the body's radius. */
    var radius = 0.0
    var height = 0.0

    /** Colour (linear) and opacity of the whole deck. */
    val colour = floatArrayOf(1f, 1f, 1f)
    var fade = 1f

    /** The deck's turn about the body's axis (rad): [CloudField.DRIFT] × time. */
    var angle = 0.0

    /** Fog around the camera inside the deck, 0..1. */
    var fog = 0f

    fun off() {
        on = false
        threshold = 2f
        fog = 0f
    }
}
