package com.starwake.core.terrain

import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Uploads a finished tile into a GPU slot (the renderer; a no-op in headless tests). */
fun interface TileUploader {
    fun upload(slot: Int, staging: TileStaging)
}

/** Quality settings of the terrain (§9.7). */
class TerrainQuality(
    /** Finest vertex spacing (m): HIGH 2.5, LOW 5 (the Roblox voxels are 4 m). */
    val minSpacing: Double,
    /** Split distance in tile edges. */
    val rho: Double,
    /** GPU tile slots (29.3 KB each). */
    val slots: Int,
    /** Tile uploads per frame. */
    val uploadsPerFrame: Int,
) {
    companion object {
        fun forLevel(quality: Int): TerrainQuality = when {
            // ULTRA: full detail half as far again, for GPUs with frame time to spare
            quality >= 3 -> TerrainQuality(2.5, 3.2, 2400, 10)
            quality == 2 -> TerrainQuality(2.5, 2.5, 1600, 8)
            quality == 1 -> TerrainQuality(2.5, 2.25, 1200, 6)
            else -> TerrainQuality(5.0, 2.0, 900, 4)
        }
    }
}

/**
 * One landable body's seamless terrain (§9): owns the height function ([field]), the quadtree
 * ([tree]), the ground queries ([surface]) and its share of the worker pool. The game owns one per
 * terrain body and calls, on the game thread:
 *
 * - [setBodyPose] when the body moves (planets never spin, DECISIONS 7);
 * - [update] once per frame (selection, priorities, job submission, cache eviction; no GL);
 * - the render pass: `SceneRenderer` with `SceneFrame.terrain` set does it (`TerrainRenderer.prepare`
 *   uploads ≤ [TerrainQuality.uploadsPerFrame] finished tiles and culls, `drawFar` draws the far
 *   depth slices before the near pass, `drawNear` the nearest slice inside it); standalone,
 *   `prepare` + `drawSlices`, or [processCompletions] with an uploader of one's own;
 * - the queries on [surface] (any number per frame, exact, independent of streaming except
 *   [PlanetSurface.groundState]);
 * - [frameBudgetMs] when the target frame rate changes;
 * - [onGpuLost] when the renderer is rebuilt (GL context loss), then attach again;
 * - [release] when the body stops being the terrain body.
 *
 * The impostor (BodyRenderer) may stop shading this body's surface once [surfaceReady] is true:
 * then the six roots are resident and the cut covers the whole sphere.
 */
