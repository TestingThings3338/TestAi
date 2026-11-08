package com.starwake.core.gfx

import com.starwake.core.content.AsteroidMesh
import com.starwake.core.content.Content
import com.starwake.core.content.DustField
import com.starwake.core.content.KiteShip
import com.starwake.core.content.Liveries
import com.starwake.core.content.Primitives
import com.starwake.core.content.StarField
import com.starwake.core.game.ShipState
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Vec3
import kotlin.math.ln
import kotlin.math.sin

/** Fixed lighting setup of the starting system. */
object Environment {
    val sunDir: Vec3 = Vec3(-0.846f, 0.451f, 0.282f).normalize()
    val sunColor = floatArrayOf(2.6f, 2.45f, 2.25f)
    val planetDir: Vec3 = Vec3(0.45f, 0.10f, -1f).normalize()
    val fillColor = floatArrayOf(0.10f, 0.075f, 0.055f)
    val ambient = floatArrayOf(0.035f, 0.042f, 0.06f)
    const val PLANET_DISTANCE = 1000f
    const val PLANET_ANGULAR_RADIUS = 13.5f * DEG2RAD
    val planetRot: Quat = Quat().also { q ->
        // tilt the spin axis towards the viewer so the rings open up, plus a little roll
        val tilt = Quat().setAxisAngle(1f, 0f, -0.45f, -0.42f)
        val roll = Quat().setAxisAngle(planetDir.x, planetDir.y, planetDir.z, 0.32f)
        q.mul(roll, tilt)
    }
}

/** Per-frame scene parameters supplied by the game. */
class SceneFrame {
    val camera = Camera()
    var time = 0f
    var ship: ShipState? = null
    var livery = 0
    var quality = 2
    var pxScale = 1f
    val dustVelocity = Vec3()
    var dustIntensity = 0.6f
    var exposure = 1f
}

/**
 * Renders the 3D scene. Everything is drawn camera-relative. Typical frame cost: ~12 draw calls,
 * one texture lookup for the background, no post-processing passes.
 */
class SceneRenderer(private val gl: Gl, private val content: Content, quality: Int) {
    private val lit = LitProgram(gl, false)
    private val litInst = LitProgram(gl, true)
    private val sky = ShaderProgram(gl, "sky", Shaders.skyVs, Shaders.skyFs)
    private val star = ShaderProgram(gl, "stars", Shaders.starVs, Shaders.starFs)
    private val planet = ShaderProgram(gl, "planet", Shaders.planetVs, Shaders.planetFs)
    private val ring = ShaderProgram(gl, "ring", Shaders.planetVs, Shaders.ringFs)
    private val dust = ShaderProgram(gl, "dust", Shaders.dustVs, Shaders.dustFs)
    private val glow = ShaderProgram(gl, "glow", Shaders.glowVs, Shaders.glowFs)

    private val fullscreen = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
    private val shipMesh = GpuMesh(gl, content.ship, intArrayOf(3, 3, 2))
    private val rockMeshes = Array(content.rocks.size) { v ->
        GpuMesh(gl, content.rocks[v], intArrayOf(3, 3, 2)).also {
            val f = content.field
            it.setInstanceData(f.instanceData[v], f.instanceCount[v], intArrayOf(4, 4), 4)
        }
    }
    private val starCount = when (quality) {
        0 -> 3000
        1 -> 4500
        else -> Content.MAX_STARS
    }
    private val starMesh = GpuMesh(gl, content.stars.copyOf(starCount * StarField.FLOATS), intArrayOf(3, 4))
    private val dustCount = when (quality) {
        0 -> 300
        1 -> 500
        else -> Content.MAX_DUST
    }
    private val dustMesh = GpuMesh(
        gl, content.dust.first.copyOf(dustCount * 20), intArrayOf(3, 2),
        content.dust.second.copyOf(dustCount * 6),
    )
    private val sphereMesh = GpuMesh(gl, content.sphere.first, intArrayOf(3), content.sphere.second)
    private val ringMesh = GpuMesh(gl, content.ring.first, intArrayOf(3), content.ring.second)
    private val glowStream = QuadStream(gl, 48, intArrayOf(3, GL.FLOAT, 3, GL.FLOAT, 4, GL.FLOAT))

