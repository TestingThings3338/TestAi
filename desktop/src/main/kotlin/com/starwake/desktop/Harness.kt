package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.gfx.GL
import com.starwake.core.gfx.Gl
import com.starwake.core.input.InputQueue
import com.starwake.core.input.TouchFlightControls
import com.starwake.core.ui.Rect
import com.starwake.core.ui.UiHost
import org.lwjgl.opengles.GLES20
import java.io.File
import kotlin.math.min

/** 60 Hz in nanoseconds: frame N is presented at N * FRAME_NANOS, so runs are deterministic. */
const val FRAME_NANOS = 16_666_667L

class Options(
    val outDir: File,
    val assetsDir: File,
    val width: Int,
    val height: Int,
    val density: Float,
    val scenarios: List<String>,
    val glDebug: Boolean,
    val msaa: Int,
    /** Safe insets left, top, right, bottom in surface pixels. */
    val insets: FloatArray,
    val perfFrames: Int,
    /** Initial persisted settings string, e.g. "q=1;showfps=1". */
    val settings: String?,
)

/** Results of one scenario, collected for the final summary. */
class ScenarioReport(val name: String) {
    val shots = ArrayList<Pair<File, ImageStats>>()
    val notes = ArrayList<String>()
    var glErrors = 0
    var failure: Throwable? = null
    var millis = 0L

    fun note(s: String) {
        notes.add(s)
        println("  $s")
    }
}

class ScenarioEnv(val opts: Options, val egl: HeadlessEglDisplay, val report: ScenarioReport)

/**
 * A fresh ES3 context + render target for one scenario (like Android recreating the EGL context),
 * with per-frame glFinish (stands in for eglSwapBuffers) and GL error draining.
 */
class GlSession(private val env: ScenarioEnv, debug: Boolean = env.opts.glDebug) : AutoCloseable {
    val opts = env.opts
    private val context = env.egl.createContext()
    val target = RenderTarget(opts.width, opts.height, opts.msaa)
    val gl = HeadlessGl(target.screenFbo, debug)
    private var errorsLogged = 0

    init {
        if (!printedInfo) {
            printedInfo = true
            println("GL_RENDERER: ${gl.getString(GL.RENDERER)}")
            println("GL_VERSION:  ${gl.getString(GL.VERSION)}")
            println("GL_VENDOR:   ${gl.getString(GL.VENDOR)}")
            println(
                "Context: ES3, ${if (context.surfaceless) "surfaceless (EGL_KHR_surfaceless_context)" else "1x1 pbuffer"}, " +
                    "render FBO ${target.width}x${target.height} RGBA8+DEPTH24" + if (opts.msaa > 0) ", ${opts.msaa}x MSAA" else "",
            )
        }
    }

    /** End of frame: wait for the rasterizer (like a swap) and collect GL errors. Allocation free unless an error occurs. */
    fun endFrame(frame: Long) {
        GLES20.glFinish()
        drainErrors(frame)
    }

    fun drainErrors(frame: Long) {
        var n = 0
        while (n < 16) {
            val e = GLES20.glGetError()
            if (e == GLES20.GL_NO_ERROR) break
            n++
            env.report.glErrors++
            if (errorsLogged < 10) {
                errorsLogged++
                env.report.note("GL ERROR ${glErrorName(e)} during frame $frame (rerun with --gl-debug to find the call)")
            }
        }
    }

    fun screenshot(name: String, cutout: Float = 0f): File {
        val px = target.readPixels()
        val img = Screenshot.toImage(px, target.width, target.height, cutout)
        val file = File(opts.outDir, "$name.png")
        Screenshot.write(img, file)
        val stats = Screenshot.stats(img)
        env.report.shots.add(file to stats)
        println("  [shot] ${file.path}: $stats" + if (stats.looksBlank) "  <-- looks blank" else "")
        return file
    }

    override fun close() {
        target.close()
        context.close()
    }

    companion object {
        private var printedInfo = false
    }
}

/** Per-frame step callback with a primitive parameter (no boxing per frame). */
fun interface FrameFn {
    fun step(t: Float)
}

/** Observes the timing/allocation of each frame (used by the perf scenario). */
interface FrameObserver {
    fun beforeDraw()
    fun afterDraw()
    fun afterPresent()
}

/**
 * Scripting API for Game-based scenarios: simulated 60 Hz time, injected pointer events,
 * debug commands and screenshots. Coordinates are surface pixels (origin top-left);
 * use [w]/[h]/[dp] to express them relative to the screen.
 */
