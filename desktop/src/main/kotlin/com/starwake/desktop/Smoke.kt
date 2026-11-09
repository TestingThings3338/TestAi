package com.starwake.desktop

import com.starwake.core.content.Content
import com.starwake.core.content.ShipModel
import com.starwake.core.game.GearRig
import com.starwake.core.game.ShipState
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.gfx.SceneFrame
import com.starwake.core.gfx.SceneRenderer
import com.starwake.core.gfx.Shaders
import com.starwake.core.gfx.UiRenderer
import org.lwjgl.opengles.GLES20
import kotlin.math.cos
import kotlin.math.sin

/**
 * Validates the GL layer and every shader without going through [com.starwake.core.Game]:
 * compiles each shader source on its own (so all compile errors show up at once), then builds the
 * real SceneRenderer + UiRenderer and renders an orbiting view of a ship at the origin.
 */
object Smoke {
    private class View(val name: String, val yaw: Float, val pitch: Float, val distance: Float, val engine: Float)

    private val views = listOf(
        // from behind-left, looking past the ship towards the gas giant
        View("smoke", -0.80f, 0.20f, 27f, 0.8f),
        // front three-quarter view, the sun is in the background
        View("smoke-front", 2.55f, 0.16f, 25f, 0.25f),
    )

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        GlSession(env).use { s ->
            val gl = s.gl
            val w = opts.width
            val h = opts.height

            val failures = ShaderCheck.run(gl, r)
            if (failures > 0) r.note("$failures shader(s) failed to compile (see above); building renderers anyway")

            val platform = DesktopPlatform(opts.assetsDir)
            var t0 = System.nanoTime()
            val content = Content(String(platform.readAsset("font/ui.fnt"), Charsets.UTF_8), platform.readAsset("font/ui.sdf"))
            r.note("Content (meshes, asteroid field, stars, font) built in %.0f ms".format((System.nanoTime() - t0) / 1e6))

            t0 = System.nanoTime()
            val scene = SceneRenderer(gl, content, 2)
            GLES20.glFinish()
            r.note("SceneRenderer(quality 2): programs linked + environment/planet bakes in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            t0 = System.nanoTime()
            val ui = UiRenderer(gl, content.font, content.fontAtlas)
            r.note("UiRenderer + font atlas upload in %.0f ms".format((System.nanoTime() - t0) / 1e6))
            s.drainErrors(0)

            t0 = System.nanoTime()
            val model = ShipModel.load("varrow") { platform.readAsset(it) }
            val rig = GearRig().also { it.setModel(model, down = false) }
            scene.setShipModel(model)
            GLES20.glFinish()
            r.note("VARROW model parsed + uploaded in %.0f ms (%d triangles outside)".format((System.nanoTime() - t0) / 1e6, model.triangles(ShipModel.VIEW_EXTERIOR)))
            s.drainErrors(0)

            val f = SceneFrame()
            val ship = ShipState()
            // the hull's centre at the origin, which the camera circles
            ship.pos.set(-model.centreX.toDouble(), -model.centreY.toDouble(), -model.centreZ.toDouble())
            f.ship = ship
            f.gear = rig
            f.shipReach = model.radius * 4f
            f.quality = 2
            f.pxScale = h / 1080f
            f.dustIntensity = 0.6f

            var frame = 0L
            val renderNanos = ArrayList<Long>()
            for (v in views) {
                ship.engineOutput = v.engine
                for (i in 0 until 4) {
                    frame++
                    f.time = frame / 60f
                    orbit(f, v.yaw + i * 0.004f, v.pitch, v.distance * model.radius / 8f)
                    f.camera.update(w, h)
                    val t = System.nanoTime()
                    scene.render(f)
                    drawUi(ui, w, h, v.name)
                    gl.invalidateDepth()
                    GLES20.glFinish()
                    renderNanos.add(System.nanoTime() - t)
                    s.drainErrors(frame)
                }
                s.screenshot(v.name)
            }
            val warm = renderNanos.drop(2).sorted()
            r.note(
                "frame (scene + UI + glFinish on llvmpipe): median %.1f ms, max %.1f ms over %d frames"
                    .format(warm[warm.size / 2] / 1e6, warm.last() / 1e6, warm.size),
            )
            ui.dispose()
            scene.dispose()
            s.drainErrors(frame)
        }
    }