    private val envSize = if (quality == 0) 256 else 512
    private val envTex = bakeEnvironment(envSize)
    private val planetTex = bakePlanet(if (quality == 0) 1024 else 2048, if (quality == 0) 512 else 1024)
    private val envLod = ln(envSize.toFloat()) / ln(2f) - 1.5f
    val quality = quality

    // scratch (no per-frame allocations)
    private val model = Mat4()
    private val skyModel = Mat4()
    private val rel = Vec3()
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val side = Vec3()
    private val axis = Vec3()
    private val shipPalette = FloatArray(40)
    private val shipParams = FloatArray(40)
    private val rockPalette = FloatArray(40)
    private val rockParams = FloatArray(40)
    private val identityRot = Quat()
    private val sunObj = Vec3()

    init {
        setupPalettes(0)
        rockMaterial(AsteroidMesh.ROCK, 0.30f, 0.27f, 0.24f, 0.88f, 0.05f)
        rockMaterial(AsteroidMesh.DARK_ROCK, 0.16f, 0.145f, 0.135f, 0.92f, 0.05f)
        rockMaterial(AsteroidMesh.MINERAL, 0.46f, 0.50f, 0.56f, 0.35f, 0.45f)
    }

    private fun rockMaterial(i: Int, r: Float, g: Float, b: Float, rough: Float, metal: Float) {
        rockPalette[i * 4] = r; rockPalette[i * 4 + 1] = g; rockPalette[i * 4 + 2] = b; rockPalette[i * 4 + 3] = rough
        rockParams[i * 4] = metal
    }

    private var paletteLivery = -1

    private fun setupPalettes(livery: Int) {
        if (livery == paletteLivery) return
        paletteLivery = livery
        val l = Liveries.all[livery.coerceIn(0, Liveries.all.size - 1)]
        fun mat(i: Int, r: Float, g: Float, b: Float, rough: Float, metal: Float, emissive: Float = 0f, engine: Float = 0f) {
            shipPalette[i * 4] = r; shipPalette[i * 4 + 1] = g; shipPalette[i * 4 + 2] = b; shipPalette[i * 4 + 3] = rough
            shipParams[i * 4] = metal; shipParams[i * 4 + 1] = emissive; shipParams[i * 4 + 2] = engine
        }
        mat(KiteShip.PRIMARY, l.primary[0], l.primary[1], l.primary[2], 0.40f, 0.15f)
        mat(KiteShip.SECONDARY, l.secondary[0], l.secondary[1], l.secondary[2], 0.36f, 0.2f)
        mat(KiteShip.DARK, 0.075f, 0.08f, 0.09f, 0.55f, 0.5f)
        mat(KiteShip.GLASS, 0.035f, 0.07f, 0.10f, 0.06f, 0.85f, emissive = 1.2f)
        mat(KiteShip.ENGINE, 0.45f, 0.82f, 1.0f, 1f, 0f, emissive = 3.2f, engine = 1f)
        mat(KiteShip.LIGHT, 0.30f, 0.90f, 1.0f, 1f, 0f, emissive = 2.4f)
        mat(KiteShip.METAL, 0.62f, 0.64f, 0.66f, 0.28f, 1f)
        mat(KiteShip.RED, 1.0f, 0.12f, 0.10f, 1f, 0f, emissive = 3f)
        mat(KiteShip.GREEN, 0.10f, 1.0f, 0.30f, 1f, 0f, emissive = 3f)
        mat(KiteShip.WHITE, 1f, 1f, 1f, 1f, 0f, emissive = 2.5f)
    }

    fun render(f: SceneFrame) {
        val cam = f.camera
        val w = cam.width
        val h = cam.height
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.viewport(0, 0, w, h)
        gl.depthMask(true)
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(GL.COLOR_BUFFER_BIT or GL.DEPTH_BUFFER_BIT)
        gl.disable(GL.BLEND)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)

        drawBackground(f)
        drawStars(f)
        drawSun(f)
        drawPlanet(f)

