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
 * gear; the VARROW's gear half-way and stowed; then the real Game: chase, far chase and cockpit
 * views in flight, gear down in flight, and the menu backdrop for every ship (cycled with "ship").
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
            d.cmd("launch")
            d.frames(60)
            Scenarios.run { d.throttleUp(0.35f, 30) }
            d.frames(60)
            d.log(game.debugInfo)
            d.shot("ships-chase")
            d.cmd("camera")
            d.frames(40)
            d.shot("ships-far-chase")
            d.cmd("camera")
            d.frames(40)
            d.shot("ships-cockpit")
            // steer a little: the cockpit and the world around it
            val right = d.pointer(1).down(0.75f * d.w, 0.65f * d.h)
            right.dragTo(0.75f * d.w - 40f, 0.65f * d.h - 30f, 20)
            d.frames(30)
            d.shot("ships-cockpit-turn")
            right.up()
            d.cmd("camera")
            d.frames(20)
            d.cmd("gear-down")
            d.frames(170)
            d.log(game.debugInfo)
            check(Regex("gear=1.00").containsMatchIn(game.debugInfo)) { "gear not down after 2.8 s: ${game.debugInfo}" }
            d.shot("ships-gear-down-chase")
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
            d.cmd("ship=varrow")
            d.frames(5)
        }
    }
}
