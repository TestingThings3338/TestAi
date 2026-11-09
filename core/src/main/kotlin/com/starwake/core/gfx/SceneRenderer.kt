package com.starwake.core.gfx

import com.starwake.core.content.AsteroidMesh
import com.starwake.core.content.Content
import com.starwake.core.content.DustField
import com.starwake.core.content.Primitives
import com.starwake.core.content.ShipModel
import com.starwake.core.content.StarField
import com.starwake.core.content.StationMesh
import com.starwake.core.game.GearRig
import com.starwake.core.game.ShipState
import com.starwake.core.math.Mat4
import com.starwake.core.math.Vec3
import com.starwake.core.math.TAU_F
import com.starwake.core.terrain.TerrainPlanet
import com.starwake.core.world.RingDebris
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Fixed lighting setup of the starting system. */
object Environment {
    val sunDir: Vec3 = Vec3(-0.846f, 0.451f, 0.282f).normalize()
    val sunColor = floatArrayOf(2.6f, 2.45f, 2.25f)
    val planetDir: Vec3 = Vec3(0.24f, 0.13f, -1f).normalize()
    val fillColor = floatArrayOf(0.10f, 0.075f, 0.055f)
    val ambient = floatArrayOf(0.035f, 0.042f, 0.06f)
}

/** Per-frame scene parameters supplied by the game. */
class SceneFrame {
    val camera = Camera()
    var time = 0f
    var ship: ShipState? = null

    /** How the ship is seen: ShipModel.VIEW_EXTERIOR (chase, menu) or VIEW_COCKPIT (from the seat). */
    var shipView = ShipModel.VIEW_EXTERIOR

    /** Landing-gear pose of the ship's model (bones). */
    var gear: GearRig? = null

    /** How far (m) from the camera the ship and its plumes reach: the near depth slice covers at least that. */
    var shipReach = 0f
    var quality = 2
    var pxScale = 1f
    val dustVelocity = Vec3()
    var dustIntensity = 0.6f

    /** Speed-line colour: white in open space, blue in a gravity well, red for danger/heat. */
    val dustColor = Vec3(0.66f, 0.72f, 0.80f)

    /** Dust is laid out around this point (moves at a capped "visual" speed, see Game). */
    val dustOrigin = com.starwake.core.math.DVec3()

    /** Direction to the star from the viewer, and how much sunlight gets through (eclipses). */
    val sunDir = Vec3().set(Environment.sunDir)
    var sunVisibility = 1f

    /** Light reflected from the nearest planet. */
    val fillDir = Vec3().set(Environment.planetDir)
    val fillColor = floatArrayOf(Environment.fillColor[0], Environment.fillColor[1], Environment.fillColor[2])

    var exposure = 1f

    /**
     * The seamless terrain of the terrain body (from [SceneRenderer.attachTerrain]), or null. The
     * game updates the planet before rendering; the scene draws it in depth slices around its own
     * and hands the body's surface over from the impostor (see SceneRenderer.handOver). Set it to
     * null when the body is released: the impostor gets its surface back.
     */
    var terrain: TerrainGpu? = null

    /** Index of the terrain body in the system's bodies: its impostor stops shading the surface once the terrain covers it. */
    var terrainBody = -1

    /**
     * Light and air of the terrain: the game sets the profile's ambient and haze once
     * ([TerrainLight.setAir]); the scene fills the sun, the far look and the exposure from the
     * body's impostor every frame.
     */
    val terrainLight = TerrainLight()

    /** The ring debris around the ship (03-world.md 2.12), or null: drawn when it has visible rocks. */
    var debris: RingDebris? = null
}

/**
 * Renders the 3D scene. Everything is drawn camera-relative. Typical frame cost: ~15 draw calls,
 * one texture lookup for the background, no post-processing passes.
 *
 * Depth: the near scene shares one 24-bit depth buffer (near plane 0.5 m) only while everything in
 * it is close. Stations that are far from the camera, and from whatever is
 * close to it, go into a far slice with a projection fitted to them, drawn first into the back
 * half of the depth range (see planSlices); the near slice then uses the front half.
 */
class SceneRenderer(private val gl: Gl, private val content: Content, quality: Int) {
    private val sky = ShaderProgram(gl, "sky", Shaders.skyVs, Shaders.skyFs)
    private val star = ShaderProgram(gl, "stars", Shaders.starVs, Shaders.starFs)
    private val dust = ShaderProgram(gl, "dust", Shaders.dustVs, Shaders.dustFs)
    private val glow = ShaderProgram(gl, "glow", Shaders.glowVs, Shaders.glowFs)

    private val fullscreen = GpuMesh(gl, Primitives.fullscreenTriangle, intArrayOf(2))
    private val ships = ShipRenderer(gl)
    // full buffers are uploaded once; quality only changes how much of them is drawn
    private val starMesh = GpuMesh(gl, content.stars, intArrayOf(3, 4))
    private val dustMesh = GpuMesh(gl, content.dust.first, intArrayOf(3, 2), content.dust.second)
    private var starCount = 0
    private var dustCount = 0
    private val stationMeshes = Array(StationMesh.MODELS) { GpuMesh(gl, content.stationMeshes[it], intArrayOf(3, 3, 2)) }
    private val stationLit = LitProgram(gl, false)

