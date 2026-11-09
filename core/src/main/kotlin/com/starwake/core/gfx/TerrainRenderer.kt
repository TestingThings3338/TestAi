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
 * profile (DECISIONS 17b/17c) until the atmosphere port supplies its own.
 */
class TerrainLight {
    /** World direction towards the star (unit). */
    val sunDir = floatArrayOf(0f, 1f, 0f)

    /** Sun radiance (linear), BodyRenderer's 2.6/2.45/2.25 × its distance falloff × visibility. */
    val sunColor = floatArrayOf(2.6f, 2.45f, 2.25f)
    val ambientDay = floatArrayOf(0.02f, 0.02f, 0.025f)
    val ambientNight = floatArrayOf(0.002f, 0.002f, 0.003f)
    var exposure = 1f

    /** Air: scale height (m), ground extinction (1/m), chromatic weights, in-scatter radiance (linear). */
    var scaleHeight = 8700f
    var sigma0 = 0f
    val airWeights = floatArrayOf(1f, 1f, 1f)
    val airRadiance = floatArrayOf(0f, 0f, 0f)

    /** Minimum sine of the slant (curvature limit, sqrt(2H/(πR))), set by [setAir]. */
    var muMin = 0.03f

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
class TerrainRenderer(private val gl: Gl, val quality: Int) {
    private val prog = ShaderProgram(gl, "terrain", TerrainShaders.VS, TerrainShaders.FS)
    private val uViewProj = prog.u("u_viewProj")
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

    /** Debug view: 0 normal, 1 albedo, 2 water share, 3 normals, 4 morph factor, 5 transmittance. */
    var debugView = 0

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
        gl.bindBuffer(GL.ELEMENT_ARRAY_BUFFER, ibo)
        gl.bufferData(GL.ELEMENT_ARRAY_BUFFER, TileFormat.INDICES * 2, Buffers.of(TileFormat.indices()), GL.STATIC_DRAW)
        detailTex = gl.genTexture()
        val size = if (quality == 0) 128 else 256
        gl.bindTexture(GL.TEXTURE_2D, detailTex)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, size, size, GL.RGBA, GL.UNSIGNED_BYTE, Buffers.of(DetailTextures.build(size)))
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.REPEAT)
        gl.generateMipmap(GL.TEXTURE_2D)
    }

    /** GPU state for a planet (call once per planet; [TerrainGpu.dispose] when it is released). */
    fun attach(planet: TerrainPlanet): TerrainGpu = TerrainGpu(gl, ibo, planet)

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
     * and stations); the others follow beyond it. Returns the visible tile count.
     */
    fun prepare(gpu: TerrainGpu, camera: Camera, nearPlane: Float = camera.near, firstSliceFar: Float = 0f): Int {
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
        planSlices(lo, hi, nearPlane, firstSliceFar)
        lastPrepareNanos = System.nanoTime() - t0
        return n
    }

    /** Slices covering [lo, hi] with far/near ≤ [sliceRatio], nearest first in the arrays. */
    private fun planSlices(lo: Float, hi: Float, nearPlane: Float, firstFar: Float) {
        sliceCount = 0
        if (firstFar > 0f) {
            sliceNear[0] = nearPlane
            sliceFar[0] = firstFar
            sliceCount = 1
            if (visibleCount == 0 || hi <= firstFar) return
        } else if (visibleCount == 0 || hi <= lo) {
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
            d[13] = mod64(ox)
            d[14] = mod64(oy)
            d[15] = mod64(oz)
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
            drawRange(gpu, camera, sliceNear[k], sliceFar[k], viewProj)
        }
        gl.depthRange(0f, 1f)
        gl.bindVertexArray(0)
        lastDrawNanos = System.nanoTime() - t0
    }

    /**
     * The slices beyond the nearest one (scene integration, after [prepare] with a first slice):
     * far to near, each after a depth clear, then one more clear for the scene's near pass, which
     * draws slice 0 with [drawRange] next to the ship. Resets the counters.
     */
    fun drawFar(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float) {
        val t0 = System.nanoTime()
        drawCalls = 0
        trianglesDrawn = 0
        if (sliceCount > 1) {
            gl.enable(GL.DEPTH_TEST)
            gl.depthFunc(GL.LEQUAL)
            gl.depthMask(true)
            gl.enable(GL.CULL_FACE)
            gl.cullFace(GL.BACK)
            gl.disable(GL.BLEND)
            gl.depthRange(0f, 1f)
            begin(gpu, camera, light, time)
            val c = camera
            val aspect = c.width.toFloat() / max(c.height, 1)
            for (k in sliceCount - 1 downTo 1) {
                gl.clear(GL.DEPTH_BUFFER_BIT)
                proj.perspective(c.fovY, aspect, sliceNear[k], sliceFar[k], c.shiftX, c.shiftY)
                viewProj.mul(proj, c.view)
                drawRange(gpu, camera, sliceNear[k], sliceFar[k], viewProj)
            }
            gl.clear(GL.DEPTH_BUFFER_BIT)
            gl.bindVertexArray(0)
        }
        lastDrawNanos = System.nanoTime() - t0
    }

    /** Draws slice 0 with the scene's near projection [nearViewProj] (inside the near pass). */
    fun drawNear(gpu: TerrainGpu, camera: Camera, light: TerrainLight, time: Float, nearViewProj: Mat4) {
        if (sliceCount == 0) return
        val t0 = System.nanoTime()
        begin(gpu, camera, light, time)
        drawRange(gpu, camera, sliceNear[0], sliceFar[0], nearViewProj)
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
    }

    private fun smooth01(x: Double): Double {
        val t = x.coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun mod64(v: Double): Float {
        val r = v - 64.0 * kotlin.math.floor(v / 64.0)
        return r.toFloat()
    }

    companion object {
        const val DEPTH_CLEAR = 0
        const val DEPTH_RANGE = 1
        const val MAX_SLICES = 4

        /** A tile that appears because its parent split morphs in from the parent's shape over this time. */
        const val MORPH_SECONDS = 0.35
    }
}
