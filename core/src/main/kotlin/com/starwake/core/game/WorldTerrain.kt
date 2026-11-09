package com.starwake.core.game

import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import com.starwake.core.terrain.FieldScratch
import com.starwake.core.terrain.PlanetSurface
import com.starwake.core.terrain.StyleCatalog
import com.starwake.core.terrain.SurfaceField
import com.starwake.core.terrain.SurfaceProfile
import com.starwake.core.terrain.SurfaceProfiles
import com.starwake.core.terrain.SurfaceSample
import com.starwake.core.terrain.TerrainPlanet
import com.starwake.core.terrain.TileWorkerPool
import com.starwake.core.terrain.VertexColour
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The real ground of the system's solid bodies, for the flight (a session without one flies over smooth spheres). */
interface Ground {
    /** The exact ground queries of body [body], or null while it has no terrain (far away, or a smooth sphere). */
    fun surface(body: Int): PlanetSurface?

    /** The ship is at [shipPos]: makes the solid body it is nearest to the terrain body. True when that changed. */
    fun select(shipPos: DVec3): Boolean
}

/**
 * The seamless terrain of the star system (04-planet-surface.md 9): of all solid bodies, the one
 * the ship is nearest to (within [ACTIVATE_RADII] of its centre) is the terrain body and owns a
 * [TerrainPlanet]; the others are impostors. Game thread, no GL: the game attaches the planet to
 * its renderer when [generation] changes and draws it through `SceneFrame.terrain`.
 *
 * It also bakes the far look of every solid body from that body's own terrain functions
 * ([looks], DECISIONS 17a), so a planet seen from the other side of the system already shows the
 * continents one later lands on.
 */
class WorldTerrain(val system: StarSystem, quality: Int) : Ground {
    var quality = quality
        private set
    private var pool = TileWorkerPool(TileWorkerPool.threadsFor(quality), TileWorkerPool.stagingFor(quality))

    /** The terrain body's planet, or null (deep space, the menu). */
    var planet: TerrainPlanet? = null
        private set

    /** Index of the terrain body in [StarSystem.bodies], -1 without one. */
    var body = -1
        private set

    /** Changes whenever [planet] does: the renderer side attaches the new one (and frees the old). */
    var generation = 0
        private set

    val looks = BodyLooks(system)

    /** The surface profile (sky, clouds, entry glow) of body [i]; null for a body without terrain. */
    fun profile(i: Int): SurfaceProfile? = profiles[i]

    private val profiles: Array<SurfaceProfile?> = Array(system.bodies.size) { i ->
        val b = system.bodies[i]
        if (b.hasTerrain) profileOf(system, b) else null
    }

    override fun surface(body: Int): PlanetSurface? = if (body == this.body) planet?.surface else null

    /**
     * Chooses the terrain body for a ship at [shipPos]: the solid body with the least altitude in
     * its own radii, once within [ACTIVATE_RADII] of its centre, kept until [RELEASE_RADII] or
     * until another one is nearer. Returns true when the terrain body changed.
     */
    override fun select(shipPos: DVec3): Boolean {
        var best = -1
        var bestScore = Double.MAX_VALUE
        val bodies = system.bodies
        for (i in bodies.indices) {
            val b = bodies[i]
            if (!b.hasTerrain) continue
            val dx = shipPos.x - b.pos.x
            val dy = shipPos.y - b.pos.y
            val dz = shipPos.z - b.pos.z
            val score = sqrt(dx * dx + dy * dy + dz * dz) / b.radius
            if (score < bestScore) {
                bestScore = score
                best = i
            }
        }
        val want = when {
            best < 0 -> -1
            best == body -> if (bestScore < RELEASE_RADII) best else -1
            else -> if (bestScore < ACTIVATE_RADII) best else if (body >= 0 && stillInRange(shipPos)) body else -1
        }
        if (want == body) return false
        activate(want)
        return true
    }

    private fun stillInRange(shipPos: DVec3): Boolean {
        val b = system.bodies[body]
        val dx = shipPos.x - b.pos.x
        val dy = shipPos.y - b.pos.y
        val dz = shipPos.z - b.pos.z
        return sqrt(dx * dx + dy * dy + dz * dz) < RELEASE_RADII * b.radius
    }

    private fun activate(i: Int) {
        planet?.release()
        planet = null
        body = i
        generation++
        if (i < 0) {
            looks.setDetailBody(-1, null)
            return
        }
        val b = system.bodies[i]
        val p = TerrainPlanet(b.name, b.radius, profiles[i]!!, quality, pool)
        p.surface.gravityG = b.gravityG
        p.setBodyPose(b.pos, b.rot)
        planet = p
        looks.setDetailBody(i, p.field)
    }

