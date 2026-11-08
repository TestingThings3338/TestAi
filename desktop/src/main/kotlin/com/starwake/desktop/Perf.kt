package com.starwake.desktop

import com.starwake.core.Game
import java.lang.management.ManagementFactory
import java.util.function.IntSupplier
import kotlin.math.cos
import kotlin.math.sin

/**
 * Per-frame timing and allocation recorder. All storage is preallocated; [beforeDraw]/[afterDraw]
 * bracket only Game.drawFrame, so harness work (input injection, glFinish) is not attributed to
 * the engine.
 */
class PerfMeter(capacity: Int, private val drawCounter: IntSupplier?) : FrameObserver {
    private val mx = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val drawNanos = LongArray(capacity)
    val presentNanos = LongArray(capacity)
    val allocBytes = LongArray(capacity)
    val gameCpuNanos = LongArray(capacity)
    val draws = IntArray(capacity)
    var count = 0
        private set
    var game: Game? = null
    private var t0 = 0L
    private var t1 = 0L
    private var a0 = 0L
    private var a1 = 0L

    /** Bytes the allocation counter itself reports between two back-to-back reads. */
    val overhead: Long

    init {
        if (!mx.isThreadAllocatedMemoryEnabled) mx.isThreadAllocatedMemoryEnabled = true
        var o = Long.MAX_VALUE
        for (i in 0 until 50) {
            val x = mx.currentThreadAllocatedBytes
            val y = mx.currentThreadAllocatedBytes
            o = minOf(o, y - x)
        }
        overhead = o
    }

    /** Proves the counter sees heap allocations: returns what it reports for a 128-long array (~1040 B). */
    fun selfTest(): Long {
        val a = mx.currentThreadAllocatedBytes
        sink = LongArray(128)
        val b = mx.currentThreadAllocatedBytes
        return b - a - overhead
    }

    override fun beforeDraw() {
        a0 = mx.currentThreadAllocatedBytes
        t0 = System.nanoTime()
    }

    override fun afterDraw() {
        t1 = System.nanoTime()
        a1 = mx.currentThreadAllocatedBytes
    }

    override fun afterPresent() {
        val t2 = System.nanoTime()
        val i = count
        if (i >= drawNanos.size) return
        drawNanos[i] = t1 - t0
        presentNanos[i] = t2 - t1
        allocBytes[i] = a1 - a0 - overhead
        gameCpuNanos[i] = game?.lastFrameCpuNanos ?: 0L
        draws[i] = drawCounter?.asInt ?: 0
        count = i + 1
    }

    fun summary(label: String, from: Int, r: ScenarioReport) {
        val n = count - from
        if (n <= 0) return
        fun stats(a: LongArray): String {
            val s = a.copyOfRange(from, count).sorted()
            val avg = s.sum() / n.toDouble() / 1e6
            val p95 = s[((n - 1) * 0.95).toInt()] / 1e6
            return "avg %6.3f  p50 %6.3f  p95 %6.3f  max %6.3f ms".format(avg, s[n / 2] / 1e6, p95, s.last() / 1e6)
        }
        r.note("[$label] frames $from..${count - 1} ($n):")
        r.note("    drawFrame wall     : ${stats(drawNanos)}")
        r.note("    game.lastFrameCpu  : ${stats(gameCpuNanos)}")
        if (presentNanos.copyOfRange(from, count).any { it > 50_000 }) r.note("    glFinish (raster)  : ${stats(presentNanos)}")
        val alloc = allocBytes.copyOfRange(from, count)
        val total = alloc.sum()
        val allocating = alloc.count { it > 0 }
        r.note(
            "    allocated on game thread: %.1f bytes/frame (total %d B, %d of %d frames allocated, max %d B in one frame)"
                .format(total / n.toDouble(), total, allocating, n, alloc.maxOrNull() ?: 0),
        )
        val where = (from until count).filter { allocBytes[it] > 0 }.take(8).joinToString { "#$it: ${allocBytes[it]} B" }
        if (where.isNotEmpty()) r.note("    allocating frames  : $where")
        if (from > 0) {
            val warm = allocBytes.copyOfRange(0, from)
            r.note("    warm-up frames 0..${from - 1}: %d B total, %d frames allocated".format(warm.sum(), warm.count { it > 0 }))
        }
        if (drawCounter != null) {
            val d = draws.copyOfRange(from, count)
            r.note("    draw calls/frame   : min ${d.minOrNull()} max ${d.maxOrNull()} avg %.1f".format(d.average()))
        }
    }
}

