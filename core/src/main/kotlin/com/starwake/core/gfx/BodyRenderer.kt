package com.starwake.core.gfx

import com.starwake.core.game.StarSystem
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Real-scale star, planets and rings of a [StarSystem] (distances up to ~3e9 m, radii up to 6e7 m).
 *
 * Every body is a ray-cast sphere impostor: per frame the camera-relative centre is computed in
 * double and handed to the shader as a unit direction plus distance and |c|²/r² - 1 in units of
 * the body radius, so there is no depth buffer and no float precision problem at any distance or
 * altitude. Coverage geometry is a camera-facing quad in "scaled space" (1000 units from the
 * camera, only the direction matters) or, when the body fills a large part of the view or the
 * camera is inside its atmosphere, the fullscreen triangle.
 *
 * Surface textures are baked once per planet at construction (equirect RGBA8: sqrt-encoded albedo,
 * A = emission). They are mipmapped, but the shader picks the LOD from the pixel footprint
 * (textureLod), so the atan() seam never selects a wrong mip and small planets do not shimmer.
 * Construction renders into an FBO: afterwards the screen framebuffer is bound again but the
 * viewport is left at the bake size (set it before drawing). [fullscreenTriangle] stays owned by
 * the caller.
 */
class BodyRenderer(private val gl: Gl, system: StarSystem, quality: Int, fullscreenTriangle: GpuMesh) {
    private val fullscreen = fullscreenTriangle
    private val bodies: Array<StarSystem.Body> = system.bodies.toTypedArray()
    private val count = bodies.size
    private val type = IntArray(count) { typeCode(bodies[it].type) }
    private val star = type.indexOfFirst { it == T_STAR }

    private val texW = bakeWidth(quality)
    private val texH = texW / 2
    private val textures = IntArray(count)
    private val seeds = FloatArray(count * 4)

    /** Distance and angular radius of the star from the home position: reference for light falloff and glare. */
    private val refDistance: Double
    private val refStarAngle: Double