    /** Ring debris: one rock mesh drawn instanced, its instances rewritten each frame. */
    private val debrisMesh = GpuMesh(gl, content.debrisRock, intArrayOf(3, 3, 2)).also {
        it.setDynamicInstances(RingDebris.COUNT, intArrayOf(4, 4, 4), 4)
    }
    private val debrisLit = LitProgram(gl, true)
    private val debrisPalette = FloatArray(40)
    private val debrisParams = FloatArray(40)
    private val glowStream = QuadStream(gl, 64, intArrayOf(3, GL.FLOAT, 3, GL.FLOAT, 4, GL.FLOAT))

    private val envSize = if (quality == 0) 256 else 512
    private val envTex = bakeEnvironment(envSize)
    private val bodies = BodyRenderer(gl, content.system, quality, fullscreen)
    private val envLod = ln(envSize.toFloat()) / ln(2f) - 1.5f
    var quality = quality
        private set

    // scratch (no per-frame allocations)
    private val model = Mat4()
    private val rel = Vec3()
    private val tmp = Vec3()
    private val tmp2 = Vec3()
    private val side = Vec3()
    private val axis = Vec3()

    // depth slices, planned once per frame (planSlices)
    private val slices = DepthSlices(maxOf(1, content.system.stations.size))
    private val stationSlot = IntArray(content.system.stations.size)
    private val farProj = Mat4()
    private val farViewProj = Mat4()

    /** Uniform scale about the eye of everything in the far slice (1 when it starts within [FAR_SCALE_FROM]). */
    private var farScale = 1f

    /** Bounding radius per station model: the mesh and its light sprites' centres. */
    private val stationBound = FloatArray(StationMesh.MODELS) { m ->
        val l = content.stationLights[m]
        var r = StationMesh.radius(m)
        var o = 0
        while (o < l.size) {
            r = maxOf(r, sqrt(l[o] * l[o] + l[o + 1] * l[o + 1] + l[o + 2] * l[o + 2]))
            o += 6
        }
        r * 1.01f
    }

    /** Depth slices the last frame used: 1, or 2 when far objects got their own (diagnostics). */
    var depthSlices = 1
        private set

    // the seamless terrain (created on first use)
    private var terrainRenderer: TerrainRenderer? = null

    /** The projection of the near pass: the camera's, or the terrain's nearest slice when a terrain is drawn. */
    private var nearVP: Mat4 = Mat4()
    private val terrainNearProj = Mat4()
    private val terrainNearVP = Mat4()

    // the pass the stations and station lights are drawn in (setPass): the camera's cluster
    // or the far objects (only those reaching into the view depths [passZNear, passZFar]; light
    // sprites only in [passOwnZNear, passOwnZFar)), with which projection and scale
    private var passFarObjects = false
    private var passVP: Mat4 = nearVP
    private var passScale = 1f
    private var passZNear = -Float.MAX_VALUE
    private var passZFar = Float.MAX_VALUE
    private var passOwnZNear = -Float.MAX_VALUE
    private var passOwnZFar = Float.MAX_VALUE

    /** The frame whose far objects the terrain's far slices draw (set during [TerrainRenderer.drawFar]). */
    private var sliceFrame: SceneFrame? = null

    /** Far stations inside the terrain's far slices: the terrain occludes them there. */
    private val sliceObjects = object : TerrainSliceObjects {
        override fun drawSlice(near: Float, far: Float, ownNear: Float, ownFar: Float, viewProj: Mat4) {
            val f = sliceFrame ?: return
            setPass(true, viewProj, 1f, near, far, ownNear, ownFar)
            drawStations(f)
            drawStationLights(f)
        }
    }

    private fun setPass(
        far: Boolean, vp: Mat4, scale: Float,
        zNear: Float = -Float.MAX_VALUE, zFar: Float = Float.MAX_VALUE,
        ownNear: Float = -Float.MAX_VALUE, ownFar: Float = Float.MAX_VALUE,
    ) {
        passFarObjects = far
        passVP = vp
        passScale = scale
        passZNear = zNear
        passZFar = zFar
        passOwnZNear = ownNear
        passOwnZFar = ownFar
    }

    /** True when the object in [slot] (from [planSlices]) is drawn in the current pass. */
    private fun inPass(slot: Int): Boolean =
        slot >= 0 && slices.isFar(slot) == passFarObjects && slices.zMaxOf(slot) >= passZNear && slices.zMinOf(slot) <= passZFar

    /** The terrain renderer (null until a terrain was attached). */
    val terrain: TerrainRenderer? get() = terrainRenderer

    // the impostor-to-terrain hand-over (handOver)
    private var hiddenBody = -1
    private var fadePlanet: TerrainPlanet? = null
    private var fadeStart = -1f

    /** Opacity of the terrain in the last frame: 0 while the impostor still shades the surface, 1 once handed over. */
    var terrainFade = 0f
        private set