        gl.depthMask(true)
        gl.clear(GL.DEPTH_BUFFER_BIT)
        gl.enable(GL.DEPTH_TEST)
        gl.depthFunc(GL.LEQUAL)
        gl.enable(GL.CULL_FACE)
        gl.cullFace(GL.BACK)
        drawAsteroids(f)
        val ship = f.ship
        if (ship != null) drawShip(f, ship)
        drawDust(f)
        if (ship != null) drawShipGlows(f, ship)
        gl.depthMask(true)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        gl.disable(GL.BLEND)
    }

    // ------------------------------------------------------------------ background layers

    private fun drawBackground(f: SceneFrame) {
        val c = f.camera
        sky.use()
        gl.uniform3f(sky.u("u_fwd"), c.forward.x, c.forward.y, c.forward.z)
        gl.uniform3f(sky.u("u_right"), c.right.x * c.tanHalfX, c.right.y * c.tanHalfX, c.right.z * c.tanHalfX)
        gl.uniform3f(sky.u("u_up"), c.up.x * c.tanHalfY, c.up.y * c.tanHalfY, c.up.z * c.tanHalfY)
        gl.uniform2f(sky.u("u_shift"), c.shiftX, c.shiftY)
        gl.uniform1f(sky.u("u_exposure"), f.exposure)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, envTex)
        gl.uniform1i(sky.u("u_env"), 0)
        fullscreen.draw()
    }

    private fun drawStars(f: SceneFrame) {
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        star.use()
        gl.uniformMatrix4fv(star.u("u_viewProj"), f.camera.viewProj.m, 0)
        gl.uniform1f(star.u("u_pxScale"), f.pxScale)
        gl.uniform1f(star.u("u_bright"), 1f)
        starMesh.draw(GL.POINTS)
        gl.disable(GL.BLEND)
    }

    private fun drawSun(f: SceneFrame) {
        val c = f.camera
        val s = Environment.sunDir
        val d = 1000f
        val size = 150f
        glowStream.begin()
        billboard(s.x * d, s.y * d, s.z * d, size, c, 2f, 1f, 0.96f, 0.88f, 1f)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        glow.use()
        gl.uniformMatrix4fv(glow.u("u_viewProj"), c.skyViewProj.m, 0)
        gl.uniform1f(glow.u("u_time"), f.time)
        glowStream.flush()
        gl.disable(GL.BLEND)
    }

    private fun drawPlanet(f: SceneFrame) {
        val c = f.camera
        val p = Environment.planetDir
        val d = Environment.PLANET_DISTANCE
        val r = d * sin(Environment.PLANET_ANGULAR_RADIUS)
        val rot = Environment.planetRot
        skyModel.setTRS(p.x * d, p.y * d, p.z * d, rot, r)
        rot.rotateInverse(Environment.sunDir, sunObj)
        val sc = Environment.sunColor

        gl.enable(GL.DEPTH_TEST)
        gl.depthFunc(GL.LEQUAL)
        gl.depthMask(true)
        gl.enable(GL.CULL_FACE)
        gl.cullFace(GL.BACK)
        planet.use()
        gl.uniformMatrix4fv(planet.u("u_viewProj"), c.skyViewProj.m, 0)
        gl.uniformMatrix4fv(planet.u("u_model"), skyModel.m, 0)
        gl.uniform3f(planet.u("u_sunDir"), Environment.sunDir.x, Environment.sunDir.y, Environment.sunDir.z)
        gl.uniform3f(planet.u("u_sunObj"), sunObj.x, sunObj.y, sunObj.z)
        gl.uniform3f(planet.u("u_sunColor"), sc[0], sc[1], sc[2])
        gl.uniform3f(planet.u("u_atmo"), 0.35f, 0.5f, 0.75f)
        gl.uniform1f(planet.u("u_spin"), f.time * 0.0025f)
        gl.uniform1f(planet.u("u_exposure"), f.exposure)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_2D, planetTex)
        gl.uniform1i(planet.u("u_tex"), 0)
        sphereMesh.draw()

        gl.disable(GL.CULL_FACE)
        gl.depthMask(false)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.SRC_ALPHA, GL.ONE_MINUS_SRC_ALPHA)
        ring.use()
        gl.uniformMatrix4fv(ring.u("u_viewProj"), c.skyViewProj.m, 0)
        gl.uniformMatrix4fv(ring.u("u_model"), skyModel.m, 0)
        gl.uniform3f(ring.u("u_sunObj"), sunObj.x, sunObj.y, sunObj.z)
        gl.uniform3f(ring.u("u_sunColor"), sc[0], sc[1], sc[2])
        gl.uniform1f(ring.u("u_exposure"), f.exposure)
        ringMesh.draw()
        gl.disable(GL.BLEND)
        gl.depthMask(true)
        gl.disable(GL.DEPTH_TEST)
    }

    // ------------------------------------------------------------------ near scene

    private fun drawAsteroids(f: SceneFrame) {
        val c = f.camera
        val field = content.field
        field.origin.relativeTo(c.pos, rel)
        model.setTRS(rel.x, rel.y, rel.z, identityRot, 1f)
        litInst.begin(c, model, f, envTex, envLod, rockPalette, rockParams, 0f)
        for (v in rockMeshes.indices) rockMeshes[v].drawInstanced(field.drawCount(v, quality))
    }

    private fun drawShip(f: SceneFrame, ship: ShipState) {
        setupPalettes(f.livery)
        val c = f.camera
        ship.pos.relativeTo(c.pos, rel)
        model.setTRS(rel.x, rel.y, rel.z, ship.rot, 1f)
        val engine = 0.25f + ship.engineOutput * 0.9f
        lit.begin(c, model, f, envTex, envLod, shipPalette, shipParams, engine)
        shipMesh.draw()
    }

    private fun drawDust(f: SceneFrame) {
        val c = f.camera
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        dust.use()
        gl.uniformMatrix4fv(dust.u("u_viewProj"), c.viewProj.m, 0)
        val b = DustField.BOX.toDouble()
        gl.uniform3f(
            dust.u("u_camMod"),
            (((c.pos.x % b) + b) % b).toFloat(),
            (((c.pos.y % b) + b) % b).toFloat(),
            (((c.pos.z % b) + b) % b).toFloat(),
        )
        val v = f.dustVelocity
        gl.uniform3f(dust.u("u_streak"), -v.x * 0.045f, -v.y * 0.045f, -v.z * 0.045f)
        gl.uniform1f(dust.u("u_box"), DustField.BOX)
        gl.uniform1f(dust.u("u_width"), 1.3f * f.pxScale * 2f / c.height)
        gl.uniform1f(dust.u("u_aspect"), c.width.toFloat() / c.height)
        gl.uniform1f(dust.u("u_intensity"), f.dustIntensity)
        dustMesh.draw()
    }

    private fun drawShipGlows(f: SceneFrame, ship: ShipState) {
        val c = f.camera
        val t = f.time
        glowStream.begin()
        val out = ship.engineOutput
        val boost = ship.boosting
        // exhaust direction (+Z in ship space)
        ship.rot.rotate(0f, 0f, 1f, axis)
        for (n in KiteShip.nozzles) {
            ship.rot.rotate(n, tmp)
            ship.pos.relativeTo(c.pos, rel)
            val px = rel.x + tmp.x
            val py = rel.y + tmp.y
            val pz = rel.z + tmp.z
            val len = 2.2f + out * 7.5f
            val width = KiteShip.NOZZLE_RADIUS * (1.0f + out * 0.25f)
            val cr = if (boost) 0.75f else 0.35f
            val cg = if (boost) 0.75f else 0.68f
            plume(px, py, pz, len, width, 0.25f + out * 0.85f, cr, cg, 1f)
            val halo = 1.3f + out * 1.2f
            billboard(px, py, pz, halo, c, 0f, 0.35f, 0.7f, 1f, 0.35f + out * 0.5f)
        }
        // navigation lights: steady red/green, white strobes on the fins
        light(ship, KiteShip.navGreen, c, 0.1f, 1f, 0.3f, 0.9f, 0.55f)
        light(ship, KiteShip.navRed, c, 1f, 0.12f, 0.1f, 0.9f, 0.55f)
        val phase = (t % 1.4f)
        if (phase < 0.08f) {
            for (s in KiteShip.strobes) light(ship, s, c, 1f, 1f, 1f, 1.6f, 1.2f)
        }
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        gl.enable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        glow.use()
        gl.uniformMatrix4fv(glow.u("u_viewProj"), c.viewProj.m, 0)
        gl.uniform1f(glow.u("u_time"), t)
        glowStream.flush()
        gl.enable(GL.CULL_FACE)
    }

    private fun light(ship: ShipState, local: Vec3, c: Camera, r: Float, g: Float, b: Float, intensity: Float, size: Float) {
        ship.rot.rotate(local, tmp)
        ship.pos.relativeTo(c.pos, rel)
        billboard(rel.x + tmp.x, rel.y + tmp.y, rel.z + tmp.z, size, c, 0f, r, g, b, intensity)
    }

    /** Camera-facing quad. uv spans -1..1. */
    private fun billboard(
        x: Float, y: Float, z: Float, size: Float, c: Camera, shape: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val rx = c.right.x * size
        val ry = c.right.y * size
        val rz = c.right.z * size
        val ux = c.up.x * size
        val uy = c.up.y * size
        val uz = c.up.z * size
        glowVertex(x - rx - ux, y - ry - uy, z - rz - uz, -1f, -1f, shape, r, g, b, a)
        glowVertex(x + rx - ux, y + ry - uy, z + rz - uz, 1f, -1f, shape, r, g, b, a)
        glowVertex(x - rx + ux, y - ry + uy, z - rz + uz, -1f, 1f, shape, r, g, b, a)
        glowVertex(x + rx + ux, y + ry + uy, z + rz + uz, 1f, 1f, shape, r, g, b, a)
        glowStream.endQuad()
    }

    /** Exhaust plume: a quad along [axis] that turns around it to face the camera. */
    private fun plume(x: Float, y: Float, z: Float, len: Float, width: Float, a: Float, r: Float, g: Float, b: Float) {
        // to-camera vector is -position (camera at origin)
        tmp2.set(-x, -y, -z).normalize()
        side.cross(axis, tmp2)
        val s = side.length()
        if (s < 0.05f) return // looking straight down the exhaust: halo covers it
        side.scale(width / s)
        val ex = x + axis.x * len
        val ey = y + axis.y * len
        val ez = z + axis.z * len
        val alpha = a * minOf(1f, s * 1.5f)
        glowVertex(x - side.x, y - side.y, z - side.z, 0f, -1f, 1f, r, g, b, alpha)
        glowVertex(x + side.x, y + side.y, z + side.z, 0f, 1f, 1f, r, g, b, alpha)
        glowVertex(ex - side.x, ey - side.y, ez - side.z, 1f, -1f, 1f, r, g, b, alpha)
        glowVertex(ex + side.x, ey + side.y, ez + side.z, 1f, 1f, 1f, r, g, b, alpha)
        glowStream.endQuad()
    }

    private fun glowVertex(x: Float, y: Float, z: Float, u: Float, v: Float, shape: Float, r: Float, g: Float, b: Float, a: Float) {
        val d = glowStream.staging
        var o = glowStream.cursor
        d[o++] = x.toRawBits(); d[o++] = y.toRawBits(); d[o++] = z.toRawBits()
        d[o++] = u.toRawBits(); d[o++] = v.toRawBits(); d[o++] = shape.toRawBits()
        d[o++] = r.toRawBits(); d[o++] = g.toRawBits(); d[o++] = b.toRawBits(); d[o++] = a.toRawBits()
        glowStream.cursor = o
    }

    // ------------------------------------------------------------------ one-time bakes

    private fun bakeEnvironment(size: Int): Int {
        val tex = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, tex)
        for (face in 0 until 6) {
            gl.texImage2D(GL.TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, GL.RGBA8, size, size, GL.RGBA, GL.UNSIGNED_BYTE, null)
        }
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_CUBE_MAP, GL.TEXTURE_WRAP_R, GL.CLAMP_TO_EDGE)
        val prog = ShaderProgram(gl, "nebula", Shaders.bakeVs, Shaders.nebulaFs)
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.viewport(0, 0, size, size)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.BLEND)
        gl.disable(GL.CULL_FACE)
        prog.use()
        val uz = prog.u("u_fz")
        val ux = prog.u("u_fx")
        val uy = prog.u("u_fy")
        for (face in 0 until 6) {
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_CUBE_MAP_POSITIVE_X + face, tex, 0)
            val b = FACE_BASIS[face]
            gl.uniform3f(uz, b[0], b[1], b[2])
            gl.uniform3f(ux, b[3], b[4], b[5])
            gl.uniform3f(uy, b[6], b[7], b[8])
            fullscreen.draw()
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        prog.dispose()
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, tex)
        gl.generateMipmap(GL.TEXTURE_CUBE_MAP)
        return tex
    }

    private fun bakePlanet(w: Int, h: Int): Int {
        val tex = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_2D, tex)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, w, h, GL.RGBA, GL.UNSIGNED_BYTE, null)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        val prog = ShaderProgram(gl, "gasgiant", Shaders.bakeVs, Shaders.gasGiantFs)
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex, 0)
        gl.viewport(0, 0, w, h)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.BLEND)
        prog.use()
        fullscreen.draw()
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        prog.dispose()
        return tex
    }

    fun dispose() {
        lit.dispose(); litInst.dispose()
        for (p in arrayOf(sky, star, planet, ring, dust, glow)) p.dispose()
        fullscreen.dispose(); shipMesh.dispose(); starMesh.dispose(); dustMesh.dispose()
        sphereMesh.dispose(); ringMesh.dispose(); glowStream.dispose()
        for (m in rockMeshes) m.dispose()
        gl.deleteTexture(envTex)
        gl.deleteTexture(planetTex)
    }

    companion object {
        /** Cube face basis (faceZ, faceX, faceY) matching GL's cube map face orientation. */
        private val FACE_BASIS = arrayOf(
            floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, -1f, 0f),
            floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, -1f, 0f),
            floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f),
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, -1f, 0f),
            floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, -1f, 0f),
        )
    }
}