    private val planetProg = BodyProgram(gl, "bodyPlanet", BodyShaders.planetFs(if (quality == 0) 5 else 8, detailOctaves(quality)))
    private val ringProg = BodyProgram(gl, "bodyRing", BodyShaders.ringFs)
    private val starProg = BodyProgram(gl, "bodyStar", BodyShaders.starFs)
    private val glowProg = BodyProgram(gl, "bodyGlow", BodyShaders.glowFs)
    private val quad = GpuMesh(gl, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), intArrayOf(2))

    private val pToBody = planetProg.p.u("u_toBody")
    private val pSpin = planetProg.p.u("u_spin")
    private val pSunDir = planetProg.p.u("u_sunDir")
    private val pSunBody = planetProg.p.u("u_sunBody")
    private val pSunColor = planetProg.p.u("u_sunColor")
    private val pAtmo = planetProg.p.u("u_atmo")
    private val pAtmoK = planetProg.p.u("u_atmoK")
    private val pRing = planetProg.p.u("u_ring")
    private val pSeed = planetProg.p.u("u_seed")
    private val pType = planetProg.p.u("u_type")
    private val pTex = planetProg.p.u("u_tex")
    private val pTexScale = planetProg.p.u("u_texScale")

    private val rToBody = ringProg.p.u("u_toBody")
    private val rSunBody = ringProg.p.u("u_sunBody")
    private val rSunColor = ringProg.p.u("u_sunColor")
    private val rSunAng = ringProg.p.u("u_sunAng")
    private val rRing = ringProg.p.u("u_ring")

    private val sColor = starProg.p.u("u_color")
    private val gGlow = glowProg.p.u("u_glow")
    private val gColor = glowProg.p.u("u_color")
    private val gCorona = glowProg.p.u("u_corona")

    // per-frame scratch (render() allocates nothing)
    private val proj = Mat4()
    private val viewProj = Mat4()
    private val relX = DoubleArray(count)
    private val relY = DoubleArray(count)
    private val relZ = DoubleArray(count)
    private val dist = DoubleArray(count)
    private val order = IntArray(count)
    private val imp = Impostor()
    private val toBody = FloatArray(16).also { it[15] = 1f }
    private val axis = Vec3()
    private val sunBody = Vec3()
    private var pxAngle = 0.001
    private var halfDiag = 1.0
    private var useFull = false
    private var sunX = 0f
    private var sunY = 1f
    private var sunZ = 0f
    private var sunAng = 0f
    private var sunScale = 1f

    init {
        val home = system.home
        if (star >= 0) {
            val s = bodies[star].pos
            val dx = home.x - s.x
            val dy = home.y - s.y
            val dz = home.z - s.z
            refDistance = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(bodies[star].radius * 2.0)
            refStarAngle = asin(bodies[star].radius / refDistance)
        } else {
            refDistance = 1.0
            refStarAngle = 0.05
        }
        for (i in 0 until count) {
            val rng = Rng(bodies[i].seed * 7919L + 17L)
            seeds[i * 4] = rng.range(-40f, 40f)
            seeds[i * 4 + 1] = rng.range(-40f, 40f)
            seeds[i * 4 + 2] = rng.range(-40f, 40f)
            seeds[i * 4 + 3] = rng.nextFloat()
        }
        bake()
    }

    /** True if [quality] needs differently sized baked textures (then rebuild the renderer). */
    fun needsRebake(quality: Int): Boolean = bakeWidth(quality) != texW

    /**
     * Draws star glow, star and all planets (+rings) back-to-front with premultiplied alpha
     * blending and no depth test/writes. Expects the target framebuffer and viewport to be bound
     * and [camera] to be updated; leaves blending disabled and the depth mask on.
     */
    fun render(camera: Camera, time: Float, exposure: Float) {
        val c = camera
        pxAngle = 2.0 * c.tanHalfY / max(c.height, 1)
        val tx = c.tanHalfX * (1.0 + kotlin.math.abs(c.shiftX))
        val ty = c.tanHalfY * (1.0 + kotlin.math.abs(c.shiftY))
        halfDiag = atan(sqrt(tx * tx + ty * ty))
        // depth is unused: a projection that never clips the scaled-space quads
        proj.perspective(c.fovY, c.width.toFloat() / max(c.height, 1), 1f, 1.0e5f, c.shiftX, c.shiftY)
        viewProj.mul(proj, c.view)

        val cp = c.pos
        for (i in 0 until count) {
            val p = bodies[i].pos
            val x = p.x - cp.x
            val y = p.y - cp.y
            val z = p.z - cp.z
            relX[i] = x
            relY[i] = y
            relZ[i] = z
            dist[i] = sqrt(x * x + y * y + z * z)
            order[i] = i
        }
        BodyMath.sortFarToNear(order, dist, count)

        gl.disable(GL.DEPTH_TEST)
        gl.depthMask(false)
        gl.disable(GL.CULL_FACE)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
        for (k in 0 until count) {
            val i = order[k]
            if (type[i] == T_STAR) drawStar(i, c, exposure) else drawPlanet(i, c, time, exposure)
        }
        gl.disable(GL.BLEND)
        gl.depthMask(true)
    }

    // ------------------------------------------------------------------ planets

    private fun drawPlanet(i: Int, c: Camera, time: Float, exposure: Float) {
        val b = bodies[i]
        BodyMath.impostor(relX[i], relY[i], relZ[i], dist[i], b.radius, MIN_PLANET_PX * pxAngle, imp)
        sunFor(i)
        bodyMatrix(b.rot)
        val thick = if (b.atmoThickness > 1e-4f) b.atmoThickness else 0f
        val shell = 1.0 + thick
        val inside = imp.d <= shell
        val angle = if (inside) PI else asin(shell / imp.d) + MARGIN_PX * pxAngle
        val scR = SUN_COLOR_R * sunScale
        val scG = SUN_COLOR_G * sunScale
        val scB = SUN_COLOR_B * sunScale
        val ringed = b.hasRings && b.ringOuter > b.ringInner
        val ringIn = (b.ringInner / b.radius).toFloat()
        val ringOut = (b.ringOuter / b.radius).toFloat()

        if (coverage(planetProg, c, angle, inside, exposure)) {
            gl.uniformMatrix4fv(pToBody, toBody, 0)
            val spin = (b.spin.toDouble() * time) % TAU
            gl.uniform2f(pSpin, cos(spin).toFloat(), sin(spin).toFloat())
            gl.uniform3f(pSunDir, sunX, sunY, sunZ)
            gl.uniform3f(pSunBody, sunBody.x, sunBody.y, sunBody.z)
            gl.uniform3f(pSunColor, scR, scG, scB)
            val a = b.atmo
            // scattering colour: the tint squared (normalised) is closer to a λ⁻⁴ law than the tint itself
            val am = max(a[0], max(a[1], max(a[2], 1e-3f)))
            val br = a[0] / am
            val bg = a[1] / am
            val bb = a[2] / am
            gl.uniform4f(pAtmo, br * br, bg * bg, bb * bb, thick)
            // lava worlds: keep the haze thin so the dark crust and the glow read
            val haze = if (type[i] == BodyShaders.T_LAVA) 0.4f else 1f
            gl.uniform2f(pAtmoK, (thick * 4f).coerceIn(0.02f, 0.15f) * haze, 1f)
            gl.uniform4f(pRing, ringIn, ringOut, if (ringed) 1f else 0f, 0f)
            gl.uniform4f(pSeed, seeds[i * 4], seeds[i * 4 + 1], seeds[i * 4 + 2], seeds[i * 4 + 3])
            gl.uniform1i(pType, type[i])
            gl.activeTexture(GL.TEXTURE0)
            gl.bindTexture(GL.TEXTURE_2D, textures[i])
            gl.uniform1i(pTex, 0)
            gl.uniform1f(pTexScale, (texH / PI).toFloat())
            drawCoverage()
        }

        if (ringed) {
            val ro = ringOut.toDouble()
            val insideR = imp.d <= ro
            val angleR = if (insideR) PI else asin(ro / imp.d) + MARGIN_PX * pxAngle
            if (coverage(ringProg, c, angleR, insideR, exposure)) {
                gl.uniformMatrix4fv(rToBody, toBody, 0)
                gl.uniform3f(rSunBody, sunBody.x, sunBody.y, sunBody.z)
                gl.uniform3f(rSunColor, scR, scG, scB)
                gl.uniform1f(rSunAng, sunAng)
                gl.uniform4f(rRing, ringIn, ringOut, 1f, 0f)
                drawCoverage()
            }
        }
    }

    /** Direction, angular size and intensity of the star as seen from body [i]. */
    private fun sunFor(i: Int) {
        if (star < 0) {
            sunX = 0f; sunY = 1f; sunZ = 0f; sunAng = 0.01f; sunScale = 1f
            return
        }
        val s = bodies[star]
        val p = bodies[i].pos
        val dx = s.pos.x - p.x
        val dy = s.pos.y - p.y
        val dz = s.pos.z - p.z
        val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1.0)
        sunX = (dx / d).toFloat()
        sunY = (dy / d).toFloat()
        sunZ = (dz / d).toFloat()
        sunAng = asin(min(s.radius / d, 1.0)).toFloat()
        // gentle falloff: the inverse square law would leave the outer planets black
        sunScale = (refDistance / d).pow(0.35).toFloat().coerceIn(0.7f, 1.45f)
    }

    /** World -> body rotation (inverse of [rot]) as a column-major mat4, plus the body-space sun. */
    private fun bodyMatrix(rot: Quat) {
        val m = toBody
        rot.rotate(1f, 0f, 0f, axis)
        m[0] = axis.x; m[4] = axis.y; m[8] = axis.z
        rot.rotate(0f, 1f, 0f, axis)
        m[1] = axis.x; m[5] = axis.y; m[9] = axis.z
        rot.rotate(0f, 0f, 1f, axis)
        m[2] = axis.x; m[6] = axis.y; m[10] = axis.z
        rot.rotateInverse(sunX, sunY, sunZ, sunBody)
    }

    // ------------------------------------------------------------------ star

    private fun drawStar(i: Int, c: Camera, exposure: Float) {
        val b = bodies[i]
        BodyMath.impostor(relX[i], relY[i], relZ[i], dist[i], b.radius, MIN_STAR_PX * pxAngle, imp)
        val inside = imp.d <= 1.0 + 1e-6
        val disc = if (inside) PI / 2 else asin(1.0 / imp.d)
        val glare = max(imp.trueAngle, GLARE_MIN_PX * pxAngle)
        val fadeOut = min(disc * 2.0 + glare * 10.0, 1.45)
        val intensity = sqrt(imp.trueAngle / refStarAngle).toFloat().coerceIn(0.6f, 1.4f)
        val a = b.atmo
        if (coverage(glowProg, c, fadeOut, inside, exposure)) {
            gl.uniform4f(gGlow, disc.toFloat(), glare.toFloat(), intensity, fadeOut.toFloat())
            gl.uniform3f(gColor, 1.0f, 0.92f, 0.78f)
            gl.uniform3f(gCorona, a[0], a[1], a[2])
            drawCoverage()
        }
        val angle = if (inside) PI else disc + MARGIN_PX * pxAngle
        if (coverage(starProg, c, angle, inside, exposure)) {
            gl.uniform3f(sColor, STAR_INTENSITY, STAR_INTENSITY, STAR_INTENSITY)
            drawCoverage()
        }
    }

    // ------------------------------------------------------------------ coverage geometry

    /**
     * Binds [p] and its shared uniforms for the current body ([imp]) seen under [angle] radians
     * (silhouette + margins). Picks the fullscreen triangle when [inside] or the angle is large,
     * otherwise a quad around the body's direction. Returns false when it is off-screen.
     */
    private fun coverage(p: BodyProgram, c: Camera, angle: Double, inside: Boolean, exposure: Float): Boolean {
        val dx = imp.dirX
        val dy = imp.dirY
        val dz = imp.dirZ
        val full = inside || angle > FULLSCREEN_ANGLE
        if (!full) {
            val cosFwd = dx * c.forward.x + dy * c.forward.y + dz * c.forward.z
            val lim = angle + halfDiag
            if (lim < PI && cosFwd < cos(lim)) return false
        }
        p.p.use()
        gl.uniformMatrix4fv(p.viewProj, viewProj.m, 0)
        gl.uniform3f(p.fwd, c.forward.x, c.forward.y, c.forward.z)
        gl.uniform3f(p.right, c.right.x * c.tanHalfX, c.right.y * c.tanHalfX, c.right.z * c.tanHalfX)
        gl.uniform3f(p.up, c.up.x * c.tanHalfY, c.up.y * c.tanHalfY, c.up.z * c.tanHalfY)
        gl.uniform2f(p.shift, c.shiftX, c.shiftY)
        useFull = full
        if (full) {
            gl.uniform1f(p.full, 1f)
        } else {
            gl.uniform1f(p.full, 0f)
            // quad basis: right = dir x camUp, up = right x dir (fallback when looking along camUp)
            var rx = dy * c.up.z - dz * c.up.y
            var ry = dz * c.up.x - dx * c.up.z
            var rz = dx * c.up.y - dy * c.up.x
            var rl = sqrt(rx * rx + ry * ry + rz * rz)
            if (rl < 1e-3f) {
                val k = c.right.x * dx + c.right.y * dy + c.right.z * dz
                rx = c.right.x - dx * k
                ry = c.right.y - dy * k
                rz = c.right.z - dz * k
                rl = sqrt(rx * rx + ry * ry + rz * rz)
            }
            rx /= rl; ry /= rl; rz /= rl
            val ux = ry * dz - rz * dy
            val uy = rz * dx - rx * dz
            val uz = rx * dy - ry * dx
            val half = (SCALED_DISTANCE * tan(angle)).toFloat()
            val s = SCALED_DISTANCE.toFloat()
            gl.uniform3f(p.qCenter, dx * s, dy * s, dz * s)
            gl.uniform3f(p.qRight, rx * half, ry * half, rz * half)
            gl.uniform3f(p.qUp, ux * half, uy * half, uz * half)
        }
        gl.uniform4f(p.center, dx, dy, dz, imp.d.toFloat())
        gl.uniform1f(p.cc, imp.cc.toFloat())
        gl.uniform1f(p.pxR, (pxAngle * sqrt(imp.cc)).toFloat())
        gl.uniform1f(p.pxAngle, pxAngle.toFloat())
        gl.uniform1f(p.exposure, exposure)
        return true
    }

    private fun drawCoverage() {
        if (useFull) fullscreen.draw() else quad.draw(GL.TRIANGLE_STRIP)
    }

    // ------------------------------------------------------------------ one-time bakes

    private fun bake() {
        val prog = ShaderProgram(gl, "bodyBake", BodyShaders.bakeVs, BodyShaders.bakeFs)
        val uType = prog.u("u_type")
        val uSeed = prog.u("u_seed")
        val uRect = prog.u("u_rect")
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.BLEND)
        gl.disable(GL.CULL_FACE)
        prog.use()
        for (i in 0 until count) {
            if (type[i] == T_STAR) continue
            val tex = gl.genTexture()
            gl.bindTexture(GL.TEXTURE_2D, tex)
            gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, texW, texH, GL.RGBA, GL.UNSIGNED_BYTE, null)
            // mipmapped, but the shader picks the LOD itself (textureLod): derivative-based LOD would
            // jump to the smallest mip along the atan() seam
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex, 0)
            val status = gl.checkFramebufferStatus(GL.FRAMEBUFFER)
            check(status == GL.FRAMEBUFFER_COMPLETE) { "body bake framebuffer incomplete: 0x${status.toString(16)}" }
            gl.uniform1i(uType, type[i])
            gl.uniform4f(uSeed, seeds[i * 4], seeds[i * 4 + 1], seeds[i * 4 + 2], seeds[i * 4 + 3])
            for (s in 0 until BAKE_STRIPS) {
                val y0 = s * texH / BAKE_STRIPS
                val y1 = (s + 1) * texH / BAKE_STRIPS
                gl.viewport(0, y0, texW, y1 - y0)
                gl.uniform2f(uRect, y0.toFloat() / texH, (y1 - y0).toFloat() / texH)
                fullscreen.draw()
            }
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, 0, 0)
            gl.bindTexture(GL.TEXTURE_2D, tex)
            gl.generateMipmap(GL.TEXTURE_2D)
            textures[i] = tex
        }
        gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
        gl.deleteFramebuffer(fbo)
        prog.dispose()
    }

    fun dispose() {
        planetProg.p.dispose()
        ringProg.p.dispose()
        starProg.p.dispose()
        glowProg.p.dispose()
        quad.dispose()
        for (t in textures) if (t != 0) gl.deleteTexture(t)
    }

    companion object {
        const val T_STAR = 0

        /** Planets never shrink below this apparent radius (px), so they stay visible as dots. */
        const val MIN_PLANET_PX = 1.5
        const val MIN_STAR_PX = 2.0
        private const val GLARE_MIN_PX = 7.0
        private const val MARGIN_PX = 3.0
        private const val SCALED_DISTANCE = 1000.0
        private val FULLSCREEN_ANGLE = 35.0 * PI / 180.0
        private const val TAU = 2.0 * PI
        private const val BAKE_STRIPS = 8
        private const val SUN_COLOR_R = 2.6f
        private const val SUN_COLOR_G = 2.45f
        private const val SUN_COLOR_B = 2.25f
        private const val STAR_INTENSITY = 4f

        fun bakeWidth(quality: Int) = if (quality == 0) 512 else 1024
        fun detailOctaves(quality: Int) = when (quality) {
            0 -> 0
            1 -> 3
            else -> 4
        }

        fun typeCode(t: StarSystem.BodyType): Int = when (t) {
            StarSystem.BodyType.STAR -> T_STAR
            StarSystem.BodyType.LAVA -> BodyShaders.T_LAVA
            StarSystem.BodyType.TERRAN -> BodyShaders.T_TERRAN
            StarSystem.BodyType.GAS -> BodyShaders.T_GAS
            StarSystem.BodyType.ICE -> BodyShaders.T_ICE
            StarSystem.BodyType.ROCK -> BodyShaders.T_ROCK
        }
    }
}