    /**
     * The hand-over of the terrain body's surface from its impostor to the terrain, once per
     * frame: the terrain's sun, far look and exposure come from the impostor
     * ([BodyRenderer.fillTerrainLight]); the impostor shades the surface until the terrain covers
     * the sphere ([TerrainPlanet.surfaceReady]), then the terrain takes over and the impostor draws
     * only the black core at the terrain's lowest radius under it. When all the terrain is beyond
     * its far look's end ([TerrainRenderer.farLookEnd]: the usual case, the planet is created
     * ~20,000 km out) the terrain is the impostor and takes over at once; nearer (after a GL
     * context loss, a quality change, a late creation) it fades in over [HANDOVER_SECONDS]. A body
     * that stops being the terrain body gets its surface back. Returns the terrain's opacity (0:
     * do not draw it).
     */
    private fun handOver(f: SceneFrame): Float {
        val tg = f.terrain
        val body = if (tg != null && terrainRenderer != null) f.terrainBody else -1
        if (hiddenBody >= 0 && hiddenBody != body) bodies.setSurfaceHidden(hiddenBody, false)
        hiddenBody = body
        if (tg == null || body < 0) {
            fadePlanet = null
            fadeStart = -1f
            terrainFade = 0f
            return 0f
        }
        val p = tg.planet
        if (p !== fadePlanet) {
            fadePlanet = p
            fadeStart = -1f
        }
        bodies.fillTerrainLight(body, f.terrainLight)
        f.terrainLight.exposure = f.exposure
        if (!p.surfaceReady()) {
            fadeStart = -1f
        } else if (fadeStart < 0f || f.time < fadeStart) {
            // all the terrain beyond its far look's end: it is the impostor, hand over at once
            val farEnd = TerrainRenderer.farLookEnd(p, f.terrainLight)
            val clear = p.cameraAltitude - (p.field.maxRadius - p.radius)
            fadeStart = if (farEnd > 0f && clear > farEnd) f.time - HANDOVER_SECONDS else f.time
        }
        val x = if (fadeStart < 0f) 0f else ((f.time - fadeStart) / HANDOVER_SECONDS).coerceIn(0f, 1f)
        val fade = if (fadeStart < 0f) 0f else maxOf(x * x * (3f - 2f * x), MIN_FADE)
        bodies.setSurfaceHidden(body, fade >= 1f, p.field.minRadius)
        terrainFade = fade
        return fade
    }

    /** GPU side of a terrain planet; set the result as [SceneFrame.terrain]. Dispose it when the body changes. */
    fun attachTerrain(planet: TerrainPlanet): TerrainGpu {
        val tr = terrainRenderer ?: TerrainRenderer(gl, quality).also { terrainRenderer = it }
        return tr.attach(planet)
    }

    /**
     * Gives body [i]'s impostor its surface image ([BodyRenderer.setSurfaceTexture]): for a terrain
     * body the terrain's own far colours (`TerrainPlanet.bakeEquirect`), which the terrain's far
     * look shows too. Upload on the GL thread; again after the renderer is rebuilt.
     */
    fun setBodySurfaceTexture(i: Int, rgba: java.nio.ByteBuffer, w: Int, h: Int) = bodies.setSurfaceTexture(i, rgba, w, h)

    init {
        setQuality(quality)
    }

    /** True when [q] needs differently sized baked textures (then rebuild the renderer). */
    fun needsRebake(q: Int): Boolean = (q == 0) != (envSize == 256) || bodies.needsRebake(q)

    /** Cheap quality switch: star and dust counts. */
    fun setQuality(q: Int) {
        quality = q
        starCount = when (q) {
            0 -> 3000
            1 -> 4500
            else -> Content.MAX_STARS
        }
        dustCount = when (q) {
            0 -> 300
            1 -> 500
            else -> Content.MAX_DUST
        }
    }

    /** The ship model drawn for [SceneFrame.ship] (uploads it; not per frame). */
    fun setShipModel(m: ShipModel?) = ships.setModel(m)

    /** Ship draw calls and triangles of the last frame (diagnostics). */
    val shipDrawCalls get() = ships.drawCalls
    val shipTriangles get() = ships.triangles

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
        ships.resetStats()

