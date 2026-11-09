package com.starwake.desktop

import com.starwake.core.game.StarSystem
import com.starwake.core.gfx.FontData
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.Quat
import com.starwake.core.ui.Frame
import com.starwake.core.ui.MapHost
import com.starwake.core.ui.MapScreen
import java.lang.management.ManagementFactory
import kotlin.math.hypot
import kotlin.math.min

/**
 * The system map on its own (no Game, no scene behind it), driven through MapScreen's real touch
 * handlers: the opening view with nothing, OUTPOST II and PLANET III selected (label overlap
 * checks), a pinch on PLANET II that must keep the planet under the fingers, a two-finger pan, a
 * tap that selects PLANET I, close-ups around the ship (YOU, PLANET III, HOME STATION), and a
 * zero-allocation check of MapScreen.move + draw. Finger positions are drawn as magenta rings,
 * their midpoint as a cross, so the pinch anchoring can be checked by eye; the drift of the pinched
 * point from the midpoint is logged.
 */
object MapScenario {
    private const val FINGER = 0xFFFF40C0.toInt()

    private class Host : MapHost {
        var lockedKind = 0
        var lockedIndex = -1
        override fun haptic(type: Int) {}
        override fun closeMap() {}

        override fun lockFromMap(kind: Int, index: Int) {
            lockedKind = kind; lockedIndex = index
        }
    }

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height
            val platform = DesktopPlatform(opts.assetsDir)
            val font = FontData.parse(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8))
            val ui = UiRenderer(gl, font, platform.readAsset("font/ui.sdf"))

            // the same UI unit and insets Game.onSurfaceChanged would use
            val d = if (opts.density > 0f) opts.density else 1f
            val f = Frame().apply {
                this.w = w.toFloat(); this.h = h.toFloat()
                u = d * min(1f, min(h / d / 380f, w / d / 720f))
                sl = opts.insets[0]; st = opts.insets[1]; sr = opts.insets[2]; sb = opts.insets[3]
            }
            val system = StarSystem()
            val host = Host()
            val map = MapScreen(host, system)
            map.layout(f)
            val ship = system.home
            // any heading: the arrow just has to point somewhere
            val rot = Quat().setAxisAngle(0f, 1f, 0f, 2.2f)
            var frame = 0L
            var fingers = 0
            val fx = FloatArray(2)
            val fy = FloatArray(2)

            fun draw(name: String?) {
                frame++
                gl.bindFramebuffer(GL.FRAMEBUFFER, gl.screenFramebuffer)
                gl.viewport(0, 0, w, h)
                gl.clearColor(0.02f, 0.03f, 0.05f, 1f)
                gl.clear(GL.COLOR_BUFFER_BIT)
                ui.begin(w, h)
                map.draw(ui, 1f, ship, rot, frame / 60f)
                if (fingers == 2) {
                    for (i in 0 until 2) ui.circle(fx[i], fy[i], 22f * f.u, FINGER, 2f * f.u)
                    val mx = (fx[0] + fx[1]) * 0.5f
                    val my = (fy[0] + fy[1]) * 0.5f
                    ui.line(mx - 12f * f.u, my, mx + 12f * f.u, my, 2f * f.u, FINGER)
                    ui.line(mx, my - 12f * f.u, mx, my + 12f * f.u, 2f * f.u, FINGER)
                }
                ui.end()
                s.endFrame(frame)
                if (name != null) s.screenshot(name)
            }

            fun bodyAt(name: String): StarSystem.Body = system.bodies.first { it.name == name }

            /** Logs how far the point ([wx], [wz]) called [name] drifted from the finger midpoint (the pinch anchor). */
            fun anchor(label: String, name: String, wx: Double, wz: Double) {
                val mx = (fx[0] + fx[1]) * 0.5f
                val my = (fy[0] + fy[1]) * 0.5f
                val bx = map.screenX(wx)
                val by = map.screenY(wz)
                r.note(
                    "%s: %s at (%.1f, %.1f), finger midpoint (%.1f, %.1f), drift %.2f px, zoom %.3g m/px"
                        .format(label, name, bx, by, mx, my, hypot(bx - mx, by - my), map.metresPerPixel),
                )
            }

            fun anchor(label: String, b: StarSystem.Body) = anchor(label, b.name, b.pos.x, b.pos.z)

            /** Two fingers down at (x ± d/2, y). */
            fun pinchDown(x: Float, y: Float, dist: Float) {
                fx[0] = x - dist * 0.5f; fy[0] = y
                fx[1] = x + dist * 0.5f; fy[1] = y
                map.down(10, fx[0], fy[0])
                map.down(11, fx[1], fy[1])
                fingers = 2
            }

            /** Moves both fingers over [n] frames (one move event per finger per frame) to the given spots. */
            fun pinchTo(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
                val sx0 = fx[0]; val sy0 = fy[0]; val sx1 = fx[1]; val sy1 = fy[1]
                for (k in 1..n) {
                    val t = k.toFloat() / n
                    fx[0] = sx0 + (x0 - sx0) * t; fy[0] = sy0 + (y0 - sy0) * t
                    map.move(10, fx[0], fy[0])
                    fx[1] = sx1 + (x1 - sx1) * t; fy[1] = sy1 + (y1 - sy1) * t
                    map.move(11, fx[1], fy[1])
                    draw(null)
                }
            }

            fun pinchUp() {
                map.up(11, fx[1], fy[1], false)
                map.up(10, fx[0], fy[0], false)
                fingers = 0
            }

            // 1. opening views: nothing selected, OUTPOST II locked (the review's case), PLANET III locked
            map.open(ship, 0, -1)
            draw("map-open")
            map.open(ship, 2, 1)
            draw("map-outpost")
            map.open(ship, 3, 1)
            draw("map-planet3")

            // 2. pinch on PLANET II, zooming in x10: it has to stay under the fingers
            map.open(ship, 2, 1)
            draw(null)
            val p2 = bodyAt("PLANET II")
            val px = map.screenX(p2.pos.x)
            val py = map.screenY(p2.pos.z)
            val spread = 0.04f * w
            pinchDown(px, py, spread)
            anchor("pinch start", p2)
            pinchTo(px - spread * 5f, py, px + spread * 5f, py, 20)
            anchor("after x10 pinch", p2)
            draw("map-pinch")

            // 3. keep both fingers down: drag them towards the screen centre (two-finger pan) while
            // spreading a little more (x2), so OUTPOST II separates from the planet
            val cx = w * 0.45f
            val cy = h * 0.5f
            pinchTo(cx - spread * 10f, cy - 0.05f * h, cx + spread * 10f, cy + 0.05f * h, 30)
            anchor("after pan + x2", p2)
            draw("map-pinch-pan")
            pinchUp()

            // 4. one more pinch in, until station labels show up (below 2 Mm/px)
            val qx = map.screenX(p2.pos.x)
            val qy = map.screenY(p2.pos.z)
            pinchDown(qx, qy, spread)
            pinchTo(qx - spread * 4f, qy, qx + spread * 4f, qy, 20)
            anchor("after x8 pinch", p2)
            pinchUp()
            draw("map-zoomed")

            // 5. back to the opening view and tap PLANET I: it becomes the selection
            map.open(ship, 0, -1)
            draw(null)
            val p1 = bodyAt("PLANET I")
            val tx = map.screenX(p1.pos.x)
            val ty = map.screenY(p1.pos.z)
            map.down(12, tx, ty)
            map.up(12, tx, ty, false)
            draw("map-tap")
            // LOCK TARGET reports the tapped body
            val lockX = w - f.sr - 18f * f.u - 100f * f.u
            val lockY = h - f.sb - 18f * f.u - 22f * f.u
            map.down(13, lockX, lockY)
            map.up(13, lockX, lockY, false)
            val ok = host.lockedKind == 3 && system.bodies[host.lockedIndex].name == "PLANET I"
            r.note("tap PLANET I at (%.0f, %.0f) then LOCK TARGET: lockFromMap(%d, %d) -> %s".format(tx, ty, host.lockedKind, host.lockedIndex, if (ok) "OK" else "WRONG"))
            if (!ok) r.failure = IllegalStateException("tap-select on the map did not select PLANET I")

            // 6. around the ship: pinch on it until the stations are labelled (YOU, PLANET III and
            // HOME STATION crowd each other), then much closer, where the station sits under the arrow
            map.open(ship, 0, -1)
            draw(null)
            val sx = map.screenX(ship.x)
            val sy = map.screenY(ship.z)
            pinchDown(sx, sy, spread)
            pinchTo(sx - spread * 3f, sy, sx + spread * 3f, sy, 20)
            anchor("pinch on the ship x6", "the ship", ship.x, ship.z)
            draw("map-home")
            pinchUp()
            pinchDown(sx, sy, spread * 0.25f)
            pinchTo(sx - spread * 10f, sy, sx + spread * 10f, sy, 30)
            pinchUp()
            draw("map-home-close")
            r.note("home close-up at %.3g m/px".format(map.metresPerPixel))

            // 7. the per-frame path must not allocate: pinch in and out over PLANET II (labels placed,
            // stations labelled part of the time, OUTPOST II selected) and count heap bytes on this
            // thread around MapScreen.move + draw only, after a warm-up
            val meter = PerfMeter(1, null) // enables the counter and measures its own overhead
            val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
            val overhead = meter.overhead
            map.open(ship, 2, 1)
            val ax = map.screenX(p2.pos.x)
            val ay = map.screenY(p2.pos.z)
            map.down(20, ax - 40f, ay)
            map.down(21, ax + 40f, ay)
            var allocated = 0L
            var allocFrames = 0
            for (k in 0 until 480) {
                // spread oscillates between 80 px and ~1300 px: about 4x to 0.25x the opening zoom
                val half = 40f + 610f * (0.5f - 0.5f * kotlin.math.cos(k * 0.05f))
                val t = frame / 60f
                ui.begin(w, h)
                val a0 = mx.currentThreadAllocatedBytes
                map.move(20, ax - half, ay)
                map.move(21, ax + half, ay)
                map.draw(ui, 1f, ship, rot, t)
                val bytes = mx.currentThreadAllocatedBytes - a0 - overhead
                ui.end()
                frame++
                s.endFrame(frame)
                if (k >= 240 && bytes > 0) {
                    allocated += bytes; allocFrames++
                }
            }
            map.up(21, ax + 40f, ay, false)
            map.up(20, ax - 40f, ay, false)
            r.note("MapScreen.move + draw over 240 measured frames: $allocated B allocated in $allocFrames frames (counter self-test: a 128-long array reads ${meter.selfTest()} B)")
            if (allocated > 0) r.failure = IllegalStateException("MapScreen allocates per frame ($allocated B over 240 frames)")

            ui.dispose()
            s.drainErrors(frame)
        }
    }
}