/** A program with the shared [BodyShaders.vs] and the uniforms every body shader uses. */
private class BodyProgram(gl: Gl, name: String, fs: String) {
    val p = ShaderProgram(gl, name, BodyShaders.vs, fs)
    val viewProj = p.u("u_viewProj")
    val qCenter = p.u("u_qCenter")
    val qRight = p.u("u_qRight")
    val qUp = p.u("u_qUp")
    val full = p.u("u_full")
    val fwd = p.u("u_fwd")
    val right = p.u("u_right")
    val up = p.u("u_up")
    val shift = p.u("u_shift")
    val center = p.u("u_center")
    val cc = p.u("u_cc")
    val pxR = p.u("u_pxR")
    val pxAngle = p.u("u_pxAngle")
    val exposure = p.u("u_exposure")
}

/** Camera-relative description of one body in units of its (possibly enlarged) radius. */
internal class Impostor {
    var dirX = 0f
    var dirY = 0f
    var dirZ = -1f

    /** Distance to the centre in effective radii. */
    var d = 2.0

    /** d² - 1, computed without cancellation (the shader's c·c - 1). */
    var cc = 3.0

    /** Effective radius in metres (≥ the true radius when the minimum apparent size applies). */
    var rEff = 1.0

    /** True angular radius (rad); PI/2 when the camera is inside. */
    var trueAngle = 0.0
}

