package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.content.Content
import com.starwake.core.content.ShipCatalog
import com.starwake.core.content.ShipModel
import com.starwake.core.game.GearRig
import com.starwake.core.game.ShipState
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.math.DEG2RAD
import com.starwake.core.math.DVec3
import com.starwake.core.math.Vec3
import org.lwjgl.opengles.GLES20

/**
 * The eight Roblox ships through the real SceneRenderer: each from a 3/4 front and a side view under
 * the same light, far from the field and the stations, from the pilot's seat, and standing on its
 * gear; the VARROW's gear half-way and stowed; then the real Game: the cockpit, external and chase
 * views in flight, gear down in flight, the menu backdrop for every ship (cycled with "ship"), and
 * every hull docking in both stand-in stations' slots. [holograms] shows the holograms aboard.
 */
object ShipsScenario {
    /** 40 km behind the start (the field and the home station are not drawn), the gas giant ahead. */
    private val shipCentre = DVec3()

    fun run(env: ScenarioEnv) {
        showcase(env)
        inGame(env)
    }

    private fun showcase(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            val home = content.system.home
            shipCentre.set(home.x, home.y + 4000.0, home.z + 40_000.0)
            val scene = SceneRenderer(gl, content, 2)
            val f = SceneFrame()
            val ship = ShipState()
            val rig = GearRig()
            f.ship = ship
            f.gear = rig
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            f.drawField = false
            ship.engineOutput = 0.55f
            val centre = Vec3()
            var frame = 0L

            /** Camera [dist] radii from the hull's centre along (dx, dy, dz), looking at it. */
            fun view(m: ShipModel, name: String, dx: Float, dy: Float, dz: Float, dist: Float, fov: Float = 34f) {
                val l = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                val d = (m.radius * dist).toDouble()
                val cam = f.camera
                cam.fovY = fov * DEG2RAD
                cam.near = 0.5f
                cam.pos.set(shipCentre.x + dx / l * d, shipCentre.y + dy / l * d, shipCentre.z + dz / l * d)
                cam.rot.lookRotation(-dx, -dy, -dz, 0f, 1f, 0f)
                f.shipReach = (d + m.radius).toFloat()
                for (i in 0 until 3) { // settle (skyFade comes from the previous frame)
                    frame++
                    f.time = 2f + frame / 60f
                    cam.update(w, h)
                    scene.render(f)
                }
                GLES20.glFinish()
                s.drainErrors(frame)
                s.screenshot(name)
            }

            for (entry in ShipCatalog.all) {
                val t0 = System.nanoTime()
                val m = ShipModel.load(entry.id) { platform.readAsset(it) }
                val t1 = System.nanoTime()
                scene.setShipModel(m)
                rig.setModel(m, down = false)
                GLES20.glFinish()
                val t2 = System.nanoTime()
                // the hull's centre at shipCentre, nose towards -Z, level
                ship.rot.identity()
                centre.set(m.centreX, m.centreY, m.centreZ)
                ship.pos.set(shipCentre.x - centre.x, shipCentre.y - centre.y, shipCentre.z - centre.z)
                var bytes = 0
                for (c in m.chunks) bytes += c.vertices.size + c.indices.size
                r.note(
                    "%-8s parse %.1f ms, upload %.1f ms; %d chunks, %d vertices, %d KB GPU; triangles outside %d, from the seat %d; %d bones, %d nozzles, %d lights; bounds %.1f x %.1f x %.1f m, radius %.1f m"
                        .format(
                            m.name, (t1 - t0) / 1e6, (t2 - t1) / 1e6, m.chunks.size, m.chunks.sumOf { it.vertexCount }, bytes / 1024,
                            m.triangles(ShipModel.VIEW_EXTERIOR), m.triangles(ShipModel.VIEW_COCKPIT), m.boneCount, m.nozzles.size,
                            m.lights.size, m.bounds[3] - m.bounds[0], m.bounds[4] - m.bounds[1], m.bounds[5] - m.bounds[2], m.radius,
                        ),
                )
                f.shipView = ShipModel.VIEW_EXTERIOR
                view(m, "ship-${m.id}-front", -0.62f, 0.30f, -0.72f, 2.6f)
                r.note("  ${m.id} outside: ${scene.shipDrawCalls} ship draw calls, ${scene.shipTriangles} triangles")
                view(m, "ship-${m.id}-side", -1f, 0.08f, 0.04f, 2.5f)
                // from the seat: eye point, looking ahead, Roblox's FOV
                f.shipView = ShipModel.VIEW_COCKPIT
                val cam = f.camera
                cam.fovY = 80f * DEG2RAD
                cam.pos.set(ship.pos.x + m.eyeX, ship.pos.y + m.eyeY, ship.pos.z + m.eyeZ)
                cam.rot.identity()
                for (i in 0 until 3) {
                    frame++
                    f.time = 2f + frame / 60f
                    cam.update(w, h)
                    scene.render(f)
                }
                GLES20.glFinish()
                s.drainErrors(frame)
                s.screenshot("ship-${m.id}-cockpit")
                r.note("  ${m.id} from the seat: ${scene.shipDrawCalls} ship draw calls, ${scene.shipTriangles} triangles")
                f.shipView = ShipModel.VIEW_EXTERIOR
                // standing on its gear, seen low from the front-left
                rig.setModel(m, down = true)
                view(m, "ship-${m.id}-gear", -0.7f, -0.25f, -0.66f, 1.9f, 34f)
                rig.setModel(m, down = false)
                if (m.id == "varrow") gearViews(m, rig, ::view, r)
            }
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    /**
     * The holograms of the KESSOCK, LOCHRAN and MORVEN (Roblox ForceField: see-through glows that
     * add light, brightest at the rim) from 3 m away in their room, from the centre-line side, at
     * two moments of their shimmer. In the exterior view, as a pilot on foot sees them: the
     * LOCHRAN's sit in its Interior, hidden from the seat. Checks that the shimmer moves.
     */
    fun holograms(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            val home = content.system.home
            val scene = SceneRenderer(s.gl, content, 2)
            val f = SceneFrame()
            val ship = ShipState()
            val rig = GearRig()
            f.ship = ship
            f.gear = rig
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0f
            f.drawField = false
            f.shipView = ShipModel.VIEW_EXTERIOR
            ship.engineOutput = 0.3f
            ship.pos.set(home.x, home.y + 4000.0, home.z + 40_000.0)
            ship.rot.identity()
            val cam = f.camera
            cam.fovY = 60f * DEG2RAD
            cam.near = 0.5f
            var frame = 0L
            var shown = 0
            for (entry in ShipCatalog.all) {
                val m = ShipModel.load(entry.id) { platform.readAsset(it) }
                val holo = forceFieldBox(m) ?: continue
                shown++
                scene.setShipModel(m)
                rig.setModel(m, down = false)
                val hx = (holo[0] + holo[3]) * 0.5f
                val hy = (holo[1] + holo[4]) * 0.5f
                val hz = (holo[2] + holo[5]) * 0.5f
                val ox = if (hx > 0.5f) -2.1f else 2.1f
                cam.pos.set(ship.pos.x + hx + ox, ship.pos.y + hy + 0.85f, ship.pos.z + hz - 1.5f)
                cam.rot.lookRotation(-ox, -0.85f, 1.5f, 0f, 1f, 0f)
                f.shipReach = 200f
                val shots = Array(2) { k ->
                    for (i in 0 until 3) {
                        frame++
                        f.time = 2f + k * 0.65f + i / 60f
                        cam.update(w, h)
                        scene.render(f)
                    }
                    GLES20.glFinish()
                    s.drainErrors(frame)
                    s.screenshot(if (k == 0) "holo-${m.id}" else "holo-${m.id}-later")
                }
                val moved = changedPixels(shots[0], shots[1])
                r.note("${m.name} holograms at (%.1f, %.1f, %.1f), %.1f m across: the shimmer changed %d px in 0.65 s".format(hx, hy, hz, holo[3] - holo[0], moved))
                check(moved > 1000) { "${m.name}: the hologram shimmer does not move" }
            }
            check(shown == 3) { "expected the holograms of 3 ships, found $shown" }
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    /** Ship-space box (min x, y, z, max x, y, z) of [m]'s ForceField vertices (hull chunk), or null. */
    private fun forceFieldBox(m: ShipModel): FloatArray? {
        val c = m.chunks[0]
        val b = java.nio.ByteBuffer.wrap(c.vertices).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        var n = 0
        for (v in 0 until c.vertexCount) {
            val o = v * ShipModel.VERTEX_BYTES
            if ((b.getShort(o + 6).toInt() and 0xFF) != ShipModel.MAT_FORCEFIELD) continue
            n++
            for (k in 0..2) {
                val origin = if (k == 0) c.originX else if (k == 1) c.originY else c.originZ
                val p = origin + (b.getShort(o + 2 * k).toInt() and 0xFFFF) * c.scale
                box[k] = minOf(box[k], p)
                box[k + 3] = maxOf(box[k + 3], p)
            }
        }
        return if (n > 0) box else null
    }

    /** Pixels whose colour differs by more than a few levels between two screenshots. */
    private fun changedPixels(a: java.io.File, b: java.io.File): Int {
        val ia = javax.imageio.ImageIO.read(a)
        val ib = javax.imageio.ImageIO.read(b)
        var n = 0
        for (y in 0 until ia.height) for (x in 0 until ia.width) {
            val p = ia.getRGB(x, y)
            val q = ib.getRGB(x, y)
            var d = 0
            for (sh in intArrayOf(0, 8, 16)) d += kotlin.math.abs(((p shr sh) and 255) - ((q shr sh) and 255))
            if (d > 12) n++
        }
        return n
    }

    private fun gearViews(m: ShipModel, rig: GearRig, view: (ShipModel, String, Float, Float, Float, Float, Float) -> Unit, r: ScenarioReport) {
        // low, from the front-left: legs, feet and doors folding (ship-varrow-gear shows them down)
        rig.setModel(m, down = true)
        var t = 0f
        while (rig.legT < 0.5f) {
            rig.update(1f / 60f, false, false)
            t += 1f / 60f
        }
        r.note("  gear: legs half up after %.2f s (legT %.2f, doorT %.2f, down %.2f)".format(t, rig.legT, rig.doorT, rig.downFraction))
        view(m, "ship-varrow-gear-mid", -0.7f, -0.25f, -0.66f, 1.9f, 34f)
        while (!rig.stowed) {
            rig.update(1f / 60f, false, false)
            t += 1f / 60f
        }
        r.note("  gear: stowed after %.2f s (closed hull doors shown)".format(t))
        view(m, "ship-varrow-gear-up", -0.7f, -0.25f, -0.66f, 1.9f, 34f)
        rig.setModel(m, down = false)
    }

    private fun inGame(env: ScenarioEnv) {
        GlSession(env).use { session ->
            val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
            val game = Game(platform)
            val d = GameDriver(game, session.gl, session, platform, env.report, env.opts, env.opts.insets)
            d.start()
            d.frames(90)
            d.shot("ships-menu-varrow")
            d.cmd("launch space")
            d.frames(60)
            Scenarios.run { d.throttleUp(0.35f, 30) }
            d.frames(60)
            d.log(game.debugInfo)
            // the Roblox views: cockpit (default) and the ship's external camera; the chase view on request
            d.shot("ships-cockpit")
            d.cmd("view external")
            d.frames(40)
            d.shot("ships-external")
            d.cmd("view chase")
            d.frames(40)
            d.shot("ships-chase")
            d.cmd("view cockpit")
            d.frames(10)
            // steer a little: the cockpit and the world around it
            val right = d.pointer(1).down(0.75f * d.w, 0.65f * d.h)
            right.dragTo(0.75f * d.w - 40f, 0.65f * d.h - 30f, 20)
            d.frames(30)
            d.shot("ships-cockpit-turn")
            right.up()
            d.cmd("view external")
            d.frames(20)
            d.cmd("gear-down")
            d.frames(170)
            d.log(game.debugInfo)
            check(Regex("gear=1.00").containsMatchIn(game.debugInfo)) { "gear not down after 2.8 s: ${game.debugInfo}" }
            d.shot("ships-gear-down-external")
            d.cmd("gear-up")
            d.frames(170)
            d.log(game.debugInfo)
            check(Regex("stowed=true").containsMatchIn(game.debugInfo)) { "gear not stowed after 2.8 s: ${game.debugInfo}" }
            d.cmd("gear-auto")
            d.cmd("menu")
            d.frames(5)
            for (i in 1 until ShipCatalog.all.size) {
                d.cmd("ship")
                d.frames(60)
                val id = ShipCatalog.all[i].id
                check(Regex("ship=$id ").containsMatchIn(game.debugInfo)) { "ship command did not switch to $id: ${game.debugInfo}" }
                d.shot("ships-menu-$id")
            }
            // every hull docks in both stand-in slots, as every Roblox hangar takes every ship: lined
            // up 20 m in front of OUTPOST II's slot (the smaller one) and HOME STATION's, it slides in,
            // parks with its centre on the slot axis and launches clear (the route, the slot and the
            // launch place the hull's centre, not the pilot's eye at the ship's origin). Auto-dock
            // flies without collision, so the probe gap to the station must stay open all the way.
            fun hull() = Regex("hull=([^ ]+)").find(game.debugInfo)!!.groupValues[1].toFloat()
            fun fly(id: String) {
                d.cmd("menu")
                d.frames(5)
                d.cmd("ship=$id")
                d.frames(5)
                d.cmd("launch space")
                d.frames(30)
            }

            /** Flies until docked; returns the closest probe gap to the station on the way. */
            fun untilDocked(what: String, limit: Int, shotAt: Int = -1, shot: String = ""): Float {
                var n = 0
                var minHull = Float.MAX_VALUE
                var minAt = 0
                while (game.stateName != "docked" && n < limit) {
                    d.frames(1)
                    n++
                    val gap = hull()
                    if (gap < minHull) {
                        minHull = gap
                        minAt = n
                    }
                    if (n == shotAt) d.shot(shot)
                }
                check(game.stateName == "docked") { "$what never docked: ${game.debugInfo}" }
                d.frames(20)
                d.log("%s: docked after %d frames, closest hull gap %.1f m (frame %d), parked %.1f m".format(what, n, minHull, minAt, hull()))
                return minHull
            }
            var tightest = Float.MAX_VALUE
            for ((station, where) in listOf(1 to "OUTPOST II", 0 to "HOME STATION")) {
                for (e in ShipCatalog.all) {
                    fly(e.id)
                    d.cmd("docked=$station")
                    val what = "${e.name} into $where"
                    val gap = untilDocked(what, 60 * 20)
                    check(gap > MIN_DOCK_GAP) { "$what came within $gap m of the station (collision is off while docking)" }
                    check(hull() > MIN_DOCK_GAP) { "$what parked ${hull()} m from the walls" }
                    tightest = minOf(tightest, gap)
                    if (station == 1 || e.id == "morven") d.shot("ships-docked-${if (station == 1) "outpost" else "home"}-${e.id}")
                    game.launchFromStation()
                    d.frames(2)
                    check(hull() > 10f) { "$what launched into the hull: ${game.debugInfo}" }
                }
            }
            d.log("docking: the closest any hull came to a station on the slide-in is %.1f m".format(tightest))
            // the biggest hull flies the whole route into OUTPOST II: from 1.5 km behind it, round the
            // outpost to the stand-off in front of the slot, then in
            fly("morven")
            d.cmd("behind-station=1")
            d.frames(2)
            d.cmd("dock=1")
            d.frames(1)
            check(game.debugInfo.contains("docking=true")) { "auto-dock refused the MORVEN at OUTPOST II: ${game.debugInfo}" }
            val route = untilDocked("MORVEN from behind OUTPOST II", 60 * 120, 400, "ships-route-morven")
            check(route > MIN_DOCK_GAP) { "the MORVEN's route came within $route m of OUTPOST II (collision is off while docking)" }
            d.shot("ships-route-morven-docked")
            game.launchFromStation()
            d.frames(2)
            check(hull() > 10f) { "MORVEN launched into OUTPOST II: ${game.debugInfo}" }
            d.cmd("menu")
            d.frames(5)
            d.cmd("ship=varrow")
            d.frames(5)
        }
    }

    /** Closest a docking hull's collision probes may come to the station (m): the slots leave ~4.5 m. */
    private const val MIN_DOCK_GAP = 2f
}
