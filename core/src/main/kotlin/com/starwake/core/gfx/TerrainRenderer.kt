package com.starwake.core.gfx

import com.starwake.core.math.Mat4
import com.starwake.core.terrain.Rgb
import com.starwake.core.terrain.SurfaceProfile
import com.starwake.core.terrain.TerrainPlanet
import com.starwake.core.terrain.TileFormat
import com.starwake.core.terrain.TileStaging
import com.starwake.core.terrain.TileUploader
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Lighting and aerial-perspective inputs of the terrain for one frame. The defaults are the
 * engine's (sun colour of BodyRenderer and SceneRenderer); [setAir] derives the haze from a surface
 * profile (DECISIONS 17b/17c) until the atmosphere port supplies its own. The sun and the far look
 * come from the body's impostor ([BodyRenderer.fillTerrainLight]; `SceneRenderer` does it every
 * frame), so the terrain is lit like the impostor it replaces.
 */
class TerrainLight {
    /** World direction towards the star (unit). */
    val sunDir = floatArrayOf(0f, 1f, 0f)

    /** Sun radiance (linear): BodyRenderer's 2.6/2.45/2.25 × its distance falloff for this body. */
    val sunColor = floatArrayOf(2.6f, 2.45f, 2.25f)
    val ambientDay = floatArrayOf(0.02f, 0.02f, 0.025f)
    val ambientNight = floatArrayOf(0.002f, 0.002f, 0.003f)
    var exposure = 1f

    /**
     * The impostor's surface image (GL texture; 0 = none: no far look) and its texels per radian.
     * The image is the impostor's: give it the terrain's own far colours
     * (`TerrainPlanet.bakeEquirect` → `SceneRenderer.setBodySurfaceTexture`) so far and near agree.
     */
    var farTexture = 0
    var farTexelsPerRadian = 0f

    /** The impostor's air: scattering colour (rgb, max 1), shell thickness (radii; 0 = none), ground optical depth. */
    val farAtmo = FloatArray(4)
    var farAtmoK = 0f

    /** The impostor's surface type (BodyShaders codes: 1 lava glow, 2 terran water glint, 4 ice sheen; else 0). */
    var farType = 0

    /** Air: scale height (m), ground extinction (1/m), chromatic weights, in-scatter radiance (linear). */
    var scaleHeight = 8700f
    var sigma0 = 0f
    val airWeights = floatArrayOf(1f, 1f, 1f)
    val airRadiance = floatArrayOf(0f, 0f, 0f)

    /** Minimum sine of the slant (curvature limit, sqrt(2H/(πR))), set by [setAir]. */
    var muMin = 0.03f

    /** The body's cloud deck this frame ([BodyRenderer.fillTerrainLight]); off without one. */
    val clouds = CloudDeckState()

    fun setSun(x: Float, y: Float, z: Float) {
        val l = sqrt(x * x + y * y + z * z)
        sunDir[0] = x / l; sunDir[1] = y / l; sunDir[2] = z / l
    }

    /**
     * Haze and ambient from a surface profile: no air → no haze; with air, H = thickness/3, ground
     * e-fold = 10 × Roblox's `454/(density·0.4)` (Earth ~35 km, DECISIONS 17c), in Roblox's haze
     * colour (FieldFogDay under a "day" sky, else the air colour desaturated 35 %); ambient =
     * profile ambient², × 0.35 (05 §8.7 AmbientGain).
     */
    fun setAir(profile: SurfaceProfile, radius: Double) {
        val sky = profile.sky
        ambientDay[0] = lin(sky.ambient.r) * AMBIENT_GAIN
        ambientDay[1] = lin(sky.ambient.g) * AMBIENT_GAIN
        ambientDay[2] = lin(sky.ambient.b) * AMBIENT_GAIN
        ambientNight[0] = lin(sky.nightAmbient.r) * AMBIENT_GAIN
        ambientNight[1] = lin(sky.nightAmbient.g) * AMBIENT_GAIN
        ambientNight[2] = lin(sky.nightAmbient.b) * AMBIENT_GAIN
        if (!sky.air) {
            sigma0 = 0f
            airRadiance.fill(0f)
            airWeights.fill(1f)
            return
        }
        scaleHeight = (sky.thickness / 3.0).toFloat()
        val eFold = 10.0 * 454.0 / (sky.density * 0.4)
        sigma0 = (1.0 / eFold).toFloat()
        muMin = sqrt(2.0 * scaleHeight / (Math.PI * radius)).toFloat()
        val haze: Rgb = if (sky.skybox == "day") Rgb.of(46, 105, 167) else desaturate(sky.colour, 0.35)
        // the haze colour is what Roblox shows on screen: in-scatter radiance = that colour before the
        // tonemap, so terrain fades into exactly it (a little brighter: the port's air is clearer)
        airRadiance[0] = untonemap(haze.r) * AIR_GAIN
        airRadiance[1] = untonemap(haze.g) * AIR_GAIN
        airRadiance[2] = untonemap(haze.b) * AIR_GAIN
        // extinction a little stronger where the haze is brighter (blue skies redden distant light)
        val r = lin(haze.r)
        val g = lin(haze.g)
        val b = lin(haze.b)
        val mean = (r + g + b) / 3f
        airWeights[0] = (1f + 0.35f * (r / mean - 1f)).coerceIn(0.6f, 1.6f)
        airWeights[1] = (1f + 0.35f * (g / mean - 1f)).coerceIn(0.6f, 1.6f)
        airWeights[2] = (1f + 0.35f * (b / mean - 1f)).coerceIn(0.6f, 1.6f)
        val wm = (airWeights[0] + airWeights[1] + airWeights[2]) / 3f
        for (k in 0 until 3) airWeights[k] /= wm
    }