    /** Camera orbiting the origin, like the menu's OrbitCamera. */
    private fun orbit(f: SceneFrame, yaw: Float, pitch: Float, dist: Float) {
        val cp = cos(pitch)
        val ox = sin(yaw) * cp * dist
        val oy = sin(pitch) * dist
        val oz = cos(yaw) * cp * dist
        f.camera.pos.set(ox.toDouble(), oy.toDouble(), oz.toDouble())
        f.camera.rot.lookRotation(-ox, -oy, -oz, 0f, 1f, 0f)
    }

    private fun drawUi(ui: UiRenderer, w: Int, h: Int, label: String) {
        val s = h / 1080f
        ui.begin(w, h)
        // panel with a title
        ui.rect(48f * s, 48f * s, 820f * s, 200f * s, 0xB0081420.toInt(), radius = 22f * s)
        ui.rect(48f * s, 48f * s, 820f * s, 200f * s, 0xFF3FC8FF.toInt(), radius = 22f * s, stroke = 3f * s)
        ui.text("SPACE 0123", 88f * s, 170f * s, 96f * s, 0xFFFFFFFF.toInt(), tracking = 0.06f, shadow = 0.6f)
        ui.text(label.uppercase() + "  /  LLVMPIPE", 92f * s, 222f * s, 30f * s, 0xFF9FD8FF.toInt(), tracking = 0.12f)
        // chamfered button, rings, a soft glow, a line
        ui.rect(w - 560f * s, h - 190f * s, 480f * s, 120f * s, 0xE0FF7A1A.toInt(), radius = -24f * s)
        ui.textCentered("BOOST", w - 320f * s, h - 130f * s, 54f * s, 0xFF101010.toInt(), weight = 0.05f)
        ui.circle(w - 190f * s, 190f * s, 110f * s, 0xFF3FC8FF.toInt(), stroke = 6f * s)
        ui.circle(w - 190f * s, 190f * s, 36f * s, 0xFFFFFFFF.toInt())
        ui.circle(w - 190f * s, 190f * s, 70f * s, 0x553FC8FF, soft = 24f * s)
        ui.line(80f * s, h - 120f * s, 700f * s, h - 160f * s, 6f * s, 0xFF66FF99.toInt())
        ui.text("0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZ abcdefghijklmnopqrstuvwxyz", 80f * s, h - 60f * s, 34f * s, 0xFFFFFFFF.toInt())
        ui.end()
    }
}

/** Compiles every shader source exposed by [Shaders] on its own and reports the driver's log. */
object ShaderCheck {
    class Source(val name: String, val type: Int, val src: String)

    fun sources(): List<Source> {
        val out = ArrayList<Source>()
        for (m in Shaders::class.java.methods.sortedBy { it.name }) {
            if (m.returnType != String::class.java) continue
            val base = when {
                m.parameterCount == 0 && m.name.startsWith("get") -> m.name.removePrefix("get").replaceFirstChar { it.lowercase() }
                m.parameterCount == 1 && m.parameterTypes[0] == java.lang.Boolean.TYPE -> m.name
                else -> continue
            }
            val type = when {
                base.endsWith("Vs") -> GL.VERTEX_SHADER
                base.endsWith("Fs") -> GL.FRAGMENT_SHADER
                else -> continue
            }
            if (m.parameterCount == 0) {
                out.add(Source(base, type, m.invoke(Shaders) as String))
            } else {
                for (flag in booleanArrayOf(false, true)) out.add(Source("$base($flag)", type, m.invoke(Shaders, flag) as String))
            }
        }
        return out
    }

    /** Returns the number of failures. */
    fun run(gl: Gl, r: ScenarioReport): Int {
        var failed = 0
        val ok = ArrayList<String>()
        for (s in sources()) {
            val id = gl.createShader(s.type)
            gl.shaderSource(id, s.src)
            gl.compileShader(id)
            val status = gl.getShaderi(id, GL.COMPILE_STATUS)
            val log = gl.getShaderInfoLog(id).trim()
            gl.deleteShader(id)
            if (status == 0) {
                failed++
                r.note("SHADER COMPILE FAILED [${s.name}]:\n$log")
            } else {
                ok.add(s.name)
                if (log.isNotEmpty()) r.note("shader [${s.name}] compiled with log:\n$log")
            }
        }
        r.note("shader check: ${ok.size} compiled OK (${ok.joinToString(" ")}), $failed failed")
        return failed
    }
}