    /**
     * One frame of the terrain body (selection of the cut, tile jobs; no GL): see
     * [TerrainPlanet.update]. [frameMs] is the last frame's time for the detail controller.
     */
    fun update(cameraPos: DVec3, dt: Double, shipPos: DVec3?, shipVel: Vec3?, frameMs: Float) {
        planet?.update(cameraPos, dt, shipPos, shipVel, frameMs)
    }

    /** A new quality level: the terrain body is rebuilt with its tile budget (the renderer side re-attaches). */
    fun setQuality(q: Int) {
        if (q == quality) return
        quality = q
        val current = body
        planet?.release()
        planet = null
        pool.shutdown()
        pool = TileWorkerPool(TileWorkerPool.threadsFor(q), TileWorkerPool.stagingFor(q))
        looks.quality = q
        body = -1
        if (current >= 0) activate(current) else generation++
    }

    /** Drops the terrain body (back in the menu). */
    fun clear() {
        if (body >= 0) activate(-1)
    }

    /** True when the terrain body has every tile it wants (harness: a shot shows the finished ground). */
    fun idle(): Boolean {
        val p = planet ?: return true
        return p.ready && p.tree.wantedCount == 0 && pool.idle()
    }

    /** Ends the terrain: the terrain body is dropped and the workers stop (tests; the game keeps it for its lifetime). */
    fun shutdown() {
        planet?.release()
        planet = null
        body = -1
        generation++
        pool.shutdown()
    }

    fun pause() = pool.pause()

    fun resume() = pool.resume()

    companion object {
        /** A solid body becomes the terrain body within this many radii of its centre... */
        const val ACTIVATE_RADII = 14.0

        /** ...and stays it out to this many. */
        const val RELEASE_RADII = 20.0

        /** The surface profile of [b]: the Sol profiles by name in Sol, else by class and seed. */
        fun profileOf(system: StarSystem, b: StarSystem.Body): SurfaceProfile {
            // the profiles and styles are keyed by the Roblox names ("Earth"); the system shows them in capitals
            val name = b.name.lowercase().replaceFirstChar { it.uppercase() }
            val sol = system.layout == StarSystem.Layout.SOL
            return SurfaceProfiles.forBody(name, b.bodyClass, b.seed, if (sol) "Sol" else system.name, b.airDensity > 0.0)
        }
    }
}

/**
 * The far look of the solid bodies: equirect images of each body's terrain colours (the impostor's
 * surface image and the terrain's own far look, DECISIONS 17a), baked on background threads from
 * the same functions the tiles use. Every solid body gets a small image, nearest to the start
 * first; the terrain body a detailed one. The game takes finished images with [poll] on the GL
 * thread and uploads them; [images] keeps the best of each for a rebuilt renderer.
 */
class BodyLooks(private val system: StarSystem) {
    class Image(val body: Int, val pixels: ByteBuffer, val width: Int, val height: Int, val detailed: Boolean)

    @Volatile var quality = 2

    /** The best image of each body so far (null: only the type's generic look). GL thread. */
    val images = arrayOfNulls<Image>(system.bodies.size)

    private val small = arrayOfNulls<Image>(system.bodies.size)
    private val finished = ConcurrentLinkedQueue<Image>()
    private val jobs = LinkedBlockingQueue<Job>()

    @Volatile private var detailBody = -1

    @Volatile private var started = false

    private class Job(val body: Int, val field: SurfaceField?, val detailed: Boolean)

    /** Starts the small bakes of every solid body, nearest to [from] first (once; a no-op in a system without terrain). */
    fun start(from: DVec3) {
        if (started) return
        started = true
        val order = system.bodies.indices.filter { system.bodies[it].hasTerrain }.sortedBy {
            val p = system.bodies[it].pos
            val dx = p.x - from.x
            val dy = p.y - from.y
            val dz = p.z - from.z
            dx * dx + dy * dy + dz * dz
        }
        if (order.isEmpty()) return
        for (i in order) jobs.add(Job(i, null, false))
        val t = Thread({ run() }, "body-looks")
        t.isDaemon = true
        t.priority = Thread.NORM_PRIORITY - 1
        t.start()
    }

