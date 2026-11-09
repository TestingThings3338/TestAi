package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.content.ShipCatalog
import com.starwake.core.game.StarSystem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Harness scenarios of the session port (01-core-loop.md 9.5): the frame order, the start curtain
 * and reveal, the docked start, the camera views and the non-pausing map. Each check fails the
 * scenario with the debug read-out; the shots go to the output directory.
 */
object SessionScenarios {
    /** The documented flight-frame order (01 9.2): the Roblox C-list steps the port keeps. */
    const val FLIGHT_ORDER =
        "F0 C1 C2 C3 C4 C5 C6 C7 C8 C9 C10 C11 C12 C13 C14 C15 C16 C19 C20 C21 C22 C23 C24 C25 C26 C27 C28 " +
            "C30 C31 C34 C35 C36 C37 C38 C39 C40 C41 C43 C44 C45 C46 C47 C48 C52 C53 C54 C56 C58 C59 C60 " +
            "C62 C63 C64 C65 C66 C67 C68 C69 C70 C71 C72 C73 C74 C75 C76"
    const val DECK_ORDER = "F0 A2 A3 A4 A5 A6 A7 A9 A10 A11 A12 A13 A14"
    const val DECK_ENDS_ORDER = "F0 A2 A3 A4"
    const val DESTRUCTION_ORDER = "F0 B1 B2 B3 B4 B6 B9 B10"

    private fun run(env: ScenarioEnv, script: GameDriver.() -> Unit) {
        GlSession(env).use { session ->
            val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
            val game = Game(platform)
            val d = GameDriver(game, session.gl, session, platform, env.report, env.opts, env.opts.insets)
            d.start()
            d.script()
            env.report.note("end: state ${game.stateName}, frame ${d.frame}, last drawFrame CPU %.2f ms".format(game.lastFrameCpuNanos / 1e6))
        }
    }

    private fun GameDriver.fail(msg: String): Nothing = throw IllegalStateException("$msg\n  ${game.debugInfo}")

    // ------------------------------------------------------------------ frame-order

    fun frameOrder(env: ScenarioEnv) = run(env) {
        fun traced(label: String, expected: String) {
            cmd("trace")
            frame()
            val got = game.frameTrace
            log("$label: $got")
            if (got != expected) fail("$label: frame order differs\n  expected $expected\n  got      $got")
            report.note("$label: ${got.split(' ').size} steps in the documented order")
        }
        cmd("launch space")
        frames(10)
        traced("free flight", FLIGHT_ORDER)
        // under the map the flight frame runs in full (DECISIONS 21)
        cmd("map")
        frames(2)
        if (game.stateName != "map") fail("the map did not open")
        traced("under the map", FLIGHT_ORDER)
        shot("frame-order-map")
        back()
        frames(2)
        // the docked start: controlled, the services open, the frame unchanged
        cmd("menu")
        frames(2)
        cmd("launch")
        frames(3)
        if (game.stateName != "docked") fail("launch did not start docked")
        traced("docked, services open", FLIGHT_ORDER)
        // branch A: the deck; the frame it ends returns early; then the flight again
        cmd("branch deck")
        traced("deck branch", DECK_ORDER)
        cmd("branch flight")
        traced("deck ends", DECK_ENDS_ORDER)
        traced("flight after the deck", FLIGHT_ORDER)
        // branch B: the cinematic, until it ends
        cmd("branch destroyed 0.2")
        traced("destruction branch", DESTRUCTION_ORDER)
        frames(15)
        traced("flight after the cinematic", FLIGHT_ORDER)
        shot("frame-order")
    }

    // ------------------------------------------------------------------ boot-reveal