/** Pure math of [BodyRenderer], kept separate so it can be unit tested without GL. */
internal object BodyMath {
    /**
     * Fills [out] for a body of [radius] metres whose centre is at ([cx], [cy], [cz]) metres from
     * the camera ([dist] = its length). Bodies smaller than [minAngle] radians are enlarged to that
     * apparent size; a camera inside the body is treated as standing on its surface.
     */
    fun impostor(cx: Double, cy: Double, cz: Double, dist: Double, radius: Double, minAngle: Double, out: Impostor) {
        out.trueAngle = if (dist > radius) asin(radius / dist) else PI / 2
        var r = radius
        if (out.trueAngle < minAngle) r = dist * sin(minAngle)
        var d = dist
        val minD = r * (1.0 + 1e-7)
        if (d < minD) d = minD
        if (dist > 0.0) {
            out.dirX = (cx / dist).toFloat()
            out.dirY = (cy / dist).toFloat()
            out.dirZ = (cz / dist).toFloat()
        } else {
            out.dirX = 0f; out.dirY = 0f; out.dirZ = -1f
        }
        out.rEff = r
        out.d = d / r
        out.cc = (d - r) * (d + r) / (r * r)
    }

    /** Sorts [order] (first [n] entries) by descending [dist] of the body indices; insertion sort, no allocation. */
    fun sortFarToNear(order: IntArray, dist: DoubleArray, n: Int) {
        for (i in 1 until n) {
            val v = order[i]
            val dv = dist[v]
            var j = i - 1
            while (j >= 0 && dist[order[j]] < dv) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = v
        }
    }
}