/** Uniform bindings shared by the ship and asteroid programs. */
private class LitProgram(private val gl: Gl, instanced: Boolean) {
    val prog = ShaderProgram(gl, if (instanced) "litInstanced" else "lit", Shaders.litVs(instanced), Shaders.litFs)
    private val uViewProj = prog.u("u_viewProj")
    private val uModel = prog.u("u_model")
    private val uPalette = prog.u("u_palette")
    private val uParams = prog.u("u_matParams")
    private val uTime = prog.u("u_time")
    private val uSunDir = prog.u("u_sunDir")
    private val uSunColor = prog.u("u_sunColor")
    private val uFillDir = prog.u("u_fillDir")
    private val uFillColor = prog.u("u_fillColor")
    private val uAmbient = prog.u("u_ambient")
    private val uEngine = prog.u("u_engine")
    private val uExposure = prog.u("u_exposure")
    private val uEnvLod = prog.u("u_envLod")
    private val uEnv = prog.u("u_env")

    fun begin(c: Camera, model: Mat4, f: SceneFrame, env: Int, envLod: Float, palette: FloatArray, params: FloatArray, engine: Float) {
        prog.use()
        gl.uniformMatrix4fv(uViewProj, c.viewProj.m, 0)
        gl.uniformMatrix4fv(uModel, model.m, 0)
        gl.uniform4fv(uPalette, 10, palette, 0)
        gl.uniform4fv(uParams, 10, params, 0)
        gl.uniform1f(uTime, f.time)
        val s = Environment.sunDir
        gl.uniform3f(uSunDir, s.x, s.y, s.z)
        val sc = Environment.sunColor
        gl.uniform3f(uSunColor, sc[0], sc[1], sc[2])
        val p = Environment.planetDir
        gl.uniform3f(uFillDir, p.x, p.y, p.z)
        val fc = Environment.fillColor
        gl.uniform3f(uFillColor, fc[0], fc[1], fc[2])
        val a = Environment.ambient
        gl.uniform3f(uAmbient, a[0], a[1], a[2])
        gl.uniform1f(uEngine, engine)
        gl.uniform1f(uExposure, f.exposure)
        gl.uniform1f(uEnvLod, envLod)
        gl.activeTexture(GL.TEXTURE0)
        gl.bindTexture(GL.TEXTURE_CUBE_MAP, env)
        gl.uniform1i(uEnv, 0)
    }

    fun dispose() = prog.dispose()
}