        val tg = f.terrain
        val tr = terrainRenderer
        // the hand-over: the impostor shades the surface until the terrain covers the sphere, then
        // the terrain fades in over it (its far look is the impostor's, so normally nothing changes)
        val fade = handOver(f)
        val withTerrain = tg != null && tr != null && fade > 0f
        planSlices(f, withTerrain)
        drawBackground(f)
        drawStars(f)
        // star and planets: ray-cast impostors at true scale, drawn back to front without depth
        bodies.render(cam, f.time, f.exposure)
        gl.viewport(0, 0, w, h)
        nearVP = cam.viewProj
        var terrainFirstFar = 0f
        if (tg != null && tr != null && !withTerrain) {
            // not drawn yet: the finished tiles still go to the GPU
            tr.prepare(tg, cam)
        } else if (tg != null && tr != null) {
            // the near pass ends where the camera's cluster ends (at least TERRAIN_NEAR m: nothing
            // nearer is a far object); farther terrain goes into slices of its own, drawn now, with
            // the far stations inside them (the terrain occludes them)
            val split = slices.split
            terrainFirstFar = maxOf(TERRAIN_NEAR, slices.nearEnd * 1.05f)
            // a far object never starts inside the near slice: the near terrain, drawn after the
            // far slices, ends in front of every far object
            if (split) terrainFirstFar = minOf(terrainFirstFar, maxOf(slices.nearEnd, slices.farNear))
            terrainFirstFar = terrainFirstFar.coerceAtMost(cam.far)
            tr.fade = fade
            tr.prepare(tg, cam, cam.near, terrainFirstFar, if (split) slices.farNear else 0f, if (split) slices.farFar else 0f)
            sliceFrame = f
            tr.drawFar(tg, cam, f.terrainLight, f.time, if (split) sliceObjects else null)
            sliceFrame = null
            terrainNearProj.perspective(cam.fovY, w.toFloat() / h.coerceAtLeast(1), cam.near, terrainFirstFar, cam.shiftX, cam.shiftY)
            terrainNearVP.mul(terrainNearProj, cam.view)
            nearVP = terrainNearVP
        }

