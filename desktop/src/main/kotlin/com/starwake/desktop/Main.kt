package com.starwake.desktop

import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Untitled Space Exploration headless preview harness (Mesa EGL surfaceless + llvmpipe)

Usage: ./gradlew :desktop:run --args="[options]"

  --scenario <a,b,...>   scenarios to run (default smoke); "all" runs every scenario
  --list                 list scenarios and exit
  --out <dir>            screenshot directory (default desktop/out)
  --assets <dir>         asset directory (default app/src/main/assets)
  --size WxH             surface size in pixels (default 2400x1080, 20:9 landscape)
  --density <px/dp>      surface pixels per dp (default 2.75)
  --insets l,t,r,b       safe insets in pixels (default 0,0,0,0)
  --cutout [px]          shorthand for a punch-hole inset on the left (default 90 px); drawn into PNGs
  --msaa <samples>       render into an N x MSAA FBO resolved before readback (default 0 = off)
  --settings <string>    initial persisted settings, e.g. "q=1;showfps=1"
  --perf-frames <n>      measured frames for the perf scenario (default 600)
  --gl-debug             glGetError after every GL call; throws naming the call

Run from the repository root (the Gradle tasks do). Allocation numbers need -XX:-DoEscapeAnalysis:
  ./gradlew :desktop:perf      or     ./gradlew :desktop:run -Pnoea --args="--scenario perf"
"""

fun parseOptions(args: Array<String>): Options? {
    var out = File("desktop/out")
    var assets = File("app/src/main/assets")
    var width = 2400
    var height = 1080
    var density = 2.75f
    var scenarios = listOf("smoke")
    var glDebug = false
    var msaa = 0
    var insets = floatArrayOf(0f, 0f, 0f, 0f)
    var perfFrames = 600
    var settings: String? = null
    var i = 0
    fun value(name: String): String {
        if (i + 1 >= args.size) throw IllegalArgumentException("$name needs a value")
        return args[++i]
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--out" -> out = File(value(a))
            "--assets" -> assets = File(value(a))
            "--size" -> {
                val parts = value(a).lowercase().split('x')
                require(parts.size == 2) { "--size expects WxH" }
                width = parts[0].toInt(); height = parts[1].toInt()
            }
            "--density" -> density = value(a).toFloat()
            "--scenario", "--scenarios" -> scenarios = value(a).split(',').map { it.trim() }.filter { it.isNotEmpty() }
            "--gl-debug" -> glDebug = true
            "--msaa" -> msaa = value(a).toInt()
            "--insets" -> {
                val p = value(a).split(',').map { it.trim().toFloat() }
                require(p.size == 4) { "--insets expects l,t,r,b" }
                insets = p.toFloatArray()
            }
            "--cutout" -> {
                val px = args.getOrNull(i + 1)?.toFloatOrNull()
                if (px != null) i++
                insets = floatArrayOf(px ?: 90f, 0f, 0f, 0f)
            }
            "--settings" -> settings = value(a)
            "--perf-frames" -> perfFrames = value(a).toInt()
            "--list" -> {
                for (s in Scenarios.registry.values) println("  %-12s %s".format(s.name, s.description))
                println("  %-12s %s".format("all", "every scenario above, in order"))
                return null
            }
            "-h", "--help" -> {
                print(USAGE)
                return null
            }
            else -> throw IllegalArgumentException("unknown option $a")
        }
        i++
    }
    return Options(out, assets, width, height, density, scenarios, glDebug, msaa, insets, perfFrames, settings)
}

fun main(args: Array<String>) {
    val opts = try {
        parseOptions(args) ?: return
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}\n")
        System.err.print(USAGE)
        exitProcess(2)
    }
    val names = opts.scenarios.flatMap { if (it == "all") Scenarios.registry.keys.toList() else listOf(it) }.distinct()
    val unknown = names.filter { it !in Scenarios.registry }
    if (unknown.isNotEmpty()) {
        System.err.println("unknown scenario(s): ${unknown.joinToString()}; available: ${Scenarios.registry.keys.joinToString()}, all")
        exitProcess(2)
    }
    if (!File(opts.assetsDir, "font/ui.fnt").isFile) {
        System.err.println("assets not found in ${opts.assetsDir.absolutePath} (run from the repo root or pass --assets)")
        exitProcess(2)
    }
    opts.outDir.mkdirs()

    println("Untitled Space Exploration preview: ${opts.width}x${opts.height} @ ${opts.density} px/dp, scenarios ${names.joinToString()}, out ${opts.outDir.absolutePath}")
    val reports = ArrayList<ScenarioReport>()
    HeadlessEglDisplay().use { egl ->
        println(egl.describe())
        for (name in names) {
            val scenario = Scenarios.registry.getValue(name)
            val report = ScenarioReport(name)
            reports.add(report)
            println("\n=== $name: ${scenario.description}")
            val t0 = System.nanoTime()
            try {
                scenario.body(ScenarioEnv(opts, egl, report))
            } catch (t: Throwable) {
                report.failure = t
                System.err.println("  FAILED: ${t.javaClass.simpleName}: ${t.message}")
                t.printStackTrace()
            }
            report.millis = (System.nanoTime() - t0) / 1_000_000
        }
    }

    println("\n=== summary")
    for (r in reports) {
        val status = when {
            r.failure != null -> "FAILED (${r.failure!!.javaClass.simpleName}: ${r.failure!!.message?.lineSequence()?.firstOrNull()})"
            r.glErrors > 0 -> "OK with ${r.glErrors} GL errors"
            else -> "OK"
        }
        println("%-12s %-8s %6.1f s  %s".format(r.name, if (r.failure == null) "ok" else "FAIL", r.millis / 1000.0, status))
        for ((f, s) in r.shots) println("    ${f.path}  ($s)" + if (s.looksBlank) "  <-- looks blank" else "")
    }
    val failed = reports.count { it.failure != null }
    exitProcess(if (failed > 0) 1 else 0)
}