    /**
     * The terrain body changed: [body] (with its planet's height [field]) gets the detailed image;
     * the previous one falls back to its small image, which frees the large one.
     */
    fun setDetailBody(body: Int, field: SurfaceField?) {
        val old = detailBody
        detailBody = body
        if (old >= 0 && old != body) {
            val s = small[old]
            if (s != null && images[old]?.detailed == true) finished.add(s)
        }
        if (body >= 0 && field != null) {
            if (!started) {
                started = true
                val t = Thread({ run() }, "body-looks")
                t.isDaemon = true
                t.priority = Thread.NORM_PRIORITY - 1
                t.start()
            }
            jobs.add(Job(body, field, true))
        }
    }

    /** True once [body] has its detailed image (the game holds the loading curtain for it, briefly). */
    fun detailed(body: Int): Boolean = body >= 0 && images[body]?.detailed == true

    /** True once [body] has any image of its own. */
    fun ready(body: Int): Boolean = body >= 0 && images[body] != null

    /** The next finished image to upload, or null. GL thread; the image is then in [images]. */
    fun poll(): Image? {
        while (true) {
            val img = finished.poll() ?: return null
            // a detailed image of a body that is no longer the terrain body is dropped
            if (img.detailed && img.body != detailBody) continue
            // a small image never replaces the detailed one of the terrain body
            if (!img.detailed) small[img.body] = img
            if (!img.detailed && images[img.body]?.detailed == true && img.body == detailBody) continue
            images[img.body] = img
            return img
        }
    }

    private fun run() {
        while (true) {
            val job = jobs.take()
            try {
                if (job.detailed && job.body != detailBody) continue
                val b = system.bodies[job.body]
                val field = job.field ?: run {
                    val profile = WorldTerrain.profileOf(system, b)
                    val style = StyleCatalog.chooseStyle(profile) ?: return@run null
                    SurfaceField.create(b.radius, style, profile.seed, profile.bodyClass)
                } ?: continue
                val w = if (!job.detailed) SMALL_WIDTH else if (quality >= 3) 4096 else if (quality == 2) 2048 else 1024
                val pixels = bake(field, w, w / 2, if (job.detailed) BAKE_THREADS else 1)
                finished.add(Image(job.body, pixels, w, w / 2, job.detailed))
            } catch (t: Throwable) {
                // a failed bake leaves the body with its generic look
                System.err.println("body look of ${system.bodies[job.body].name} failed: $t")
            }
        }
    }

    companion object {
        const val SMALL_WIDTH = 512
        private const val BAKE_THREADS = 3

        /**
         * The terrain colours of [field] as an equirect image (BodyRenderer's bake format: RGBA8,
         * sqrt-encoded albedo = the terrain's sRGB colour, A = 0; row 0 = south pole, u = 0 at
         * longitude −π about +Y), at a band limit of one texel, the rows shared between [threads].
         */
        fun bake(field: SurfaceField, width: Int, height: Int, threads: Int = 1): ByteBuffer {
            field.buildMeanColours()
            val out = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            val n = threads.coerceIn(1, 8)
            if (n == 1) {
                bakeRows(field, width, height, 0, 1, out)
            } else {
                val done = CountDownLatch(n)
                for (k in 0 until n) {
                    val t = Thread({
                        try {
                            bakeRows(field, width, height, k, n, out)
                        } finally {
                            done.countDown()
                        }
                    }, "body-look-$k")
                    t.isDaemon = true
                    t.priority = Thread.NORM_PRIORITY - 1
                    t.start()
                }
                done.await()
            }
            out.position(0)
            return out
        }

        /** Rows [first], first + step, ... of the image (absolute puts: threads share [out]). */
        private fun bakeRows(field: SurfaceField, width: Int, height: Int, first: Int, step: Int, out: ByteBuffer) {
            val fs = FieldScratch()
            val sample = SurfaceSample()
            val col = VertexColour()
            val radius = field.radius
            val spacing = PI * radius / height
            var y = first
            while (y < height) {
                val lat = ((y + 0.5) / height - 0.5) * PI
                val cl = cos(lat)
                val sl = sin(lat)
                for (x in 0 until width) {
                    val lon = ((x + 0.5) / width - 0.5) * 2.0 * PI
                    val nx = cl * cos(lon)
                    val nz = cl * sin(lon)
                    val px = nx * radius
                    val py = sl * radius
                    val pz = nz * radius
                    field.collect(px - spacing, py - spacing, pz - spacing, px + spacing, py + spacing, pz + spacing, spacing, fs)
                    field.sample(nx, sl, nz, spacing, fs, sample)
                    field.colour(sample, 0.0, spacing, fs, col)
                    val o = (y * width + x) * 4
                    out.put(o, (col.r.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                    out.put(o + 1, (col.g.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                    out.put(o + 2, (col.b.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                    out.put(o + 3, 0)
                }
                y += step
            }
        }
    }
}