        // the near scene draws over the bodies (planets use no depth), so a single clear suffices
        gl.depthMask(true)
        gl.enable(GL.DEPTH_TEST)
        gl.depthFunc(GL.LEQUAL)
        gl.enable(GL.CULL_FACE)
        gl.cullFace(GL.BACK)
        if (slices.split && !withTerrain) {
            // far slice first, behind everything: nothing in it overlaps the near slice in depth
            gl.depthRange(0.5f, 1f)
            setPass(true, farViewProj, farScale)
            drawStations(f)
            drawStationLights(f)
            gl.depthRange(0f, 0.5f)
        } else {
            gl.depthRange(0f, 1f)
        }
        if (tg != null && tr != null && terrainFirstFar > 0f) {
            tr.drawNear(tg, cam, f.terrainLight, f.time, nearVP)
            gl.enable(GL.CULL_FACE)
            gl.cullFace(GL.BACK)
        }
        setPass(false, nearVP, 1f)
        drawStations(f)
        drawDebris(f)
        val ship = f.ship
        if (ship != null) drawShip(f, ship, ShipModel.PASS_OPAQUE)
        drawDust(f)
        // canopy glass after everything opaque, blended, without depth writes
        if (ship != null) drawShip(f, ship, ShipModel.PASS_GLASS)
        drawStationLights(f)
        if (ship != null) drawShipGlows(f, ship)
        gl.depthMask(true)
        gl.depthRange(0f, 1f)
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
        // under a daylight sky the nebula fades out (skyFade is from the previous frame's bodies pass)
        gl.uniform1f(sky.u("u_exposure"), f.exposure * (1f - bodies.skyFade()))
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
        gl.uniform1f(star.u("u_bright"), 1f - bodies.skyFade())
        starMesh.drawRange(GL.POINTS, starCount)
        gl.disable(GL.BLEND)
    }

    // ------------------------------------------------------------------ depth slices

    /**
     * Decides, once per frame, which slice each station is drawn in, and culls the
     * ones whose bounding sphere is outside the view. The camera's own cluster is everything within
     * [NEAR_REACH] (ship, dust) plus whatever overlaps it in depth, transitively; it is drawn with
     * the camera's projection, so spawn and docking render exactly as with a single slice. Anything
     * beyond that cluster goes into the far slice, whose projection is fitted to it: at 30 km a
     * depth step is millimetres instead of ~20 m with the 0.5 m near plane.
     *
     * Every object lies wholly in one slice and the slices do not overlap in depth, so nothing is
     * drawn twice and the order between slices is always right.
     *
     * [withTerrain]: the near pass shares its depth slice with the terrain out to at least
     * [TERRAIN_NEAR], so the cluster starts from there (nothing nearer may be a far object: the
     * near terrain would be drawn over it); the far objects are drawn inside the terrain's far
     * slices instead of a slice of their own.
     */
    private fun planSlices(f: SceneFrame, withTerrain: Boolean) {
        val c = f.camera
        val s = slices
        s.begin()
        val tx = c.tanHalfX * (1f + abs(c.shiftX))
        val ty = c.tanHalfY * (1f + abs(c.shiftY))
        val stations = content.system.stations
        for (i in stations.indices) {
            val st = stations[i]
            st.pos.relativeTo(c.pos, rel)
            stationSlot[i] = if (rel.length() > STATION_DRAW_RANGE || occludedByBody(st.pos, c.pos)) {
                -1
            } else {
                s.add(rel.dot(c.right), rel.dot(c.up), rel.dot(c.forward), stationBound[st.model], tx, ty)
            }
        }
        val reach = maxOf(NEAR_REACH, if (f.ship != null) f.shipReach else 0f)
        s.plan(if (withTerrain) maxOf(reach, TERRAIN_NEAR) else reach)
        depthSlices = if (s.split) 2 else 1
        if (!s.split || withTerrain) return
        // one scale for the whole slice keeps the depth order inside it exact;
        // scaling about the eye changes neither the picture nor the precision, it only keeps the
        // coordinates of a station 3000 km away in the range the rest of the scene uses
        farScale = if (s.farNear > FAR_SCALE_FROM) FAR_SCALE_FROM / s.farNear else 1f
        val n = s.farNear * farScale * (1f - FAR_MARGIN)
        val fa = s.farFar * farScale * (1f + FAR_MARGIN)
        farProj.perspective(c.fovY, c.width.toFloat() / c.height.coerceAtLeast(1), n, fa, c.shiftX, c.shiftY)
        farViewProj.mul(farProj, c.view)
    }

    // ------------------------------------------------------------------ near scene

    /**
     * One pass of the ship model (ShipModel.PASS_*): opaque with the rest of the scene, glass after
     * it with premultiplied alpha blending (ForceField adds its glow at alpha 0) and no depth writes.
     * The engine glow discs follow the throttle like ShipHull.updatePlumes (half transparent at
     * idle, opaque at full, 1.35x brighter on boost).
     */
    private fun drawShip(f: SceneFrame, ship: ShipState, pass: Int) {
        val rig = f.gear ?: return
        if (ships.model == null) return
        val c = f.camera
        ship.pos.relativeTo(c.pos, rel)
        model.setTRS(rel.x, rel.y, rel.z, ship.rot, 1f)
        val glow = ship.engineOutput.coerceIn(0f, 1f)
        val engine = (0.5f + 0.5f * glow) * (if (ship.boosting) 1.35f else 1f) * ENGINE_DISC_GLOW
        ships.begin(nearVP, f, envTex, envLod, engine)
        if (pass == ShipModel.PASS_GLASS) {
            gl.enable(GL.BLEND)
            gl.blendFunc(GL.ONE, GL.ONE_MINUS_SRC_ALPHA)
            gl.depthMask(false)
            ships.draw(pass, f.shipView, model, rig)
            gl.depthMask(true)
            gl.disable(GL.BLEND)
        } else {
            ships.draw(pass, f.shipView, model, rig)
        }
    }

    private fun drawDust(f: SceneFrame) {
        val c = f.camera
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        dust.use()
        gl.uniformMatrix4fv(dust.u("u_viewProj"), nearVP.m, 0)
        val b = DustField.BOX.toDouble()
        val o = f.dustOrigin
        gl.uniform3f(
            dust.u("u_camMod"),
            (((o.x % b) + b) % b).toFloat(),
            (((o.y % b) + b) % b).toFloat(),
            (((o.z % b) + b) % b).toFloat(),
        )
        val v = f.dustVelocity
        gl.uniform3f(dust.u("u_streak"), -v.x * 0.045f, -v.y * 0.045f, -v.z * 0.045f)
        gl.uniform1f(dust.u("u_box"), DustField.BOX)
        gl.uniform1f(dust.u("u_width"), 1.3f * f.pxScale * 2f / c.height)
        gl.uniform1f(dust.u("u_aspect"), c.width.toFloat() / c.height)
        gl.uniform1f(dust.u("u_intensity"), f.dustIntensity)
        gl.uniform3f(dust.u("u_color"), f.dustColor.x, f.dustColor.y, f.dustColor.z)
        dustMesh.drawRange(GL.TRIANGLES, dustCount * 6)
    }

    /**
     * Exhaust plumes from the model's engine glow discs (ShipExhaust: a hot core that turns from
     * near-white into the engine colour, and a wider, fainter veil; length = diameter x (0.4 idle ..
     * 3.4 full) x 1.6 on boost) and soft halos on the outside lights. The Roblox ships' nav lights
     * burn steadily (no blink pattern in the game's code).
     */
    private fun drawShipGlows(f: SceneFrame, ship: ShipState) {
        val c = f.camera
        val t = f.time
        val m = ships.model ?: return
        glowStream.begin()
        val thrust = ship.engineOutput.coerceIn(0f, 1f)
        val boost = ship.boosting
        val stretch = if (boost) EXHAUST_BOOST_LENGTH else 1f
        val bright = if (boost) EXHAUST_BOOST_BRIGHTNESS else 1f
        // exhaust direction (+Z in ship space: the plume carrier is aligned with the ship)
        ship.rot.rotate(0f, 0f, 1f, axis)
        ship.pos.relativeTo(c.pos, rel)
        for (n in m.nozzles) {
            val d = n.diameter
            ship.rot.rotate(n.x, n.y, n.z + d * EXHAUST_START, tmp)
            val px = rel.x + tmp.x
            val py = rel.y + tmp.y
            val pz = rel.z + tmp.z
            val len = d * (EXHAUST_IDLE + (EXHAUST_FULL - EXHAUST_IDLE) * thrust) * stretch
            // veil: wider, fainter, longer; only with some thrust
            if (thrust > EXHAUST_VEIL_THRESHOLD) {
                plume(px, py, pz, len * 1.5f, d * 0.75f, 0.28f * thrust * bright, n.r, n.g, n.b)
            }
            // hot core: the colour lerped 60 % towards white
            val hr = n.r + (1f - n.r) * 0.6f
            val hg = n.g + (1f - n.g) * 0.6f
            val hb = n.b + (1f - n.b) * 0.6f
            plume(px, py, pz, len, d * 0.475f, (0.45f + 0.55f * thrust) * bright, hr, hg, hb)
            billboard(px, py, pz, d * (0.55f + 0.35f * thrust), c, 0f, n.r, n.g, n.b, (0.25f + 0.35f * thrust) * bright)
        }
        // halos on the outside lights (Roblox: Neon parts with Point/SpotLights), not on parts the
        // current view hides
        val hidden = if (f.shipView == ShipModel.VIEW_COCKPIT) ShipModel.CLASS_EXTERIOR else ShipModel.CLASS_COCKPIT
        for (l in m.lights) {
            if (!l.enabled || l.cls == hidden || l.type == ShipModel.LIGHT_SURFACE) continue
            ship.rot.rotate(l.x, l.y, l.z, tmp)
            val size = if (l.type == ShipModel.LIGHT_SPOT) 1.1f else 0.5f + l.range * 0.07f
            billboard(rel.x + tmp.x, rel.y + tmp.y, rel.z + tmp.z, size, c, 0f, l.r, l.g, l.b, minOf(1f, 0.3f + l.brightness * 0.18f))
        }
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        gl.enable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        glow.use()
        gl.uniformMatrix4fv(glow.u("u_viewProj"), nearVP.m, 0)
        gl.uniform1f(glow.u("u_time"), t % GLOW_PERIOD)
        glowStream.flush()
        gl.enable(GL.CULL_FACE)
    }

    /** Camera-facing quad. uv spans -1..1. */
    private fun billboard(
        x: Float, y: Float, z: Float, size: Float, c: Camera, shape: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        if (!glowStream.hasRoom()) return
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
        if (!glowStream.hasRoom()) return
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

    // ------------------------------------------------------------------ stations

    /**
     * The stations of the pass [setPass] selected (the slice [planSlices] put them in, within
     * [STATION_DRAW_RANGE], in view, not hidden behind a planet: planets don't write depth).
     * Far-slice stations are drawn scaled towards the camera by the slice's scale, which keeps
     * their apparent size and position; it only keeps very far ones in the coordinate range of the
     * rest of the scene. Their depth precision comes from the far slice's fitted projection, not
     * from the scaling. Inside the terrain's far slices a station is drawn in every slice it
     * reaches into (the slice planes clip it), unscaled.
     */
    private fun drawStations(f: SceneFrame) {
        val c = f.camera
        val stations = content.system.stations
        val k = passScale
        var begun = false
        for (i in stations.indices) {
            if (!inPass(stationSlot[i])) continue
            val st = stations[i]
            st.pos.relativeTo(c.pos, rel)
            model.setTRS(rel.x * k, rel.y * k, rel.z * k, st.rot, k)
            if (!begun) {
                stationLit.begin(passVP, model, f, envTex, envLod, StationMesh.palette, StationMesh.params, 1f)
                begun = true
            } else {
                stationLit.setModel(model)
            }
            stationMeshes[st.model].draw()
        }
    }

    /**
     * The ring debris (RingDebris): the visible rocks in ring coordinates around [RingDebris.origin],
     * one instanced draw, tinted by the ring's colour (`setRing`: rocky albedo times the tint; an
     * icy ring is lighter, as the Moon textures are). Always near the ship: the near slice.
     */
    private fun drawDebris(f: SceneFrame) {
        val d = f.debris ?: return
        if (d.visible == 0) return
        val c = f.camera
        d.origin.relativeTo(c.pos, rel)
        model.setTRS(rel.x, rel.y, rel.z, d.frame, 1f)
        val t = d.tint
        val lift = if (d.icy) 1.3f else 1f
        debrisMaterial(AsteroidMesh.ROCK, t, 0.30f * lift, 0.9f)
        debrisMaterial(AsteroidMesh.DARK_ROCK, t, 0.20f * lift, 0.93f)
        debrisMaterial(AsteroidMesh.MINERAL, t, 0.40f * lift, 0.6f)
        debrisMesh.updateInstances(d.instances, d.visible)
        debrisLit.begin(nearVP, model, f, envTex, envLod, debrisPalette, debrisParams, 0f)
        debrisMesh.drawInstanced(d.visible)
    }

    /** Palette entry [i]: the ring tint (squared, about linear) times [albedo], with [rough]ness. */
    private fun debrisMaterial(i: Int, tint: FloatArray, albedo: Float, rough: Float) {
        val o = i * 4
        for (k in 0 until 3) {
            val v = tint[k].coerceIn(0f, 1.2f)
            debrisPalette[o + k] = v * v * albedo
        }
        debrisPalette[o + 3] = rough
        debrisParams[o] = 0f
    }

    /**
     * Glow sprites of the stations of the current pass, depth-tested against them; inside the
     * terrain's far slices each sprite in the one slice that owns its depth (additive: drawn once).
     */
    private fun drawStationLights(f: SceneFrame) {
        val c = f.camera
        val stations = content.system.stations
        glowStream.begin()
        val blink = (f.time % 1.6f) < 0.8f
        val k = passScale
        val owned = passOwnZNear > -Float.MAX_VALUE || passOwnZFar < Float.MAX_VALUE
        for (i in stations.indices) {
            if (!inPass(stationSlot[i])) continue
            val st = stations[i]
            st.pos.relativeTo(c.pos, rel)
            val d = rel.length()
            // readable from afar: sprites grow with distance (6..60 m) up to LIGHT_FAR, then keep
            // their apparent size; in true metres, then scaled with the station
            val dEff = minOf(d, LIGHT_FAR)
            val size = maxOf(6f, minOf(dEff * 0.012f, 60f)) * (d / dEff) * k
            val lights = content.stationLights[st.model]
            var o = 0
            while (o < lights.size) {
                val red = lights[o + 3] > 0.9f && lights[o + 4] < 0.3f
                if (red && !blink) {
                    o += 6
                    continue
                }
                st.rot.rotate(lights[o], lights[o + 1], lights[o + 2], tmp)
                if (owned) {
                    val z = (rel.x + tmp.x) * c.forward.x + (rel.y + tmp.y) * c.forward.y + (rel.z + tmp.z) * c.forward.z
                    if (z < passOwnZNear || z >= passOwnZFar) {
                        o += 6
                        continue
                    }
                }
                val lx = (rel.x + tmp.x) * k
                val ly = (rel.y + tmp.y) * k
                val lz = (rel.z + tmp.z) * k
                billboard(lx, ly, lz, size, c, 0f, lights[o + 3], lights[o + 4], lights[o + 5], 0.9f)
                o += 6
            }
        }
        if (glowStream.quads == 0) return
        gl.enable(GL.BLEND)
        gl.blendFunc(GL.ONE, GL.ONE)
        gl.depthMask(false)
        gl.enable(GL.DEPTH_TEST)
        gl.disable(GL.CULL_FACE)
        glow.use()
        gl.uniformMatrix4fv(glow.u("u_viewProj"), passVP.m, 0)
        gl.uniform1f(glow.u("u_time"), f.time % GLOW_PERIOD)
        glowStream.flush()
        gl.enable(GL.CULL_FACE)
        gl.depthMask(true)
        gl.disable(GL.BLEND)
    }

    /** True if a planet blocks the line of sight from [eye] to [p]. */
    private fun occludedByBody(p: com.starwake.core.math.DVec3, eye: com.starwake.core.math.DVec3): Boolean {
        val dx = p.x - eye.x
        val dy = p.y - eye.y
        val dz = p.z - eye.z
        val len = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (len < 1.0) return false
        val bodies = content.system.bodies
        for (i in bodies.indices) {
            val b = bodies[i]
            val cx = b.pos.x - eye.x
            val cy = b.pos.y - eye.y
            val cz = b.pos.z - eye.z
            val t = (cx * dx + cy * dy + cz * dz) / len
            if (t <= 0.0 || t >= len) continue
            val c2 = cx * cx + cy * cy + cz * cz
            if (c2 - t * t < b.radius * b.radius) return true
        }
        return false
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

    fun dispose() {
        for (p in arrayOf(sky, star, dust, glow)) p.dispose()
        stationLit.dispose()
        debrisLit.dispose()
        debrisMesh.dispose()
        for (m in stationMeshes) m.dispose()
        bodies.dispose()
        terrainRenderer?.dispose()
        fullscreen.dispose(); ships.dispose(); starMesh.dispose(); dustMesh.dispose()
        glowStream.dispose()
        gl.deleteTexture(envTex)
    }

    companion object {
        /** The near pass reaches at least this far when a terrain is drawn (m): 0.48 m depth steps there. */
        private const val TERRAIN_NEAR = 2000f

        /** The terrain fades in over the impostor's surface in this time (s) once it covers the sphere. */
        const val HANDOVER_SECONDS = 1f

        /** Smallest opacity once the fade started (the first frame already draws the terrain). */
        private const val MIN_FADE = 1f / 64f

        /** Period of the exhaust flicker (sin(t * 61)); the shader only ever sees t modulo this. */
        private const val GLOW_PERIOD = TAU_F / 61f

        /** Emission of the engine glow discs at full throttle (tone-mapped: bright, still coloured). */
        private const val ENGINE_DISC_GLOW = 1.6f

        /** ShipConfig.Exhaust: start behind the disc, length at idle / full throttle (x diameter), boost stretch. */
        private const val EXHAUST_START = 0.1f
        private const val EXHAUST_IDLE = 0.4f
        private const val EXHAUST_FULL = 3.4f
        private const val EXHAUST_BOOST_LENGTH = 1.6f

        /** BoostBrightness / Brightness (4 / 2.2). */
        private const val EXHAUST_BOOST_BRIGHTNESS = 4f / 2.2f

        /** No veil below this throttle (VeilThreshold). */
        private const val EXHAUST_VEIL_THRESHOLD = 0.05f
        private const val STATION_DRAW_RANGE = 3.0e6f

        /** Station light sprites keep their apparent size beyond this distance (m). */
        private const val LIGHT_FAR = 20_000f

        /**
         * Least depth (m) of the camera's own cluster before anything else joins it: the dust
         * streaks (~110 m), with margin. The ship and its plumes reach further from the far chase
         * camera of a big hull; SceneFrame.shipReach extends the cluster to cover them.
         */
        private const val NEAR_REACH = 250f

        /** The far slice is scaled about the eye so that it starts no farther than this (m). */
        private const val FAR_SCALE_FROM = 20_000f

        /** Relative slack on the far slice's planes (the bounding spheres are exact, float math is not). */
        private const val FAR_MARGIN = 0.002f


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

/** Uniform bindings shared by the station and ring-debris programs. */
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

    /** Binds the program with [viewProj] (the camera's or the far slice's) and the frame's lighting. */
    fun begin(viewProj: Mat4, model: Mat4, f: SceneFrame, env: Int, envLod: Float, palette: FloatArray, params: FloatArray, engine: Float) {
        prog.use()
        gl.uniformMatrix4fv(uViewProj, viewProj.m, 0)
        gl.uniformMatrix4fv(uModel, model.m, 0)
        gl.uniform4fv(uPalette, 10, palette, 0)
        gl.uniform4fv(uParams, 10, params, 0)
        gl.uniform1f(uTime, f.time)
        val s = f.sunDir
        gl.uniform3f(uSunDir, s.x, s.y, s.z)
        val sc = Environment.sunColor
        val v = f.sunVisibility
        gl.uniform3f(uSunColor, sc[0] * v, sc[1] * v, sc[2] * v)
        val p = f.fillDir
        gl.uniform3f(uFillDir, p.x, p.y, p.z)
        val fc = f.fillColor
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

    fun setModel(model: Mat4) = gl.uniformMatrix4fv(uModel, model.m, 0)

    fun dispose() = prog.dispose()
}

/**
 * Per-frame depth slicing (pure logic, unit tested): bounding spheres in view space are culled
 * against the view frustum, then split into the camera's own cluster and a far slice; see
 * SceneRenderer.planSlices. Allocation free after construction.
 */
internal class DepthSlices(capacity: Int) {
    private val zMin = FloatArray(capacity)
    private val zMax = FloatArray(capacity)
    private val far = BooleanArray(capacity)
    var count = 0
        private set

    /** True when the last [plan] put at least one object in the far slice. */
    var split = false
        private set

    /** View depth (m) where the far slice's nearest object starts (0 without a far slice). */
    var farNear = 0f
        private set

    /** View depth (m) where the far slice's farthest object ends. */
    var farFar = 0f
        private set

    /** View depth (m) where the camera's cluster ends. */
    var nearEnd = 0f
        private set

    /** Starts a frame: no objects. */
    fun begin() {
        count = 0
        split = false
    }

    /**
     * Adds a sphere of [radius] at view-space ([vx] right, [vy] up, [vz] along the view direction);
     * [tx]/[ty] are the tangents of the half field of view. Returns its slot, or -1 when it is
     * behind the camera or outside a side of the frustum.
     */
    fun add(vx: Float, vy: Float, vz: Float, radius: Float, tx: Float, ty: Float): Int {
        if (vz < -radius) return -1
        // signed distance outside the side planes x = ±tx z and y = ±ty z
        val nx = 1f / sqrt(1f + tx * tx)
        val ny = 1f / sqrt(1f + ty * ty)
        if ((abs(vx) - tx * vz) * nx > radius || (abs(vy) - ty * vz) * ny > radius) return -1
        val i = count++
        zMin[i] = vz - radius
        zMax[i] = vz + radius
        far[i] = false
        return i
    }

    /**
     * Grows the camera's cluster from depth [nearReach] by every object whose depth range starts
     * inside it (taking its end along); what is left is far. No object ends up in both slices.
     */
    fun plan(nearReach: Float) {
        var end = nearReach
        for (i in 0 until count) far[i] = true
        var grew = true
        while (grew) {
            grew = false
            for (i in 0 until count) {
                if (far[i] && zMin[i] <= end) {
                    far[i] = false
                    if (zMax[i] > end) end = zMax[i]
                    grew = true
                }
            }
        }
        nearEnd = end
        farNear = Float.MAX_VALUE
        farFar = 0f
        split = false
        for (i in 0 until count) {
            if (!far[i]) continue
            split = true
            if (zMin[i] < farNear) farNear = zMin[i]
            if (zMax[i] > farFar) farFar = zMax[i]
        }
        if (!split) farNear = 0f
    }

    /** Whether [slot] is in the far slice (after [plan]). */
    fun isFar(slot: Int): Boolean = far[slot]

    /** View depth (m) where the object in [slot] starts. */
    fun zMinOf(slot: Int): Float = zMin[slot]

    /** View depth (m) where the object in [slot] ends. */
    fun zMaxOf(slot: Int): Float = zMax[slot]
}