class TerrainPlanet(
    val name: String,
    val radius: Double,
    val profile: SurfaceProfile,
    quality: Int,
    val pool: TileWorkerPool,
    styleOverride: TerrainStyle? = null,
    nodeCapacity: Int = 16384,
) : TileJobOwner {
    val style: TerrainStyle = styleOverride ?: StyleCatalog.chooseStyle(profile)
        ?: throw IllegalArgumentException("$name has no terrain (a star)")
    val field = SurfaceField.create(radius, style, profile.seed, profile.bodyClass)

    var quality = TerrainQuality.forLevel(quality)
        private set
    val maxLevel = CubeSphere.maxLevel(radius, this.quality.minSpacing)
    val tree = TerrainQuadtree(field, maxLevel, nodeCapacity, this.quality.slots)
    val surface = PlanetSurface(field).also { it.tree = tree }

    init {
        // the planet-scale layer is interpolated on the two finest levels (from the vertex grid of Lmax − 2)
        field.macroLevel = if (maxLevel >= 3) maxLevel - 2 else -1
    }

    private val ownerId = pool.register(this)
    private val builders = arrayOfNulls<TileBuilder>(pool.threads)

    /** Tile origin (body space, double), half extents and morph scale per node, set at upload. */
    val originX = DoubleArray(nodeCapacity)
    val originY = DoubleArray(nodeCapacity)
    val originZ = DoubleArray(nodeCapacity)
    val halfX = FloatArray(nodeCapacity)
    val halfY = FloatArray(nodeCapacity)
    val halfZ = FloatArray(nodeCapacity)
    val morphScale = FloatArray(nodeCapacity)

    /** The camera in body space this frame (m from the centre) and its height above the reference sphere. */
    val camBody = DoubleArray(3)
    var cameraAltitude = 0.0
        private set

    /** Split distance in tile edges after the frame-time controller (§9.3). */
    var rho = this.quality.rho
        private set

    @Volatile private var meanReady = false
    private var clock = 0.0

    // statistics
    var lastUpdateNanos = 0L
        private set
    var lastUploadNanos = 0L
        private set
    var uploadsTotal = 0L
        private set
    var droppedTotal = 0L
        private set
    var lastUploads = 0
        private set

    private val q = DoubleArray(3)
    private val sink = TileSink { st -> accept(st) }
    private var uploader: TileUploader? = null
    private var uploadsThisCall = 0

    init {
        surface.gravityG = 1.0
        // the mean colour table of the style (~0.1–0.5 s): on a background thread, tiles wait for it
        val t = Thread({
            field.buildMeanColours()
            meanReady = true
        }, "terrain-init-$name")
        t.isDaemon = true
        t.start()
    }

    override val jobStates: IntArray get() = tree.jobState
    override val generations: IntArray get() = tree.gen

    /** True once the background initialisation is done (tiles are only requested after it). */
    val ready get() = meanReady

    /** Blocks until [ready] (tests and harness only). */
    fun awaitReady(timeoutMs: Long = 30_000) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!meanReady && System.currentTimeMillis() < end) Thread.sleep(2)
    }

    /** The impostor may skip this body's surface shading: the cut covers the whole sphere. */
    fun surfaceReady(): Boolean {
        if (!meanReady) return false
        for (f in 0 until 6) if (tree.slot[f] < 0 && !coveredByChildren(f)) return false
        return true
    }

    private fun coveredByChildren(n: Int): Boolean {
        val c = tree.child[n]
        if (c < 0) return false
        for (k in 0 until 4) if (tree.slot[c + k] < 0 && !coveredByChildren(c + k)) return false
        return true
    }

    fun setBodyPose(pos: DVec3, rot: Quat) = surface.setPose(pos, rot)

    /**
     * The frame-time budget of the ρ controller (ms): 1000 / the target frame rate, 16.7 at the
     * default 60 fps, 8.3 when 120 Hz is selected (DECISIONS 21a). Set it when the setting changes.
     */
    var frameBudgetMs = DEFAULT_BUDGET_MS

    /**
     * One frame (game thread, no GL, no allocation): the camera at [cameraPos] (world), [dt]
     * seconds; the ship's position and velocity (world, m/s) shape the priorities (path first
     * below 1,500 m above the ground, the focus ahead); [frameMs] (the last frame's time, 0 =
     * unknown) drives the ρ controller against [frameBudgetMs].
     */
    fun update(cameraPos: DVec3, dt: Double, shipPos: DVec3? = null, shipVel: Vec3? = null, frameMs: Float = 0f) {
        val t0 = System.nanoTime()
        clock += dt
        surface.toBody(cameraPos.x, cameraPos.y, cameraPos.z, camBody)
        val cr = sqrt(camBody[0] * camBody[0] + camBody[1] * camBody[1] + camBody[2] * camBody[2])
        cameraAltitude = cr - radius
        controlRho(dt, frameMs)
        tree.rho = rho
        tree.slotReserve = quality.uploadsPerFrame * 2 + 4
        setFocus(shipPos, shipVel)
        tree.update(camBody[0], camBody[1], camBody[2], clock)
        if (meanReady) pool.submit(ownerId, tree.wanted, tree.wantedPriority, tree.wantedCount)
        tree.evictForReserve()
        lastUpdateNanos = System.nanoTime() - t0
    }

    /**
     * ρ controllers (§9.3): frame time (−20 % at most while frames take longer than the budget,
     * restored over ~4 s) and memory (when the tree asks for more tiles than there are slots, ρ
     * shrinks until the cut fits, down to 60 %, and recovers slowly once there is room).
     */
    private fun controlRho(dt: Double, frameMs: Float) {
        val base = quality.rho
        val budget = frameBudgetMs
        if (frameMs > budget * 1.05f) {
            rhoFrame = max(base * 0.8, rhoFrame - base * 0.5 * dt)
        } else if (frameMs > 0f && frameMs < budget * 0.9f) {
            rhoFrame = min(base, rhoFrame + base * 0.05 * dt)
        }
        val demand = tree.slotDemand
        val cap = tree.slotCapacity
        if (demand > cap) {
            rhoMemory = max(base * 0.6, rhoMemory - base * 0.6 * dt)
        } else if (demand < cap * 0.85) {
            rhoMemory = min(base, rhoMemory + base * 0.03 * dt)
        }
        rho = min(rhoFrame, rhoMemory)
    }

    private var rhoFrame = this.quality.rho
    private var rhoMemory = this.quality.rho

    /**
     * The scheduler's focus (§9.6, `focusFor`) and the path of the next 2.5 s, in body space. The
     * height that gates the path-first and near-radius boosts and plans the descent focus is hAgl
     * (above the ground or water below the ship, one exact query on the physics scratch, which
     * the ship's own queries reuse): the planet-scale layer moves the ground by kilometres, so the
     * height above the reference sphere would switch them off over mountains and leave them on
     * over deep basins. The focus point itself lies on the ground there (its own scratch).
     */
    private fun setFocus(shipPos: DVec3?, shipVel: Vec3?) {
        val f = tree.focus
        if (shipPos == null) {
            f.active = false
            return
        }
        f.active = true
        surface.toBody(shipPos.x, shipPos.y, shipPos.z, q)
        f.shipX = q[0]; f.shipY = q[1]; f.shipZ = q[2]
        val r = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2])
        if (r < 1.0) {
            f.active = false
            return
        }
        val ux = q[0] / r
        val uy = q[1] / r
        val uz = q[2] / r
        val floor = surface.floorAt(ux, uy, uz)
        val h = r - floor
        f.height = h
        if (shipVel == null) {
            f.velX = 0.0; f.velY = 0.0; f.velZ = 0.0
            f.hasFocus = false
            return
        }
        // velocity into body space (rotation only)
        val m = surface.rotation()
        val vx = shipVel.x.toDouble()
        val vy = shipVel.y.toDouble()
        val vz = shipVel.z.toDouble()
        val bx = m[0] * vx + m[3] * vy + m[6] * vz
        val by = m[1] * vx + m[4] * vy + m[7] * vz
        val bz = m[2] * vx + m[5] * vy + m[8] * vz
        f.velX = bx; f.velY = by; f.velZ = bz
        val vUp = bx * ux + by * uy + bz * uz
        val fx = bx - ux * vUp
        val fy = by - uy * vUp
        val fz = bz - uz * vUp
        val flat = sqrt(fx * fx + fy * fy + fz * fz)
        if (flat < 5.0) {
            // hovering: the ground below the ship
            f.focusX = ux * floor; f.focusY = uy * floor; f.focusZ = uz * floor
            f.hasFocus = h < 20_000.0
            return
        }
        val ahead = if (vUp < -20.0 && h > 1.2 * LEVEL_ALTITUDE) flat / -vUp * (h - LEVEL_ALTITUDE) + 300.0 else flat * LOOKAHEAD
        val d = min(ahead, MAX_LOOKAHEAD)
        val px = q[0] + fx / flat * d
        val py = q[1] + fy / flat * d
        val pz = q[2] + fz / flat * d
        val pr = sqrt(px * px + py * py + pz * pz)
        val nx = px / pr
        val ny = py / pr
        val nz = pz / pr
        val fr = if (h < 20_000.0) focusFloor(nx, ny, nz) else floor
        f.focusX = nx * fr; f.focusY = ny * fr; f.focusZ = nz * fr
        f.hasFocus = h < 20_000.0
    }

    // the scheduler's own scratch: the focus moves a few metres per frame, so its box is reused,
    // and the physics scratch keeps the ship's
    private val focusScratch = FieldScratch()
    private val focusSample = SurfaceSample()

    /** The floor radius at a direction (exact, the scheduler's scratch). */
    private fun focusFloor(nx: Double, ny: Double, nz: Double): Double {
        field.sampleAt(nx, ny, nz, focusScratch, focusSample)
        return focusSample.floorR
    }

    /**
     * Takes up to [max] finished tiles from the workers (game/GL thread): each goes into a free GPU
     * slot through [up] and its node becomes resident; stale tiles are dropped. Returns the count.
     */
    fun processCompletions(max: Int, up: TileUploader): Int {
        val t0 = System.nanoTime()
        uploader = up
        uploadsThisCall = 0
        pool.pollCompleted(ownerId, max, sink)
        uploader = null
        lastUploads = uploadsThisCall
        lastUploadNanos = System.nanoTime() - t0
        return uploadsThisCall
    }

    private fun accept(st: TileStaging) {
        val n = st.node
        val t = tree
        if (!st.ok || !t.alive[n] || t.gen[n] != st.gen || t.slot[n] >= 0) {
            droppedTotal++
            return
        }
        val s = t.allocSlot()
        if (s < 0) {
            droppedTotal++
            return
        }
        uploader?.upload(s, st)
        originX[n] = st.cx; originY[n] = st.cy; originZ[n] = st.cz
        halfX[n] = st.hx; halfY[n] = st.hy; halfZ[n] = st.hz
        morphScale[n] = st.morphScale
        t.makeResident(n, s, st.rMin, st.rMax, st.morphScale)
        uploadsThisCall++
        uploadsTotal++
    }

    override fun buildTile(node: Int, out: TileStaging, worker: Int): Boolean {
        val t = tree
        val gen = out.gen
        val f = t.face[node]
        val l = t.level[node]
        val i = t.ix[node]
        val j = t.iy[node]
        if (t.gen[node] != gen) return false
        val b = builders[worker] ?: TileBuilder(field).also { builders[worker] = it }
        b.build(f, l, i, j, out)
        return t.gen[node] == gen
    }

    /** Pauses or resumes generation (app paused). */
    fun pause() = pool.pause()
    fun resume() = pool.resume()

    /** Leaves the pool and drops every tile (the renderer frees its GPU slots separately). */
    fun release() {
        pool.unregister(ownerId)
        tree.clear()
    }

    /**
     * The GPU side of the tiles is gone: after a GL context loss (drop the old `TerrainGpu`
     * without disposing it, its names died with the context) or after `TerrainGpu.dispose()` when
     * the scene renderer is rebuilt on the same context. Drops every tile (no GL calls; tiles in
     * flight for the old tree are dropped when they arrive, root tiles stay valid); the impostor
     * shades the surface again until the roots are back ([surfaceReady]). Then attach the planet to
     * the new renderer (`SceneRenderer.attachTerrain`) and give the impostor and the terrain their
     * far texture again.
     */
    fun onGpuLost() = tree.clear()

    /**
     * The far look of the body as an equirect image for the impostor (BodyRenderer's bake format:
     * RGBA8, sqrt-encoded albedo = the terrain's sRGB colour, A = 0; row 0 = south pole, u = 0 at
     * longitude −π about +Y): the terrain colour at a band limit of one texel, from the same
     * functions as the tiles, so impostor and terrain agree at the hand-over (DECISIONS 17a).
     * Waits for the mean colour table (the same one the tiles use: [ready] may still be false
     * when this starts). CPU only, allocates its own scratch; run it off the GL thread (1–2 s for
     * 1024 × 512 on desktop).
     */
    fun bakeEquirect(width: Int, height: Int): java.nio.ByteBuffer {
        field.buildMeanColours()
        val out = java.nio.ByteBuffer.allocateDirect(width * height * 4).order(java.nio.ByteOrder.nativeOrder())
        val fs = FieldScratch()
        val sample = SurfaceSample()
        val col = VertexColour()
        val spacing = Math.PI * radius / height
        for (y in 0 until height) {
            val lat = ((y + 0.5) / height - 0.5) * Math.PI
            val cl = kotlin.math.cos(lat)
            val sl = kotlin.math.sin(lat)
            for (x in 0 until width) {
                val lon = ((x + 0.5) / width - 0.5) * 2.0 * Math.PI
                val nx = cl * kotlin.math.cos(lon)
                val ny = sl
                val nz = cl * kotlin.math.sin(lon)
                val px = nx * radius
                val py = ny * radius
                val pz = nz * radius
                val b = spacing
                field.collect(px - b, py - b, pz - b, px + b, py + b, pz + b, spacing, fs)
                field.sample(nx, ny, nz, spacing, fs, sample)
                field.colour(sample, 0.0, spacing, fs, col)
                val o = (y * width + x) * 4
                out.put(o, (col.r.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                out.put(o + 1, (col.g.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                out.put(o + 2, (col.b.coerceIn(0.0, 1.0) * 255 + 0.5).toInt().toByte())
                out.put(o + 3, 0)
            }
        }
        out.position(0)
        return out
    }

    /** One line of statistics (debug overlay; allocates, not for every frame). */
    fun stats(): String =
        "terrain $name ${style.key} L$maxLevel ρ=%.2f alt=%.0f m resident=%d drawn=%d wanted=%d queue=%d busy=%d uploads=%d dropped=%d upd=%.2f ms"
            .format(rho, cameraAltitude, tree.residentCount, tree.drawCount, tree.wantedCount, pool.queued(), pool.inFlight(),
                uploadsTotal, droppedTotal, lastUpdateNanos / 1e6)

    companion object {
        /** The default [frameBudgetMs]: 60 fps. */
        const val DEFAULT_BUDGET_MS = 1000f / 60f
        const val LEVEL_ALTITUDE = 900.0
        const val LOOKAHEAD = 2.5
        const val MAX_LOOKAHEAD = 9000.0

        /** The planet for a body of the world data: profile from [SurfaceProfiles.forBody], style by chooseStyle. */
        fun forBody(
            name: String, radius: Double, bodyClass: String, seed: Int, quality: Int, pool: TileWorkerPool,
            systemName: String? = null, hasAtmosphere: Boolean = false, atmosphereColour: Rgb? = null, gravityG: Double = 1.0,
        ): TerrainPlanet {
            val profile = SurfaceProfiles.forBody(name, bodyClass, seed, systemName, hasAtmosphere, atmosphereColour)
            return TerrainPlanet(name, radius, profile, quality, pool).also { it.surface.gravityG = gravityG }
        }
    }
}