    fun bootReveal(env: ScenarioEnv) = run(env) {
        frames(100) // the menu has faded in (1.4 s)
        shot("boot-menu")
        cmd("launch fade")
        // the fade to black: 0.9 s sine in, then LOADING after 1 s
        var fadeFrames = 0
        var midFadeShot = false
        while (game.stateName != "loading") {
            frame()
            fadeFrames++
            if (!midFadeShot && num("curtain") > 0.4) {
                shot("boot-1-fade")
                midFadeShot = true
            }
            if (fadeFrames > 120) fail("LOADING never began")
        }
        log("fade to black took $fadeFrames frames")
        if (abs(fadeFrames - 60) > 2) fail("the launch fade should take 1 s (60 frames), took $fadeFrames")
        // the build frame: the world was built in this frame, under the curtain
        val b = game.frameCount
        if (game.buildFrame != b) fail("expected the build in frame $b, got ${game.buildFrame}")
        val curtain = ArrayList<Double>()
        val states = ArrayList<String>()
        fun record() {
            curtain += num("curtain")
            states += game.stateName
        }
        record()
        val s0 = shot("boot-2-build")!!
        frame(); record()
        val s1 = shot("boot-3-build+1")!!
        frame(); record()
        val s2 = shot("boot-4-build+2")!!
        for ((i, st) in listOf(s0, s1, s2).withIndex()) {
            if (curtain[i] != 1.0) fail("frame B+$i: curtain ${curtain[i]}, expected 1 (black until two frames after the build)")
            if (st.meanLuma > 0.001) fail("frame B+$i is not black: $st")
        }
        // B+3: the reveal's first step; then 0.6 s sine out
        frame(); record()
        val first = 1.0 - sin(Math.PI / 2 * (1.0 / 60) / 0.6)
        if (abs(curtain[3] - first) > 0.005) fail("frame B+3: curtain ${curtain[3]}, expected the first reveal step $first")
        while (curtain.size < 3 + 18) { frame(); record() } // B+20: 0.3 s into the reveal
        shot("boot-5-reveal+0.3s")
        val mid = 1.0 - sin(Math.PI / 4)
        if (abs(curtain[20] - mid) > 0.01) fail("0.3 s into the reveal: curtain ${curtain[20]}, expected $mid")
        while (curtain.size < 3 + 36) { frame(); record() } // B+38: 0.6 s
        shot("boot-6-reveal+0.6s")
        if (curtain[38] != 0.0) fail("0.6 s into the reveal: curtain ${curtain[38]}, expected 0")
        for (i in 4 until curtain.size) if (curtain[i] > curtain[i - 1]) fail("the curtain went up again at B+$i")
        while (curtain.size < 3 + 156) { frame(); record() } // 2.6 s: the cockpit boot is over
        shot("boot-7-reveal+2.6s")
        // the services of the docked start appear with the reveal, not under the curtain
        val firstDocked = states.indexOf("docked")
        if (firstDocked != 3) fail("the services showed at B+$firstDocked, expected B+3 (the reveal): $states")
        if (field("safety") != "false") fail("the reveal came from the safety net")
        report.note("curtain B..B+4: ${curtain.take(5).joinToString { "%.3f".format(it) }}; B+20 %.3f; B+38 %.3f".format(curtain[20], curtain[38]))
    }

    // ------------------------------------------------------------------ start-docked

    fun startDocked(env: ScenarioEnv) = run(env) {
        frames(100)
        // the real LAUNCH button of the main menu (MainMenu.layout)
        val u = unitPx()
        val lx = insets[0] + maxOf(28f * u, 40f * u) + 118f * u
        val ly = h * 0.42f + 23f * u
        pointer(0).tap(lx, ly)
        var n = 0
        var phaseAtDocked = ""
        while (game.stateName != "docked") {
            frame()
            n++
            if (n > 60 * 5) fail("never saw the docked services after LAUNCH")
        }
        phaseAtDocked = field("phase")!!
        log("services after $n frames, in phase $phaseAtDocked")
        if (phaseAtDocked != "REVEAL") fail("the services must open with the reveal, came in $phaseAtDocked")
        frames(60)
        if (field("docked") != "true" || field("pstate") != "DOCKED") fail("not docked")
        if (field("station") != "0") fail("not at the home station")
        if (num("speed") != 0.0) fail("a docked ship is at rest")
        val gap = num("hull")
        if (gap <= 0.0) fail("parked inside the station's walls (gap $gap m)")
        report.note("docked at HOME STATION, hull gap %.1f m to the slot walls".format(gap))
        shot("start-docked")
        // the world runs behind the services (they do not pause): the session clock advances
        val f0 = num("frame")
        frames(30)
        if (game.stateName != "docked") fail("the services closed by themselves")
        log("30 frames later at frame ${num("frame") - f0 + f0}")
        // LAUNCH on the services (DockedScreen.layout)
        val bx = insets[0] + 144f * u
        val by = h - insets[3] - 67f * u
        pointer(1).tap(bx, by)
        frames(30)
        if (game.stateName != "flight") fail("LAUNCH on the services did not fly out")
        if (field("docked") != "false") fail("still docked after LAUNCH")
        if (num("speed") < 10.0) fail("launched without a push")
        shot("start-launched")
    }

    /** The UI unit, as Game.onSurfaceChanged derives it. */
    private fun GameDriver.unitPx(): Float {
        val d = if (dp > 0f) dp else 1f
        return d * min(1f, min(h / d / 380f, w / d / 720f))
    }

    // ------------------------------------------------------------------ camera-views