class GameDriver(
    val game: Game,
    val gl: Gl,
    /** null when rendering through [NoopGl] (no screenshots). */
    val session: GlSession?,
    val platform: DesktopPlatform,
    val report: ScenarioReport,
    val opts: Options,
    val insets: FloatArray,
) {
    val w = opts.width.toFloat()
    val h = opts.height.toFloat()
    val dp = opts.density
    var frame = 0L
        private set
    var observer: FrameObserver? = null
    private var lastState: String? = null

    /** Warn when a stick/lever touch lands on a control (switch off for touches meant for a control). */
    var warnOnButtons = true

    /** UI unit (px per layout dp) exactly as Game.onSurfaceChanged derives it; keep the two in sync. */
    private val unit = run {
        val d = if (dp > 0f) dp else 1f
        d * min(1f, min(h / d / 380f, w / d / 720f))
    }

    /**
     * The in-flight touch layout, computed by core's own [TouchFlightControls] with the game's
     * settings and this surface, so scripted touches can be checked against the control rects
     * (top bar, strafe cross, BOOST, FA, look strip) instead of trusting hard-coded fractions.
     */
    val touchLayout = TouchFlightControls(
        game.settings,
        object : UiHost {
            override fun haptic(type: Int) {}
        },
    ).also { it.layout(w, h, unit, insets[0], insets[1], insets[2], insets[3]) }

    /** Name of the flight control under (x, y), including the controls' touch slop, or null. */
    fun flightButtonAt(x: Float, y: Float): String? {
        val c = touchLayout
        val slop = 8f * unit
        fun Rect.hit() = contains(x, y, slop)
        fun Rect.hitCircle(s: Float): Boolean {
            val dx = x - cx
            val dy = y - cy
            val r = w * 0.5f + s
            return dx * dx + dy * dy <= r * r
        }
        return when {
            c.pauseBtn.hit() -> "pause"
            c.camBtn.hit() -> "VIEW"
            c.lockBtn.hit() -> "LOCK"
            c.mapBtn.hit() -> "MAP"
            c.upBtn.hitCircle(slop * 0.5f) -> "thrust up"
            c.downBtn.hitCircle(slop * 0.5f) -> "thrust down"
            c.leftBtn.hitCircle(slop * 0.5f) -> "thrust left"
            c.rightBtn.hitCircle(slop * 0.5f) -> "thrust right"
            c.boostBtn.hitCircle(slop) -> "BOOST"
            c.faBtn.hitCircle(slop) -> "FA"
            c.lookStrip.contains(x, y, 0f) -> "look strip"
            else -> null
        }
    }

    /** A field of [Game.debugInfo] ("name=value"), or null. */
    fun field(name: String): String? = Regex("(?:^| )$name=([^ ]+)").find(game.debugInfo)?.groupValues?.get(1)

    /** A numeric field of [Game.debugInfo]; fails when it is missing. */
    fun num(name: String): Double = field(name)?.toDouble() ?: throw IllegalStateException("no $name in ${game.debugInfo}")

    /** A vector field "x,y,z" of [Game.debugInfo]. */
    fun vec(name: String): DoubleArray = (field(name) ?: throw IllegalStateException("no $name in ${game.debugInfo}"))
        .split(',').map { it.toDouble() }.toDoubleArray()

    fun start() {
        game.onSurfaceCreated(gl)
        game.onSurfaceChanged(opts.width, opts.height, opts.density, insets[0], insets[1], insets[2], insets[3])
        lastState = game.stateName
        log("start: ${opts.width}x${opts.height} @ ${opts.density} px/dp, insets ${insets.joinToString(",") { it.toInt().toString() }}, state ${game.stateName}")
    }

    /** Renders one frame at simulated time frame * 16.67 ms. */
    fun frame() {
        frame++
        val o = observer
        o?.beforeDraw()
        game.drawFrame(frame * FRAME_NANOS)
        o?.afterDraw()
        session?.endFrame(frame)
        o?.afterPresent()
        val s = game.stateName
        if (s != lastState) {
            if (!platform.quiet) log("state ${lastState} -> $s")
            lastState = s
        }
    }

    fun frames(n: Int) {
        for (i in 0 until n) frame()
    }

    /** Runs [n] frames; before each one [fn] gets t = 1/n .. 1 (e.g. to move a pointer). */
    fun animate(n: Int, fn: FrameFn) {
        for (i in 1..n) {
            fn.step(i.toFloat() / n)
            frame()
        }
    }

    fun cmd(command: String) {
        log("debugCommand(\"$command\")")
        game.debugCommand(command)
    }

    fun back() {
        log("requestBack()")
        game.requestBack()
    }

    /** Saves a screenshot and returns its content statistics (null without a GL session). */
    fun shot(name: String): ImageStats? {
        val s = session ?: return null
        log("screenshot $name (state ${game.stateName})")
        s.screenshot(name, if (insets[0] > 0f) insets[0] else 0f)
        return report.shots.last().second
    }

    fun pointer(id: Int) = Pointer(id)

    fun log(msg: String) {
        println("  [f%4d] %s".format(frame, msg))
    }

    inner class Pointer(val id: Int) {
        var x = 0f
            private set
        var y = 0f
            private set
        var isDown = false
            private set

        fun down(x: Float, y: Float): Pointer {
            if (game.stateName == "flight" && warnOnButtons) {
                flightButtonAt(x, y)?.let {
                    report.note("WARNING: pointer $id down at (%.0f, %.0f) lands on the $it control; the script meant a stick/throttle touch".format(x, y))
                }
            }
            this.x = x; this.y = y; isDown = true
            game.input.push(InputQueue.DOWN, id, x, y)
            return this
        }

        fun move(x: Float, y: Float) {
            this.x = x; this.y = y
            game.input.push(InputQueue.MOVE, id, x, y)
        }

        fun up() {
            isDown = false
            game.input.push(InputQueue.UP, id, x, y)
        }

        /** Moves linearly from the current position to (tx, ty) over [n] frames. */
        fun dragTo(tx: Float, ty: Float, n: Int) {
            val sx = x
            val sy = y
            animate(n) { t -> move(sx + (tx - sx) * t, sy + (ty - sy) * t) }
        }

        /** Touch down, drag, release. */
        fun swipe(fx: Float, fy: Float, tx: Float, ty: Float, n: Int) {
            down(fx, fy)
            dragTo(tx, ty, n)
            up()
        }

        fun tap(x: Float, y: Float) {
            down(x, y)
            frame()
            up()
        }
    }
}