/** Keeps the self-test allocation reachable so it cannot be optimised away. */
@Volatile private var sink: Any? = null

object Perf {
    /**
     * Flight with continuous input: left thumb holds the throttle lever and wiggles it (throttle +
     * rudder), right thumb circles the stick. Every frame both pointers send a MOVE.
     */
    fun drive(d: GameDriver, frames: Int, meter: PerfMeter) {
        d.cmd("launch")
        d.frames(30)
        val left = d.pointer(0)
        val right = d.pointer(1)
        val lx = 0.15f * d.w
        val ly0 = 0.80f * d.h
        val lyHeld = ly0 - 0.30f * d.h
        left.down(lx, ly0)
        left.dragTo(lx, lyHeld, 20)
        val cx = 0.72f * d.w
        val cy = 0.62f * d.h
        val radius = 60f
        right.down(cx + radius, cy)
        d.frame()
        d.log("measuring $frames frames (logging muted)")
        d.platform.quiet = true
        d.observer = meter
        for (i in 0 until frames) {
            val a = i * 0.045f
            left.move(lx + sin(i * 0.013f) * 0.02f * d.w, lyHeld + sin(i * 0.021f) * 0.05f * d.h)
            right.move(cx + cos(a) * radius, cy + sin(a) * radius)
            d.frame()
        }
        d.observer = null
        d.platform.quiet = false
        left.up()
        right.up()
        d.frames(5)
    }

    fun run(env: ScenarioEnv) {
        val opts = env.opts
        val r = env.report
        val frames = opts.perfFrames
        val window = minOf(300, frames / 2)
        val from = frames - window
        val ea = ManagementFactory.getRuntimeMXBean().inputArguments
        if ("-XX:-DoEscapeAnalysis" !in ea) {
            r.note("WARNING: running without -XX:-DoEscapeAnalysis; HotSpot may scalar-replace allocations ART would make. Use ./gradlew :desktop:perf")
        } else {
            r.note("escape analysis disabled (-XX:-DoEscapeAnalysis): allocation counts approximate ART")
        }
        val probe = PerfMeter(1, null)
        repeat(3) { probe.selfTest() } // first calls include one-off class/constant resolution
        r.note("allocation counter self-test: LongArray(128) measured as ${probe.selfTest()} B (expected ~1040 B)")

        // 1) real engine on llvmpipe: CPU numbers include the software rasterizer's command processing
        GlSession(env, debug = false).use { s ->
            val platform = DesktopPlatform(opts.assetsDir, opts.settings)
            val game = Game(platform)
            val d = GameDriver(game, s.gl, s, platform, r, opts, opts.insets)
            val meter = PerfMeter(frames) { val n = s.gl.drawCalls; s.gl.resetCounters(); n }
            meter.game = game
            d.start()
            drive(d, frames, meter)
            meter.summary("llvmpipe", from, r)
            d.shot("perf-end")
        }

        // 2) same script on a no-op Gl: the engine's own CPU cost, no driver at all
        for ((label, n) in listOf("no-op GL" to frames, "no-op GL, ${frames * 5} frames (JIT steady state)" to frames * 5)) {
            val noop = NoopGl()
            val platform = DesktopPlatform(opts.assetsDir, opts.settings)
            val game = Game(platform)
            val d = GameDriver(game, noop, null, platform, r, opts, opts.insets)
            val meter = PerfMeter(n) { val c = noop.drawCalls.toInt(); noop.resetCounters(); c }
            meter.game = game
            d.start()
            drive(d, n, meter)
            meter.summary(label, n - window, r)
        }
        r.note("note: llvmpipe numbers include Mesa's CPU-side GL work and are not representative of a phone GPU driver; the no-op GL rows are the engine's own cost (desktop JIT, not ART)")
    }
}