    /** The linear radiance the engine's tonemap (ACES fit, exposure 1, gamma 1/2.2) shows as [display]. */
    private fun untonemap(display: Double): Float {
        val target = Math.pow(display.coerceIn(0.0, 0.999), 2.2)
        var lo = 0.0
        var hi = 64.0
        for (i in 0 until 60) {
            val x = (lo + hi) * 0.5
            val t = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)
            if (t < target) lo = x else hi = x
        }
        return ((lo + hi) * 0.5).toFloat()
    }

    private fun lin(c: Double) = (c * c).toFloat()

    private fun desaturate(c: Rgb, k: Double): Rgb {
        val luma = 0.299 * c.r + 0.587 * c.g + 0.114 * c.b
        return Rgb(c.r + (luma - c.r) * k, c.g + (luma - c.g) * k, c.b + (luma - c.b) * k)
    }

    companion object {
        const val AMBIENT_GAIN = 0.35f
        const val AIR_GAIN = 1.25f
    }
}

/**
 * Scene objects drawn inside the terrain's far depth slices ([TerrainRenderer.drawFar]), so the
 * terrain and they share each slice's depth buffer: implemented once by the scene (no allocation
 * per frame).
 */
interface TerrainSliceObjects {
    /**
     * Draws what overlaps the view depths [near, far] with [viewProj] (the slice's projection ×
     * the camera view), after the slice's terrain. Point sprites go in exactly one slice: the one
     * that owns their depth, [ownNear, ownFar). Depth test LEQUAL and depth writes are on, culling
     * back faces, blending off; anything may be changed (the next slice sets its own state).
     */
    fun drawSlice(near: Float, far: Float, ownNear: Float, ownFar: Float, viewProj: Mat4)
}

/** The GPU side of one [TerrainPlanet]: a VBO and VAO per tile slot (created on first use). */
class TerrainGpu internal constructor(private val gl: Gl, private val ibo: Int, val planet: TerrainPlanet) : TileUploader {
    private val capacity = planet.tree.slotCapacity
    private val vbo = IntArray(capacity)
    private val vao = IntArray(capacity)

    /** Bytes of vertex data allocated on the GPU. */
    var gpuBytes = 0L
        private set

    override fun upload(slot: Int, staging: TileStaging) {
        val b = staging.buffer
        b.position(0)
        if (vbo[slot] == 0) {
            vbo[slot] = gl.genBuffer()
            vao[slot] = gl.genVertexArray()
            gl.bindVertexArray(vao[slot])
            gl.bindBuffer(GL.ARRAY_BUFFER, vbo[slot])
            gl.bufferData(GL.ARRAY_BUFFER, TileFormat.TILE_BYTES, b, GL.STATIC_DRAW)
            val s = TileFormat.STRIDE
            gl.enableVertexAttribArray(0)
            gl.vertexAttribPointer(0, 4, GL.SHORT, true, s, 0)
            gl.enableVertexAttribArray(1)
            gl.vertexAttribPointer(1, 4, GL.BYTE, true, s, 8)
            gl.enableVertexAttribArray(2)
            gl.vertexAttribPointer(2, 4, GL.UNSIGNED_BYTE, true, s, 12)
            gl.enableVertexAttribArray(3)
            gl.vertexAttribPointer(3, 4, GL.UNSIGNED_BYTE, true, s, 16)
            gl.enableVertexAttribArray(4)
            gl.vertexAttribPointer(4, 4, GL.UNSIGNED_BYTE, true, s, 20)
            gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
            gl.bindVertexArray(0)
            gpuBytes += TileFormat.TILE_BYTES
        } else {
            gl.bindBuffer(GL.ARRAY_BUFFER, vbo[slot])
            gl.bufferSubData(GL.ARRAY_BUFFER, 0, TileFormat.TILE_BYTES, b)
        }
    }

