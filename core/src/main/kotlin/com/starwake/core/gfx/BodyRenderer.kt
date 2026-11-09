package com.starwake.core.gfx

import com.starwake.core.game.StarSystem
import com.starwake.core.world.Palettes
import com.starwake.core.world.RingProfiles
import com.starwake.core.math.Mat4
import com.starwake.core.math.Quat
import com.starwake.core.math.Rng
import com.starwake.core.math.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
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
 * camera, only the direction matters) or, when the body covers the whole view or the camera is
 * inside its atmosphere, the fullscreen triangle.
 *
 * A daylight sky is opaque (it hides the stars and the star disc drawn behind it); the planet
 * shader puts the star back through the sky's transmittance, and [skyFade] tells the caller how
 * much to dim its own star field / nebula.
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

    /** Everything [quality] decides at construction (bake size, compiled loop counts); see [needsRebake]. */
    private val config = configKey(quality)
    private val atmoSteps = atmoSteps(quality)
    private val texW = bakeWidth(quality)

    /** Surface texture per body and its height (texels): the big planets get the full bake size, moons half, the tiny ones a quarter. */
    private val textures = IntArray(count)
    private val texHeight = IntArray(count) { bakeHeight(texW, bodies[it].radius) }
    private val seeds = FloatArray(count * 4)

    /** Ring opacity profiles (128 x 1, mipmapped) and linear ring colours of the ringed bodies. */
    private val ringTex = IntArray(count)
    private val ringColor = FloatArray(count * 3)

    /** Per body this frame: the occluder's camera-relative centre and radius in its own radii (w = 0: none). */
    private val occ = FloatArray(count * 4)

    /** Distance and angular radius of the star from the home planet's orbit: reference for light falloff and glare. */
    private val refDistance: Double
    private val refStarAngle: Double

    /** The star's data colours (0..1): the disc and the corona. */
    private val discR: Float
    private val discG: Float
    private val discB: Float
    private val coronaR: Float
    private val coronaG: Float
    private val coronaB: Float

    private val planetProg = BodyProgram(gl, "bodyPlanet", BodyShaders.planetFs(atmoSteps, detailOctaves(quality)))
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
    private val pSunCam = planetProg.p.u("u_sunCam")
    private val pSunGlare = planetProg.p.u("u_sunGlare")
    private val pGlareColor = planetProg.p.u("u_glareColor")
    private val pCoronaColor = planetProg.p.u("u_coronaColor")
    private val pStarBehind = planetProg.p.u("u_starBehind")
    private val pRingTex = planetProg.p.u("u_ringTex")
    private val pOcc = planetProg.p.u("u_occ")

    private val rToBody = ringProg.p.u("u_toBody")
    private val rSunBody = ringProg.p.u("u_sunBody")
    private val rSunColor = ringProg.p.u("u_sunColor")
    private val rSunAng = ringProg.p.u("u_sunAng")
    private val rRing = ringProg.p.u("u_ring")
    private val rRingTex = ringProg.p.u("u_ringTex")
    private val rRingColor = ringProg.p.u("u_ringColor")

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

    // atmosphere of the current planet (atmoParams)
    private var atmoThick = 0f
    private var betaR = 1f
    private var betaG = 1f
    private var betaB = 1f
    private var atmoK = 0.02f

    // the star as seen from the camera this frame (starView); starDisc = 0 when inside it
    private var starX = 0f
    private var starY = 0f
    private var starZ = -1f
    private var starDisc = 0.0
    private var starGlare = 0.0
    private var starFade = 0.0
    private var starIntensity = 1f
    private var fade = 0f
    private var starDrawn = true

    init {
        val disc = if (star >= 0) bodies[star].colour else 0xFFFFFF
        val corona = if (star >= 0) bodies[star].corona else 0xFFBE6E
        // the data colours are sRGB; the shaders light in linear and gamma-encode in the tonemap
        discR = srgbToLinear((disc shr 16) and 0xFF)
        discG = srgbToLinear((disc shr 8) and 0xFF)
        discB = srgbToLinear(disc and 0xFF)
        coronaR = srgbToLinear((corona shr 16) and 0xFF)
        coronaG = srgbToLinear((corona shr 8) and 0xFF)
        coronaB = srgbToLinear(corona and 0xFF)
        if (star >= 0) {
            refDistance = system.lightReferenceDistance.coerceAtLeast(bodies[star].radius * 2.0)
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
        for (i in 0 until count) {
            val b = bodies[i]
            if (!b.hasRings || b.ringProfile < 0) continue
            ringTex[i] = profileTexture(RingProfiles.values[b.ringProfile])
            ringColor[i * 3] = srgbToLinear((b.ringColour shr 16) and 0xFF)
            ringColor[i * 3 + 1] = srgbToLinear((b.ringColour shr 8) and 0xFF)
            ringColor[i * 3 + 2] = srgbToLinear(b.ringColour and 0xFF)
        }
    }

    /**
     * True if this renderer was built for something [quality] changes: baked texture size or the
     * compiled atmosphere steps / detail octaves (then rebuild the renderer).
     */
    fun needsRebake(quality: Int): Boolean = configKey(quality) != config

    /**
     * How much daylight sky surrounds the camera, from the last [render]: 0 in space or at night,
     * 1 under a full daylight sky (the sky's opacity straight up). Dim background stars / nebula
     * by it; the bodies themselves are already handled.
     */
    fun skyFade(): Float = fade

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
        findOccluders()
        starView()
        fade = skyAlphaAtCamera(exposure)
        // under an opaque sky the planet shader draws the star (and its glare) on its own
        starDrawn = fade < STAR_HIDDEN_FADE

        gl.disable(GL.DEPTH_TEST)
        gl.depthMask(false)
        gl.disable(GL.CULL_FACE)
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
        for (k in 0 until count) {
            val i = order[k]
            if (type[i] != T_STAR) drawPlanet(i, c, time, exposure)
            else if (starDrawn) drawStar(i, c, exposure)
        }
        gl.disable(GL.BLEND)
        gl.depthMask(true)
    }

    // ------------------------------------------------------------------ planets

    private fun drawPlanet(i: Int, c: Camera, time: Float, exposure: Float) {
        val b = bodies[i]
        BodyMath.impostor(relX[i], relY[i], relZ[i], dist[i], b.radius, MIN_PLANET_PX * pxAngle, imp)
        sunFor(i)
        atmoParams(i)
        bodyMatrix(b.rot)
        val thick = atmoThick
        val shell = 1.0 + thick
        val inside = imp.d <= shell
        val angle = if (inside) PI else asin(shell / imp.d) + MARGIN_PX * pxAngle
        val scR = SUN_COLOR_R * sunScale
        val scG = SUN_COLOR_G * sunScale
        val scB = SUN_COLOR_B * sunScale
        val ringed = b.hasRings && b.ringOuter > b.ringInner && ringTex[i] != 0
        val ringIn = (b.ringInner / b.radius).toFloat()
        val ringOut = (b.ringOuter / b.radius).toFloat()

        if (coverage(planetProg, c, angle, inside, exposure)) {
            gl.uniformMatrix4fv(pToBody, toBody, 0)
            // angle wrapped in double; the shader gets its cos/sin
            val spin = BodyMath.spinAngle(b.spin, time)
            gl.uniform2f(pSpin, cos(spin).toFloat(), sin(spin).toFloat())
            gl.uniform3f(pSunDir, sunX, sunY, sunZ)
            gl.uniform3f(pSunBody, sunBody.x, sunBody.y, sunBody.z)
            gl.uniform3f(pSunColor, scR, scG, scB)
            gl.uniform4f(pAtmo, betaR, betaG, betaB, thick)
            gl.uniform2f(pAtmoK, atmoK, 1f)
            gl.uniform4f(pRing, ringIn, ringOut, if (ringed) 1f else 0f, sunAng)
            gl.uniform4f(pSeed, seeds[i * 4], seeds[i * 4 + 1], seeds[i * 4 + 2], seeds[i * 4 + 3])
            gl.uniform1i(pType, type[i])
            gl.activeTexture(GL.TEXTURE0)
            gl.bindTexture(GL.TEXTURE_2D, textures[i])
            gl.uniform1i(pTex, 0)
            gl.uniform1f(pTexScale, (texHeight[i] / PI).toFloat())
            gl.activeTexture(GL.TEXTURE1)
            gl.bindTexture(GL.TEXTURE_2D, ringTex[i]) // only sampled when ringed
            gl.uniform1i(pRingTex, 1)
            gl.uniform4f(pOcc, occ[i * 4], occ[i * 4 + 1], occ[i * 4 + 2], occ[i * 4 + 3])
            gl.activeTexture(GL.TEXTURE0)
            // the star, for the part of it this planet's sky hides
            gl.uniform4f(pSunCam, starX, starY, starZ, starDisc.toFloat())
            gl.uniform4f(pSunGlare, starGlare.toFloat(), starFade.toFloat(), cos(starFade).toFloat(), STAR_INTENSITY)
            gl.uniform3f(pGlareColor, GLOW_R * starIntensity, GLOW_G * starIntensity, GLOW_B * starIntensity)
            gl.uniform1f(pStarBehind, if (inside && !starDrawn) 0f else 1f)
            if (star >= 0) {
                gl.uniform3f(pCoronaColor, coronaR * starIntensity, coronaG * starIntensity, coronaB * starIntensity)
            } else {
                gl.uniform3f(pCoronaColor, 0f, 0f, 0f)
            }
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
                gl.uniform3f(rRingColor, ringColor[i * 3], ringColor[i * 3 + 1], ringColor[i * 3 + 2])
                gl.activeTexture(GL.TEXTURE1)
                gl.bindTexture(GL.TEXTURE_2D, ringTex[i])
                gl.uniform1i(rRingTex, 1)
                gl.activeTexture(GL.TEXTURE0)
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

    /** Scattering colour, shell thickness and ground optical depth of planet [i]. */
    private fun atmoParams(i: Int) {
        val b = bodies[i]
        atmoThick = if (b.atmoThickness > 1e-4f) b.atmoThickness else 0f
        val a = b.atmo
        // scattering colour: the tint squared (normalised) is closer to a λ⁻⁴ law than the tint itself
        val am = max(a[0], max(a[1], max(a[2], 1e-3f)))
        val br = a[0] / am
        val bg = a[1] / am
        val bb = a[2] / am
        betaR = br * br
        betaG = bg * bg
        betaB = bb * bb
        // the air's ground optical depth is data (the limb's height is absolute, DECISIONS 17b)
        atmoK = b.atmoDepth
    }

    /**
     * Opacity of the sky straight above the camera (the planet shader's formula, see
     * [BodyMath.zenithSkyAlpha]) when it is inside a planet's atmosphere, else 0.
     */
    private fun skyAlphaAtCamera(exposure: Float): Float {
        for (i in 0 until count) {
            if (type[i] == T_STAR) continue
            val b = bodies[i]
            atmoParams(i)
            if (atmoThick <= 0f) continue
            val d = dist[i] / b.radius
            if (d >= 1.0 + atmoThick) continue
            sunFor(i)
            // straight up = away from the centre (relX.. point from the camera to the centre)
            val mu = if (dist[i] > 1e-6) -(relX[i] * sunX + relY[i] * sunY + relZ[i] * sunZ) / dist[i] else 1.0
            return BodyMath.zenithSkyAlpha(
                max(d, 1.0), atmoThick.toDouble(), betaR.toDouble(), betaG.toDouble(), betaB.toDouble(), atmoK.toDouble(), mu,
                (SUN_COLOR_R * sunScale).toDouble(), (SUN_COLOR_G * sunScale).toDouble(), (SUN_COLOR_B * sunScale).toDouble(),
                exposure.toDouble(), atmoSteps,
            ).toFloat()
        }
        return 0f
    }

    /** Direction, drawn size, glare width, fade-out angle and intensity of the star seen from the camera. */
    private fun starView() {
        starDisc = 0.0
        if (star < 0) return
        val r = bodies[star].radius
        val d = dist[star]
        if (d <= r * (1.0 + 1e-6)) return
        starX = (relX[star] / d).toFloat()
        starY = (relY[star] / d).toFloat()
        starZ = (relZ[star] / d).toFloat()
        val trueAngle = asin(r / d)
        starDisc = max(trueAngle, MIN_STAR_PX * pxAngle)
        starGlare = max(trueAngle, GLARE_MIN_PX * pxAngle)
        starFade = glowFadeOut(starDisc, starGlare)
        starIntensity = sqrt(trueAngle / refStarAngle).toFloat().coerceIn(0.6f, 1.4f)
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
        val fadeOut = glowFadeOut(disc, glare)
        val intensity = sqrt(imp.trueAngle / refStarAngle).toFloat().coerceIn(0.6f, 1.4f)
        if (coverage(glowProg, c, fadeOut, inside, exposure)) {
            gl.uniform4f(gGlow, disc.toFloat(), glare.toFloat(), intensity, fadeOut.toFloat())
            gl.uniform3f(gColor, GLOW_R, GLOW_G, GLOW_B)
            // the data corona colour (Sol 255,190,110), Roblox's ForceField corona sphere
            gl.uniform3f(gCorona, coronaR, coronaG, coronaB)
            drawCoverage()
        }
        val angle = if (inside) PI else disc + MARGIN_PX * pxAngle
        if (coverage(starProg, c, angle, inside, exposure)) {
            // the data disc colour (Sol 255,246,224), Roblox's Neon star
            gl.uniform3f(sColor, STAR_INTENSITY * discR, STAR_INTENSITY * discG, STAR_INTENSITY * discB)
            drawCoverage()
        }
    }

    // ------------------------------------------------------------------ coverage geometry

    /**
     * Binds [p] and its shared uniforms for the current body ([imp]) seen under [angle] radians
     * (silhouette + margins). Picks the fullscreen triangle when [inside], when the angle is too
     * large for a quad or when the body's cone contains the whole view anyway; otherwise a quad
     * around the body's direction, which never shades more pixels than the fullscreen triangle
     * (fill rate: the star glow is wide, but rarely covers the screen). Returns false when it is
     * off-screen, whichever geometry it would have used.
     */
    private fun coverage(p: BodyProgram, c: Camera, angle: Double, inside: Boolean, exposure: Float): Boolean {
        val dx = imp.dirX
        val dy = imp.dirY
        val dz = imp.dirZ
        val cosFwd = (dx * c.forward.x + dy * c.forward.y + dz * c.forward.z).toDouble()
        // rejected before the full-screen choice: a wide cone (the star glow near the star, a
        // planet from low orbit) behind the camera would otherwise shade every pixel for nothing
        if (!inside && BodyMath.offScreen(cosFwd, angle, halfDiag)) return false
        val full = inside || angle > MAX_QUAD_ANGLE || acos(cosFwd.coerceIn(-1.0, 1.0)) + halfDiag <= angle
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

    /**
     * Bakes every body's equirect surface texture with its look ([BodyLooksShader]): the recipe,
     * the body's data colour and its class palette (`ShipPalettes.colours`), linear.
     */
    private fun bake() {
        val prog = ShaderProgram(gl, "bodyBake", BodyShaders.bakeVs, BodyShaders.bakeFs)
        val uLook = prog.u("u_look")
        val uSeed = prog.u("u_seed")
        val uPal = prog.u("u_pal")
        val uAir = prog.u("u_air")
        val uRect = prog.u("u_rect")
        val pal = FloatArray(32)
        val fbo = gl.genFramebuffer()
        gl.bindFramebuffer(GL.FRAMEBUFFER, fbo)
        gl.disable(GL.DEPTH_TEST)
        gl.disable(GL.BLEND)
        gl.disable(GL.CULL_FACE)
        prog.use()
        for (i in 0 until count) {
            if (type[i] == T_STAR) continue
            val b = bodies[i]
            val th = texHeight[i]
            val tw = th * 2
            val tex = gl.genTexture()
            gl.bindTexture(GL.TEXTURE_2D, tex)
            gl.texImage2D(GL.TEXTURE_2D, 0, GL.RGBA8, tw, th, GL.RGBA, GL.UNSIGNED_BYTE, null)
            // mipmapped, but the shader picks the LOD itself (textureLod): derivative-based LOD would
            // jump to the smallest mip along the atan() seam
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.REPEAT)
            gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
            gl.framebufferTexture2D(GL.FRAMEBUFFER, GL.COLOR_ATTACHMENT0, GL.TEXTURE_2D, tex, 0)
            val status = gl.checkFramebufferStatus(GL.FRAMEBUFFER)
            check(status == GL.FRAMEBUFFER_COMPLETE) { "body bake framebuffer incomplete: 0x${status.toString(16)}" }
            gl.uniform1i(uLook, b.look)
            gl.uniform4f(uSeed, seeds[i * 4], seeds[i * 4 + 1], seeds[i * 4 + 2], seeds[i * 4 + 3])
            palette(b, pal)
            gl.uniform4fv(uPal, 8, pal, 0)
            val air = if (b.atmosphereColour != 0) b.atmosphereColour else b.colour
            gl.uniform4f(uAir, srgbToLinear((air shr 16) and 0xFF), srgbToLinear((air shr 8) and 0xFF), srgbToLinear(air and 0xFF), 1f)
            for (s in 0 until BAKE_STRIPS) {
                val y0 = s * th / BAKE_STRIPS
                val y1 = (s + 1) * th / BAKE_STRIPS
                gl.viewport(0, y0, tw, y1 - y0)
                gl.uniform2f(uRect, y0.toFloat() / th, (y1 - y0).toFloat() / th)
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

    /** One ring's opacity profile as a 128 x 1 R8 texture with its mip chain. */
    private fun profileTexture(values: FloatArray): Int {
        val n = values.size
        val buf = java.nio.ByteBuffer.allocateDirect(n).order(java.nio.ByteOrder.nativeOrder())
        for (v in values) buf.put((v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
        buf.flip()
        val tex = gl.genTexture()
        gl.bindTexture(GL.TEXTURE_2D, tex)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 1)
        gl.texImage2D(GL.TEXTURE_2D, 0, GL.R8, n, 1, GL.RED, GL.UNSIGNED_BYTE, buf)
        gl.pixelStorei(GL.UNPACK_ALIGNMENT, 4)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MIN_FILTER, GL.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_MAG_FILTER, GL.LINEAR)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_S, GL.CLAMP_TO_EDGE)
        gl.texParameteri(GL.TEXTURE_2D, GL.TEXTURE_WRAP_T, GL.CLAMP_TO_EDGE)
        gl.generateMipmap(GL.TEXTURE_2D)
        return tex
    }

    /**
     * The occluder of every body this frame (03-world.md 2.10: centre-distance sorting alone lets a
     * small body behind a big body's limb show through it): the biggest body drawn before it (its
     * centre is farther) whose disc overlaps its disc and whose near surface is nearer than its
     * centre. Written in the body's own radii, camera-relative, into [occ]. O(n²) over ~18 bodies.
     */
    private fun findOccluders() {
        for (k in 0 until count) {
            val i = order[k]
            val o = i * 4
            occ[o + 3] = 0f
            if (type[i] == T_STAR) continue
            val ri = bodies[i].radius
            val di = dist[i]
            if (di <= ri) continue
            val sinI = ri / di
            val cosI = sqrt(1.0 - sinI * sinI)
            var best = -1
            var bestSin = 0.0
            for (m in 0 until k) {
                val j = order[m]
                if (type[j] == T_STAR) continue
                val rj = bodies[j].radius
                val dj = dist[j]
                if (dj <= rj || dj - rj >= di) continue
                val sinJ = rj / dj
                if (sinJ <= sinI || sinJ <= bestSin) continue
                // overlap: the angle between the centres is below the sum of the angular radii
                val cosSep = (relX[i] * relX[j] + relY[i] * relY[j] + relZ[i] * relZ[j]) / (di * dj)
                val cosJ = sqrt(1.0 - sinJ * sinJ)
                if (cosSep <= cosI * cosJ - sinI * sinJ) continue
                best = j
                bestSin = sinJ
            }
            if (best < 0) continue
            val inv = 1.0 / ri
            occ[o] = (relX[best] * inv).toFloat()
            occ[o + 1] = (relY[best] * inv).toFloat()
            occ[o + 2] = (relZ[best] * inv).toFloat()
            occ[o + 3] = (bodies[best].radius * inv).toFloat()
        }
    }

    fun dispose() {
        planetProg.p.dispose()
        ringProg.p.dispose()
        starProg.p.dispose()
        glowProg.p.dispose()
        quad.dispose()
        for (t in textures) if (t != 0) gl.deleteTexture(t)
        for (t in ringTex) if (t != 0) gl.deleteTexture(t)
    }

    companion object {
        const val T_STAR = 0

        /** Planets never shrink below this apparent radius (px), so they stay visible as dots. */
        const val MIN_PLANET_PX = 1.5
        const val MIN_STAR_PX = 2.0
        private const val GLARE_MIN_PX = 7.0
        private const val MARGIN_PX = 3.0
        private const val SCALED_DISTANCE = 1000.0

        /** Largest cone (half angle, rad) drawn with a quad; tan() makes wider quads impractical. */
        private const val MAX_QUAD_ANGLE = 1.3
        private const val BAKE_STRIPS = 8
        private const val SUN_COLOR_R = 2.6f
        private const val SUN_COLOR_G = 2.45f
        private const val SUN_COLOR_B = 2.25f
        private const val STAR_INTENSITY = 4f
        private const val GLOW_R = 1.0f
        private const val GLOW_G = 0.92f
        private const val GLOW_B = 0.78f

        /**
         * From this [skyFade] on the star (glow: often most of the screen) is not drawn behind the
         * sky: the sky around it is nearly opaque, and the planet shader draws it in full instead.
         */
        private const val STAR_HIDDEN_FADE = 0.9f

        fun bakeWidth(quality: Int) = if (quality == 0) 512 else 1024

        /**
         * Height of a body's equirect bake (width = 2 × height) for a full width [texW]: planets of
         * 3,000 km and more get the full size, the other moons and Mercury half, Phobos and Deimos a
         * quarter (17 Sol textures: ~26 MB at quality 1-2 instead of 48).
         */
        fun bakeHeight(texW: Int, radius: Double): Int = when {
            radius >= 3.0e6 -> texW / 2
            radius >= 1.0e6 -> texW / 4
            else -> texW / 8
        }

        /** An sRGB channel (0..255) as a linear value (gamma 2.2). */
        fun srgbToLinear(c: Int): Float = (c / 255.0).pow(2.2).toFloat()

        /**
         * The bake palette of [b] into [out] (8 × vec4, linear): 0 its data colour, 1..5 its class
         * ramp (shore, low, high, peak, ice with the ice latitude in w), 6..7 deep and shallow water.
         */
        fun palette(b: StarSystem.Body, out: FloatArray) {
            val r = Palettes.colours(b.cls)
            val cols = intArrayOf(b.colour, r.shore, r.low, r.high, r.peak, r.ice, r.deep, r.shallow)
            for (k in 0 until 8) {
                val c = cols[k]
                out[k * 4] = srgbToLinear((c shr 16) and 0xFF)
                out[k * 4 + 1] = srgbToLinear((c shr 8) and 0xFF)
                out[k * 4 + 2] = srgbToLinear(c and 0xFF)
                out[k * 4 + 3] = 0f
            }
            out[5 * 4 + 3] = r.iceLatitude
        }
        fun atmoSteps(quality: Int) = if (quality == 0) 5 else 8
        fun detailOctaves(quality: Int) = when (quality) {
            0 -> 0
            1 -> 3
            else -> 4
        }

        /** Packs everything [quality] decides at construction; equal keys share programs and bakes. */
        internal fun configKey(quality: Int): Int = (bakeWidth(quality) * 100 + atmoSteps(quality)) * 100 + detailOctaves(quality)

        /** Angle from the star's centre where its glow has faded out (rad). */
        private fun glowFadeOut(disc: Double, glare: Double) = min(disc * 2.0 + glare * 10.0, 1.45)

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
     * True when a cone of half angle [angle] around a direction at cos [cosFwd] from the view axis
     * cannot reach any pixel of a view whose corners are [halfDiag] radians off-axis: every pixel
     * direction is within halfDiag of the axis, so it is more than [angle] from the cone's axis
     * once the two are more than angle + halfDiag apart.
     */
    fun offScreen(cosFwd: Double, angle: Double, halfDiag: Double): Boolean {
        val lim = angle + halfDiag
        return lim < PI && cosFwd < cos(lim)
    }

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

    private const val TAU = 2.0 * PI

    /**
     * Spin angle [spin] (rad/s) * [time] (s) wrapped to [0, 2π) in double; 0 when the product is
     * not finite (e.g. a zero spin at an infinite time).
     */
    fun spinAngle(spin: Float, time: Float): Double {
        val a = spin.toDouble() * time.toDouble()
        if (!a.isFinite()) return 0.0
        val w = a % TAU
        val p = if (w < 0.0) w + TAU else w
        return if (p >= TAU) 0.0 else p
    }

    /**
     * Opacity of the sky straight up from [d] planet radii (CPU copy of the planet shader:
     * atmosphere() along the zenith ray with [steps] samples, without the sun's halo, then
     * 1 - T + 2.5 * luminance after the tonemap): shell [thick] radii, scattering colour [br],
     * [bg], [bb], ground optical depth [k0], sun elevation sine [mu], sun colour [sr], [sg], [sb].
     * 0 above the shell.
     */
    fun zenithSkyAlpha(
        d: Double, thick: Double, br: Double, bg: Double, bb: Double, k0: Double, mu: Double,
        sr: Double, sg: Double, sb: Double, exposure: Double, steps: Int,
    ): Double {
        val top = 1.0 + thick
        if (thick <= 0.0 || d >= top) return 0.0
        val h = thick * 0.18
        val k = k0 / h
        val dx = (top - d) / steps
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var tau = 0.0
        for (i in 0 until steps) {
            // along the zenith ray the closest-approach point is the centre: r = x, mu constant
            val r = d + (i + 0.5) * dx
            val rho = exp(min((1.0 - r) / h, 0.0))
            val dt = rho * dx * k
            val muH = -sqrt(max(1.0 - 1.0 / (r * r), 0.0))
            val vis = smoothstep(muH - 0.02, muH + 0.05, mu)
            val am = min(1.0 / (max(mu - muH, 0.0) + 0.035), 40.0)
            val o = k0 * rho * am + tau + 0.5 * dt
            val w = vis * dt
            sumR += exp(-br * o) * w
            sumG += exp(-bg * o) * w
            sumB += exp(-bb * o) * w
            tau += dt
        }
        // Rayleigh part only: the forward-scattering halo is the sun's, not the sky's
        val phase = 0.75 * (1.0 + mu * mu)
        val lum = 0.3 * tonemap(sumR * br * phase * sr, exposure) +
            0.59 * tonemap(sumG * bg * phase * sg, exposure) +
            0.11 * tonemap(sumB * bb * phase * sb, exposure)
        val t = (exp(-br * tau) + exp(-bg * tau) + exp(-bb * tau)) * 0.3333
        return (1.0 - t + 2.5 * lum).coerceIn(0.0, 1.0)
    }

    /** One channel of the shaders' tonemap (ACES fit + gamma). */
    fun tonemap(c: Double, exposure: Double): Double {
        val x = c * exposure
        val t = ((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)).coerceIn(0.0, 1.0)
        return t.pow(0.4545)
    }

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
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