    fun cameraViews(env: ScenarioEnv) = run(env) {
        val home = StarSystem().home
        cmd("launch space")
        frames(5)
        // at rest at the old spawn, nose -Z (the gas giant ahead): ship space = world space
        cmd("teleport ${home.x} ${home.y} ${home.z} 0 0 -1")
        cmd("throttle 0")
        frames(10)
        fun camNear(x: Double, y: Double, z: Double, eps: Double, what: String) {
            val c = vec("cam")
            val e = sqrt((c[0] - x) * (c[0] - x) + (c[1] - y) * (c[1] - y) + (c[2] - z) * (c[2] - z))
            if (e > eps) fail("$what: camera at (${c.joinToString()}), expected ($x, $y, $z)")
        }
        // the cockpit eye for look (yaw, pitch): seat + neck - turn·neck (01 2.3)
        fun eye(yaw: Double, pitch: Double): DoubleArray {
            val ny = -0.35
            val nz = 0.3
            // turn = RotY(-yaw) · RotX(pitch) applied to the neck (0, ny, nz)
            val p = Math.toRadians(pitch)
            val y1 = ny * cos(p) - nz * sin(p)
            val z1 = ny * sin(p) + nz * cos(p)
            val t = Math.toRadians(-yaw)
            val x2 = z1 * sin(t)
            val z2 = z1 * cos(t)
            return doubleArrayOf(0.0 - x2, 0.5 + ny - y1, nz - z2)
        }
        if (field("view") != "COCKPIT") fail("the cockpit is the default view")
        camNear(0.0, 0.5, 0.0, 0.01, "cockpit")
        shot("view-cockpit")
        for ((name, look) in listOf("right" to (140.0 to 0.0), "left" to (-140.0 to 0.0), "up" to (0.0 to 80.0), "down" to (0.0 to -70.0))) {
            cmd("look ${look.first} ${look.second}")
            frames(2)
            val e = eye(look.first, look.second)
            camNear(e[0], e[1], e[2], 0.01, "look $name")
            shot("view-cockpit-look-$name")
        }
        // beyond the limits: clamped to 140° / 80°
        cmd("look 170 95")
        frames(2)
        if (field("look") != "140.0,80.0") fail("the look is not clamped to 140/80: ${field("look")}")
        // let go: the view returns forward (tau 0.12 s)
        cmd("look off")
        frames(60)
        if (field("look") != "0.0,0.0") fail("the look did not return: ${field("look")}")
        for (fov in listOf(60, 100)) {
            cmd("fov $fov")
            frames(2)
            if (abs(num("fov") - fov) > 0.05) fail("FOV ${num("fov")}, expected $fov")
            shot("view-cockpit-fov$fov")
        }
        cmd("fov 80")
        // the Roblox external view of every ship: rigid, at the ship's own offset and pitch
        for (e in ShipCatalog.all) {
            cmd("ship ${e.id}")
            cmd("view external")
            frames(3)
            if (field("ship") != e.id) fail("ship ${e.id} not selected")
            camNear(e.cameraX.toDouble(), e.cameraY.toDouble(), e.cameraZ.toDouble(), 0.05, "${e.name} external")
            shot("view-external-${e.id}")
        }
        cmd("ship varrow")
        cmd("view external")
        cmd("look 180 0")
        frames(3)
        camNear(0.0, 16.0, -95.0, 0.05, "external look 180°")
        shot("view-external-look-back")
        cmd("look off")
        // the optional chase view (behind the setting; "view chase" switches it on)
        cmd("view chase")
        frames(30)
        if (field("view") != "CHASE") fail("no chase view")
        shot("view-chase")
        cmd("view cockpit")
        frames(2)
    }

    // ------------------------------------------------------------------ map-nonpausing

    fun mapNonPausing(env: ScenarioEnv) = run(env) {
        cmd("launch space")
        frames(5)
        cmd("throttle 0.5")
        frames(120)
        val v = num("speed")
        log("cruising at %.1f m/s".format(v))
        // the right thumb holds the stick deflected, then another finger opens the map
        val right = pointer(1).down(0.75f * w, 0.65f * h)
        right.dragTo(0.75f * w + 60f, 0.65f * h, 10)
        if (vec("aim")[0] <= 0.1) fail("the stick is not deflected: aim ${field("aim")}")
        warnOnButtons = false
        val map = touchLayout.mapBtn
        pointer(2).tap(map.cx, map.cy)
        warnOnButtons = true
        frames(2)
        if (game.stateName != "map") fail("the MAP button did not open the map")
        if (field("aim") != "0.00,0.00") fail("the stick was not released under the map: aim ${field("aim")}")
        val p0 = vec("pos")
        frames(180)
        val p1 = vec("pos")
        val moved = sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1]) + (p1[2] - p0[2]) * (p1[2] - p0[2]))
        report.note("under the map for 3 s: moved %.1f m at %.1f m/s (%.1f %% of v·3 s)".format(moved, v, moved / (v * 3.0) * 100.0))
        if (abs(moved - v * 3.0) > 0.1 * v * 3.0) fail("the flight did not keep running under the map: moved $moved m, expected ~${v * 3.0}")
        shot("map-flying")
        right.up()
        back()
        frames(2)
        if (game.stateName != "flight") fail("Back did not close the map")
        if (field("aim") != "0.00,0.00") fail("the stick did not come back centred: aim ${field("aim")}")
        shot("map-closed")
        // PAUSE freezes the simulation
        cmd("pause")
        frames(2)
        val q0 = vec("pos")
        frames(60)
        val q1 = vec("pos")
        if (q0[0] != q1[0] || q0[1] != q1[1] || q0[2] != q1[2]) fail("the ship moved under PAUSE")
        cmd("resume")
        frames(2)
        if (game.stateName != "flight") fail("resume did not return to the flight")
    }
}