    internal fun vao(slot: Int) = vao[slot]

    fun dispose() {
        for (i in 0 until capacity) {
            if (vbo[i] != 0) gl.deleteBuffer(vbo[i])
            if (vao[i] != 0) gl.deleteVertexArray(vao[i])
            vbo[i] = 0
            vao[i] = 0
        }
        gpuBytes = 0
    }
}

/**
 * Draws [TerrainPlanet]s (§9.8): uploads ≤ N finished tiles per frame, frustum-culls the cut,
 * splits the visible depth range into slices (each with its own projection, far to near, so a
 * 24-bit depth buffer resolves 0.5 m at the ship and 20,000 km at the horizon alike), and draws
 * every tile in each slice it overlaps (the slice planes clip it). Two depth modes ([depthMode]):
 * a depth clear per slice, or one clear with the depth range split between the slices.
 *
 * One draw call per tile (VAO per slot, the per-tile data in one uniform array); nothing allocated
 * per frame.
 */
class TerrainRenderer(
    private val gl: Gl, val quality: Int,
    /** The cloud decks' textures (the scene's); null: a set of its own that never shows a deck. */
    sharedClouds: CloudTextures? = null,
) {
    private val ownClouds = if (sharedClouds == null) CloudTextures(gl, CloudField()) else null
    private val clouds: CloudTextures = sharedClouds ?: ownClouds!!
    private val prog = ShaderProgram(gl, "terrain", TerrainShaders.VS, TerrainShaders.fs(BodyRenderer.atmoSteps(quality), quality >= 2))
    private val uViewProj = prog.u("u_viewProj")
    private val uFarDist = prog.u("u_farDist")
    private val uFade = prog.u("u_fade")
    private val uFarTex = prog.u("u_farTex")
    private val uFarTexScale = prog.u("u_farTexScale")
    private val uFarType = prog.u("u_farType")
    private val uFarAtmo = prog.u("u_farAtmo")
    private val uFarAtmoK = prog.u("u_farAtmoK")
    private val uFarCam = prog.u("u_farCam")
    private val uBodyRot = prog.u("u_bodyRot4")
    private val uTile = prog.u("u_tile")
    private val uCamCentre = prog.u("u_camCentre")
    private val uSunDirV = prog.u("u_sunDir")
    private val uAir = prog.u("u_air")
    private val uAirW = prog.u("u_airW")
    private val uAirL = prog.u("u_airL")
    private val uSunColor = prog.u("u_sunColor")
    private val uAmbDay = prog.u("u_ambientDay")
    private val uAmbNight = prog.u("u_ambientNight")
    private val uExposure = prog.u("u_exposure")
    private val uDetail = prog.u("u_detail")
    private val uDetailParams = prog.u("u_detailParams")
    private val uDebug = prog.u("u_debug")
    private val uWater = prog.u("u_water")
    private val uCloudMap = prog.u("u_cloudMap")
    private val uCloudNoise = prog.u("u_cloudNoise")
    private val uToCloud = prog.u("u_toCloud")
    private val uCloud = prog.u("u_cloud")
    private val uCloudCol = prog.u("u_cloudCol")
    private val uCloudCam = prog.u("u_cloudCam")
    private val uCloudCamB = prog.u("u_cloudCamB")
    private val uCloudGeo = prog.u("u_cloudGeo")
    private val toCloud = FloatArray(16).also { it[15] = 1f }

    /** Debug view: 0 normal, 1 albedo, 2 water share, 3 normals, 4 morph factor, 5 transmittance, 6 far-look weight. */
    var debugView = 0

    /**
     * Opacity of the terrain (1: opaque). Below 1 it is blended over what is drawn (the impostor's
     * own surface while the terrain fades in at the hand-over, `SceneRenderer`).
     */
    var fade = 1f

    private val ibo: Int
    private val detailTex: Int

    /** CLEAR: clear depth before each slice; RANGE: split the depth range between the slices. */
    var depthMode = DEPTH_CLEAR

    /** Largest far/near ratio of a slice. */
    var sliceRatio = 4000f

    // per-frame scratch
    private var capacity = 0
    private var visible = IntArray(0)
    private var zMin = FloatArray(0)
    private var zMax = FloatArray(0)
    private val tileData = FloatArray(16)
    private val bodyRot = FloatArray(16)
    private val proj = Mat4()
    private val viewProj = Mat4()
    val sliceNear = FloatArray(MAX_SLICES)
    val sliceFar = FloatArray(MAX_SLICES)
    var sliceCount = 0
        private set
    var visibleCount = 0
        private set

    // statistics of the last frame
    var drawCalls = 0
        private set
    var trianglesDrawn = 0L
        private set
    var lastPrepareNanos = 0L
        private set
    var lastDrawNanos = 0L
        private set

    init {
        ibo = gl.genBuffer()
        // the element buffer binding belongs to the bound vertex array: without this the index
        // data would replace that of whatever was drawn last (the UI's quad stream)
        gl.bindVertexArray(0)
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
        gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, TileFormat.INDICES * 2, Buffers.of(TileFormat.indices()), GL.STATIC_DRAW)
        detailTex = gl.genTexture()
        val size = if (quality == 0) 128 else if (quality >= 3) 512 else 256
        gl.bindTexture(GL.TEXTURE_2D, detailTex)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, size, size, GL.RGBA, GL.UNSIGNED_BYTE, Buffers.of(DetailTextures.build(size)))
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.REPEAT)
        gl.generateMipmap(GL.TEXTURE_2D)
    }

    /**
     * GPU state for a planet. A planet's tiles live in exactly one [TerrainGpu]: attach a planet
     * that has no resident tiles yet (a new one, or after `tree.clear()`), and [TerrainGpu.dispose]
     * it when the planet is released.
     */
    fun attach(planet: TerrainPlanet): TerrainGpu {
        require(planet.tree.residentCount == 0) { "${planet.name} already has tiles in another TerrainGpu" }
        return TerrainGpu(gl, ibo, planet)
    }

    private fun ensureCapacity(n: Int) {
        if (capacity >= n) return
        capacity = n
        visible = IntArray(n)
        zMin = FloatArray(n)
        zMax = FloatArray(n)
    }

    /**
     * Uploads up to the quality's tiles per frame, then culls the cut against [camera]'s frustum and
     * plans the depth slices for a nearest plane of [nearPlane] metres. With [firstSliceFar] > 0 the
     * nearest slice is exactly [nearPlane, firstSliceFar] (the scene's near pass, shared with the ship
     * and stations); the others follow beyond it. The slices also cover the view depths
     * [extraNear, extraFar] (the scene's far objects, drawn inside them by [drawFar]) when
     * extraFar > 0. Returns the visible tile count.
     */
    fun prepare(
        gpu: TerrainGpu, camera: Camera, nearPlane: Float = camera.near, firstSliceFar: Float = 0f,
        extraNear: Float = 0f, extraFar: Float = 0f,
    ): Int {
        val t0 = System.nanoTime()
        val planet = gpu.planet
        planet.processCompletions(planet.quality.uploadsPerFrame, gpu)
        val t = planet.tree
        ensureCapacity(t.nodeCapacity)
        val c = camera
        val tx = c.tanHalfX * (1f + abs(c.shiftX))
        val ty = c.tanHalfY * (1f + abs(c.shiftY))
        val nxs = 1f / sqrt(1f + tx * tx)
        val nys = 1f / sqrt(1f + ty * ty)
        val rot = planet.surface.rotation()
        val ctr = planet.surface.centre
        val cp = c.pos
        var n = 0
        var lo = Float.MAX_VALUE
        var hi = 0f
        for (k in 0 until t.drawCount) {
            val node = t.drawList[k]
            if (t.slot[node] < 0) continue
            // bounding sphere centre, camera relative (world)
            val bx = t.bx[node]
            val by = t.by[node]
            val bz = t.bz[node]
            val wx = (ctr.x - cp.x + rot[0] * bx + rot[1] * by + rot[2] * bz).toFloat()
            val wy = (ctr.y - cp.y + rot[3] * bx + rot[4] * by + rot[5] * bz).toFloat()
            val wz = (ctr.z - cp.z + rot[6] * bx + rot[7] * by + rot[8] * bz).toFloat()
            val r = t.br[node].toFloat()
            val vx = wx * c.right.x + wy * c.right.y + wz * c.right.z
            val vy = wx * c.up.x + wy * c.up.y + wz * c.up.z
            val vz = wx * c.forward.x + wy * c.forward.y + wz * c.forward.z
            if (vz < -r) continue
            if ((abs(vx) - tx * vz) * nxs > r || (abs(vy) - ty * vz) * nys > r) continue
            visible[n] = node
            val z0 = max(nearPlane, vz - r)
            val z1 = vz + r
            zMin[n] = z0
            zMax[n] = z1
            if (z0 < lo) lo = z0
            if (z1 > hi) hi = z1
            n++
        }
        visibleCount = n
        sortByNear(n)
        var any = n > 0
        if (extraFar > 0f && extraFar > extraNear) {
            lo = minOf(lo, max(nearPlane, extraNear))
            hi = max(hi, extraFar)
            any = true
        }
        planSlices(lo, hi, nearPlane, firstSliceFar, any)
        lastPrepareNanos = System.nanoTime() - t0
        return n
    }

    /** Slices covering [lo, hi] (when there is [any]thing to draw) with far/near ≤ [sliceRatio], nearest first in the arrays. */
    private fun planSlices(lo: Float, hi: Float, nearPlane: Float, firstFar: Float, any: Boolean) {
        sliceCount = 0
        if (firstFar > 0f) {
            sliceNear[0] = nearPlane
            sliceFar[0] = firstFar
            sliceCount = 1
            if (!any || hi <= firstFar) return
        } else if (!any || hi <= lo) {
            return
        }
        var n = if (firstFar > 0f) max(firstFar * 0.95f, lo * 0.98f) else max(nearPlane, lo * 0.98f)
        while (sliceCount < MAX_SLICES) {
            val last = sliceCount == MAX_SLICES - 1
            var f = n * sliceRatio
            if (f >= hi || last) f = max(hi * 1.01f, n * 1.01f)
            sliceNear[sliceCount] = n
            sliceFar[sliceCount] = f
            sliceCount++
            if (f >= hi) break
            n = f * 0.95f
        }
    }

    /** Insertion-sorts the visible tiles by their nearest depth (front to back: early depth rejection). */
    private fun sortByNear(n: Int) {
        for (i in 1 until n) {
            val node = visible[i]
            val a = zMin[i]
            val b = zMax[i]
            var j = i - 1
            while (j >= 0 && zMin[j] > a) {
                visible[j + 1] = visible[j]
                zMin[j + 1] = zMin[j]
                zMax[j + 1] = zMax[j]
                j--
            }
            visible[j + 1] = node
            zMin[j + 1] = a
            zMax[j + 1] = b
        }
    }

    /** Binds the program and the per-frame uniforms (body rotation, light, air, detail texture). */
    fun begin(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float) {
        val planet = gpu.planet
        prog.use()
        val r = planet.surface.rotation()
        // column-major mat4 of the body → world rotation
        bodyRot[0] = r[0].toFloat(); bodyRot[1] = r[3].toFloat(); bodyRot[2] = r[6].toFloat(); bodyRot[3] = 0f
        bodyRot[4] = r[1].toFloat(); bodyRot[5] = r[4].toFloat(); bodyRot[6] = r[7].toFloat(); bodyRot[7] = 0f
        bodyRot[8] = r[2].toFloat(); bodyRot[9] = r[5].toFloat(); bodyRot[10] = r[8].toFloat(); bodyRot[11] = 0f
        bodyRot[12] = 0f; bodyRot[13] = 0f; bodyRot[14] = 0f; bodyRot[15] = 1f
        gl.uniformMatrix4fv(uBodyRot, bodyRot, 0)
        val ctr = planet.surface.centre
        val cp = camera.pos
        gl.uniform3f(uCamCentre, (cp.x - ctr.x).toFloat(), (cp.y - ctr.y).toFloat(), (cp.z - ctr.z).toFloat())
        gl.uniform3f(uSunDirV, light.sunDir[0], light.sunDir[1], light.sunDir[2])
        gl.uniform4f(uAir, planet.radius.toFloat(), light.scaleHeight, light.sigma0, light.muMin)
        gl.uniform3f(uAirW, light.airWeights[0], light.airWeights[1], light.airWeights[2])
        gl.uniform3f(uAirL, light.airRadiance[0], light.airRadiance[1], light.airRadiance[2])
        gl.uniform3f(uSunColor, light.sunColor[0], light.sunColor[1], light.sunColor[2])
        gl.uniform3f(uAmbDay, light.ambientDay[0], light.ambientDay[1], light.ambientDay[2])
        gl.uniform3f(uAmbNight, light.ambientNight[0], light.ambientNight[1], light.ambientNight[2])
        gl.uniform1f(uExposure, light.exposure)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D, detailTex)
        gl.uniform1i(uDetail, 0)
        val strength = if (quality == 0) 0.7f else 1f
        gl.uniform4f(uDetailParams, strength, 1500f, 9000f, time % 600f)
        gl.uniform1i(uDebug, debugView)
        val look = planet.style.waterLook
        // water reflects the sky: the haze radiance (the horizon) with air, a dark sky without
        val sky = if (light.sigma0 > 0f) 1.4f else 0.05f
        gl.uniform4f(uWater, light.airRadiance[0] * sky + 0.01f, light.airRadiance[1] * sky + 0.01f, light.airRadiance[2] * sky + 0.015f,
            (look?.reflectance ?: 0.0).toFloat())
        gl.uniform1f(uFade, fade)
        setClouds(planet, camera, light.clouds)
        val farTex = light.farTexture
        val farEnd = farLookEnd(planet, light)
        if (farEnd > 0f) {
            gl.uniform2f(uFarDist, 0.5f * farEnd, farEnd)
            gl.activeTexture(GL.TEXTURE0 + 1)
            gl.bindTexture(GL.TEXTURE_2D, farTex)
            gl.uniform1i(uFarTex, 1)
            gl.activeTexture(GL.TEXTURE0)
            gl.uniform1f(uFarTexScale, light.farTexelsPerRadian)
            gl.uniform1i(uFarType, light.farType)
            val a = light.farAtmo
            gl.uniform4f(uFarAtmo, a[0], a[1], a[2], a[3])
            gl.uniform2f(uFarAtmoK, light.farAtmoK, 1f)
            val inv = 1.0 / planet.radius
            gl.uniform4f(uFarCam, ((cp.x - ctr.x) * inv).toFloat(), ((cp.y - ctr.y) * inv).toFloat(), ((cp.z - ctr.z) * inv).toFloat(), inv.toFloat())
        } else {
            gl.uniform2f(uFarDist, 0f, 0f)
        }
    }

    /**
     * The cloud deck's uniforms: the world → deck rotation (the body's axes turned by the deck's
     * drift), the camera in that frame wrapped to the noise period (in double, so the detail holds
     * still at any distance from the centre) and its height over the deck. The samplers always get
     * units of their own, deck or not.
     */
    private fun setClouds(planet: TerrainPlanet, camera: Camera, deck: CloudDeckState) {
        gl.activeTexture(GL.TEXTURE0 + 2)
        gl.bindTexture(GL.TEXTURE_2D, clouds.map)
        gl.activeTexture(GL.TEXTURE0 + 3)
        gl.bindTexture(GL.TEXTURE_3D, clouds.noise)
        gl.activeTexture(GL.TEXTURE0)
        gl.uniform1i(uCloudMap, 2)
        gl.uniform1i(uCloudNoise, 3)
        // radians per pixel: the sea's swells fade by it too, deck or not
        val pxAngle = 2f * camera.tanHalfY / max(camera.height, 1)
        if (!deck.on) {
            gl.uniform4f(uCloud, 2f, 0f, 1f, 1f)
            gl.uniform4f(uCloudGeo, 0f, 0f, pxAngle, 0f)
            return
        }
        val r = planet.surface.rotation()
        val c = kotlin.math.cos(deck.angle)
        val s = kotlin.math.sin(deck.angle)
        val m = toCloud
        // world → body is the transpose of the body → world rows r; then the turn about the body's Y
        m[0] = (c * r[0] + s * r[2]).toFloat(); m[4] = (c * r[3] + s * r[5]).toFloat(); m[8] = (c * r[6] + s * r[8]).toFloat()
        m[1] = r[1].toFloat(); m[5] = r[4].toFloat(); m[9] = r[7].toFloat()
        m[2] = (-s * r[0] + c * r[2]).toFloat(); m[6] = (-s * r[3] + c * r[5]).toFloat(); m[10] = (-s * r[6] + c * r[8]).toFloat()
        gl.uniformMatrix4fv(uToCloud, m, 0)
        gl.uniform4f(uCloud, deck.threshold, deck.shadow, deck.radius.toFloat(), (CloudField.MAP_HEIGHT / (Math.PI * deck.radius)).toFloat())
        gl.uniform4f(uCloudCol, deck.colour[0], deck.colour[1], deck.colour[2], deck.fade)
        val cb = planet.camBody
        val qx = c * cb[0] + s * cb[2]
        val qz = -s * cb[0] + c * cb[2]
        val period = CloudField.BILLOW_PERIOD
        gl.uniform3f(uCloudCam, wrap(qx, period), wrap(cb[1], period), wrap(qz, period))
        val k = CloudField.BANK
        val bp = CloudField.BANK_PERIOD
        gl.uniform3f(
            uCloudCamB,
            wrap((k[0] * qx + k[1] * cb[1] + k[2] * qz) / bp, 1.0),
            wrap((k[3] * qx + k[4] * cb[1] + k[5] * qz) / bp, 1.0),
            wrap((k[6] * qx + k[7] * cb[1] + k[8] * qz) / bp, 1.0),
        )
        val rc = sqrt(cb[0] * cb[0] + cb[1] * cb[1] + cb[2] * cb[2])
        gl.uniform4f(uCloudGeo, (rc - deck.radius).toFloat(), deck.fog, pxAngle, 0f)
    }

    private fun wrap(v: Double, period: Double): Float = (v - period * kotlin.math.floor(v / period)).toFloat()

    /**
     * The visible tiles in [near, far] with [vp]: once when opaque. While the terrain fades in
     * ([fade] < 1), first depth only (blending ZERO, ONE keeps the colour), then blended where the
     * depth is that of the nearest surface: every pixel takes the terrain once, so neither skirts
     * nor tiles behind others show through. Depth test LEQUAL is the caller's; leaves depth writes
     * on and blending in the caller's state only when opaque.
     */
    private fun drawTiles(gpu: TerrainGpu, camera: Camera, near: Float, far: Float, vp: Mat4) {
        if (fade >= 1f) {
            drawRange(gpu, camera, near, far, vp)
            return
        }
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ZERO, GL.ONE)
        gl.depthMask(true)
        drawRange(gpu, camera, near, far, vp)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE_MINUS_SRC_ALPHA)
        gl.depthMask(false)
        drawRange(gpu, camera, near, far, vp)
        gl.depthMask(true)
        gl.disable(GL.BLEND)
    }

    /**
     * Draws the visible tiles that overlap the view depths [near, far] with [viewProjection] (the
     * slice's projection × the camera view). Depth state is the caller's.
     */
    fun drawRange(gpu: TerrainGpu, camera: Camera, near: Float, far: Float, viewProjection: Mat4) {
        val planet = gpu.planet
        val t = planet.tree
        gl.uniformMatrix4fv(uViewProj, viewProjection.m, 0)
        val rot = planet.surface.rotation()
        val ctr = planet.surface.centre
        val cp = camera.pos
        val now = t.time
        for (k in 0 until visibleCount) {
            if (zMin[k] > far || zMax[k] < near) continue
            val node = visible[k]
            val slot = t.slot[node]
            if (slot < 0) continue
            val ox = planet.originX[node]
            val oy = planet.originY[node]
            val oz = planet.originZ[node]
            val d = tileData
            d[0] = (ctr.x - cp.x + rot[0] * ox + rot[1] * oy + rot[2] * oz).toFloat()
            d[1] = (ctr.y - cp.y + rot[3] * ox + rot[4] * oy + rot[5] * oz).toFloat()
            d[2] = (ctr.z - cp.z + rot[6] * ox + rot[7] * oy + rot[8] * oz).toFloat()
            d[3] = planet.morphScale[node]
            d[4] = ox.toFloat()
            d[5] = oy.toFloat()
            d[6] = oz.toFloat()
            val ms = t.morphStart[node]
            d[7] = if (ms < 0.0) 0f else (1.0 - smooth01((now - ms) / MORPH_SECONDS)).toFloat()
            d[8] = planet.halfX[node]
            d[9] = planet.halfY[node]
            d[10] = planet.halfZ[node]
            val lvl = t.level[node]
            if (lvl == 0) {
                d[11] = 1e30f
                d[12] = 2e30f
            } else {
                val dd = (t.rho * t.edge(lvl - 1)).toFloat()
                d[11] = 0.75f * dd
                d[12] = dd
            }
            d[13] = mod512(ox)
            d[14] = mod512(oy)
            d[15] = mod512(oz)
            gl.uniform4fv(uTile, 4, d, 0)
            gl.bindVertexArray(gpu.vao(slot))
            gl.drawElements(GL.TRIANGLES, TileFormat.INDICES, GL.UNSIGNED_SHORT, 0)
            drawCalls++
            trianglesDrawn += TileFormat.TRIANGLES
        }
    }

    /**
     * The whole terrain on its own (harness, or a scene without other near objects): every slice far
     * to near with its own projection, after [prepare]. Leaves depth testing enabled.
     */
    fun drawSlices(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float) {
        val t0 = System.nanoTime()
        drawCalls = 0
        trianglesDrawn = 0
        if (sliceCount == 0) {
            lastDrawNanos = System.nanoTime() - t0
            return
        }
        gl.enable(GL.DEPTH_TEST)
        gl.depthFunc(GL.LEQUAL)
        gl.depthMask(true)
        gl.enable(GL.CULL_FACE)
        gl.cullFace(GL.BACK)
        gl.disable(GL.BLEND)
        begin(gpu, camera, light, time)
        val c = camera
        val aspect = c.width.toFloat() / max(c.height, 1)
        val s = sliceCount
        if (depthMode == DEPTH_RANGE) {
            gl.clear(GL.DEPTH_BUFFER_BIT)
        }
        for (k in s - 1 downTo 0) {
            if (depthMode == DEPTH_CLEAR) {
                gl.depthRange(0f, 1f)
                gl.clear(GL.DEPTH_BUFFER_BIT)
            } else {
                gl.depthRange(k.toFloat() / s, (k + 1).toFloat() / s)
            }
            proj.perspective(c.fovY, aspect, sliceNear[k], sliceFar[k], c.shiftX, c.shiftY)
            viewProj.mul(proj, c.view)
            drawTiles(gpu, camera, sliceNear[k], sliceFar[k], viewProj)
        }
        gl.depthRange(0f, 1f)
        gl.disable(GL.BLEND)
        gl.bindVertexArray(0)
        lastDrawNanos = System.nanoTime() - t0
    }

    /**
     * The slices beyond the nearest one (scene integration, after [prepare] with a first slice):
     * far to near, each after a depth clear, with the scene's [objects] that reach into it drawn
     * after its terrain (same projection, same depth buffer: a station behind a mountain is hidden
     * by it), then one more clear for the scene's near pass, which draws slice 0 with [drawRange]
     * next to the ship. Resets the counters.
     */
    fun drawFar(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float, objects: TerrainSliceObjects? = null) {
        val t0 = System.nanoTime()
        drawCalls = 0
        trianglesDrawn = 0
        if (sliceCount > 1) {
            val c = camera
            val aspect = c.width.toFloat() / max(c.height, 1)
            for (k in sliceCount - 1 downTo 1) {
                if (k == sliceCount - 1 || objects != null) {
                    // the objects of the previous slice used programs, textures and state of their own
                    gl.enable(GL.DEPTH_TEST)
                    gl.depthFunc(GL.LEQUAL)
                    gl.depthMask(true)
                    gl.enable(GL.CULL_FACE)
                    gl.cullFace(GL.BACK)
                    gl.disable(GL.BLEND)
                    gl.depthRange(0f, 1f)
                    begin(gpu, camera, light, time)
                }
                gl.clear(GL.DEPTH_BUFFER_BIT)
                proj.perspective(c.fovY, aspect, sliceNear[k], sliceFar[k], c.shiftX, c.shiftY)
                viewProj.mul(proj, c.view)
                drawTiles(gpu, camera, sliceNear[k], sliceFar[k], viewProj)
                if (objects != null) {
                    gl.bindVertexArray(0)
                    val ownFar = if (k == sliceCount - 1) Float.MAX_VALUE else sliceFar[k]
                    objects.drawSlice(sliceNear[k], sliceFar[k], sliceFar[k - 1], ownFar, viewProj)
                }
            }
            gl.depthMask(true)
            gl.disable(GL.BLEND)
            gl.clear(GL.DEPTH_BUFFER_BIT)
            gl.bindVertexArray(0)
        }
        lastDrawNanos = System.nanoTime() - t0
    }

    /** Draws slice 0 with the scene's near projection [nearViewProj] (inside the near pass; leaves blending off). */
    fun drawNear(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float, nearViewProj: Mat4) {
        if (sliceCount == 0) return
        val t0 = System.nanoTime()
        gl.disable(GL.BLEND)
        begin(gpu, camera, light, time)
        drawTiles(gpu, camera, sliceNear[0], sliceFar[0], nearViewProj)
        gl.disable(GL.BLEND)
        gl.bindVertexArray(0)
        lastDrawNanos += System.nanoTime() - t0
    }

    /** Resets the per-frame counters (callers that use [drawRange] directly). */
    fun resetCounters() {
        drawCalls = 0
        trianglesDrawn = 0
    }

    fun dispose() {
        prog.dispose()
        gl.deleteBuffer(ibo)
        gl.deleteTexture(detailTex)
        ownClouds?.dispose()
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /** The detail-texture origin: periods up to 512 m tile exactly (a multiple of every period used). */
    private fun mod512(v: Double): Float {
        val r = v - 512.0 * kotlin.math.floor(v / 512.0)
        return r.toFloat()
    }

    companion object {
        const val DEPTH_CLEAR = 0
        const val DEPTH_RANGE = 1
        const val MAX_SLICES = 4

        /**
         * View distance (m) from which the terrain shows only its far look (the impostor's image,
         * sun and air), 0 without an image. The tiles' vertex spacing at view distance d is
         * d/(64ρ)..d/(32ρ), so from 32ρ image texels on the image may carry more detail than a
         * tile, from 64ρ on it always does: the far look blends in between.
         */
        fun farLookEnd(planet: TerrainPlanet, light: TerrainLight): Float {
            if (light.farTexture == 0 || light.farTexelsPerRadian <= 0f) return 0f
            val texel = planet.radius / light.farTexelsPerRadian
            return (64.0 * planet.tree.rho * texel).toFloat()
        }

        /** A tile that appears because its parent split morphs in from the parent's shape over this time. */
        const val MORPH_SECONDS = 0.35
    }
}
