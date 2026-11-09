package com.starwake.desktop

import com.starwake.core.Game

/** A named, runnable check. Add new ones to [Scenarios.registry]; "all" runs them in order. */
class Scenario(val name: String, val description: String, val body: (ScenarioEnv) -> Unit)

/**
 * Builds a scenario that drives a real [Game] on a fresh GL context. [insets] overrides the
 * --insets/--cutout option (left, top, right, bottom px).
 */
fun gameScenario(
    name: String,
    description: String,
    insets: FloatArray? = null,
    script: GameDriver.() -> Unit,
) = Scenario(name, description) { env ->
    GlSession(env).use { session ->
        val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
        val game = Game(platform)
        val driver = GameDriver(game, session.gl, session, platform, env.report, env.opts, insets ?: env.opts.insets)
        driver.start()
        driver.script()
        val hap = platform.haptics
        env.report.note(
            "end: state ${game.stateName}, frame ${driver.frame}, last drawFrame CPU %.2f ms, haptics TAP=%d TICK=%d BUMP=%d HEAVY=%d, activePlay %b (%d changes)"
                .format(game.lastFrameCpuNanos / 1e6, hap[0], hap[1], hap[2], hap[3], platform.activePlay, platform.activePlayChanges),
        )
    }
}

object Scenarios {
    /** Left thumb on the throttle lever: drag up by [amount] of the screen height and let go. */
    fun GameDriver.throttleUp(amount: Float = 0.35f, frames: Int = 30) {
        pointer(0).swipe(0.15f * w, 0.75f * h, 0.15f * w, (0.75f - amount) * h, frames)
    }

    val registry: LinkedHashMap<String, Scenario> = linkedMapOf(
        "smoke" to Scenario(
            "smoke",
            "Engine renderers without Game: every shader, scene (ship, sky, planet, stars, dust) + UI primitives/text",
        ) { Smoke.run(it) },

        "bodies" to Scenario(
            "bodies",
            "Real-scale BodyRenderer without Game: gas giant from home, PLANET II from orbit and 20 km, the star, dots, all surface types",
        ) { Bodies.run(it) },

        "stations" to Scenario(
            "stations",
            "Every StationMesh model through the lit shader + light glows, from several angles (no Game)",
        ) { StationsScenario.run(it) },

        "station-collision" to Scenario(
            "station-collision",
            "Station collision proxies (CPU-traced, overlaid on the mesh) and routed auto-dock paths around both station models (no Game)",
        ) { CollisionScenario.run(it) },

        "menu" to gameScenario("menu", "Main menu after 2 s") {
            frames(120)
            shot("menu")
        },

        "menu-cutout" to gameScenario(
            "menu-cutout",
            "Main menu with a 90 px punch-hole cutout inset on the left (marker drawn into the PNG)",
            insets = floatArrayOf(90f, 0f, 0f, 0f),
        ) {
            frames(120)
            shot("menu-cutout")
        },

        "settings" to gameScenario("settings", "Settings overlay over the menu") {
            frames(10)
            cmd("settings")
            frames(30)
            shot("settings")
        },

        "flight" to gameScenario("flight", "Launch, throttle up with the left thumb, steer with the right stick") {
            cmd("launch")
            frames(60)
            shot("flight-1") // just launched (the game starts flights at 30% throttle)

            throttleUp(0.35f, 30) // left thumb: lever up by 0.35 h, released
            frames(30)
            shot("flight-2") // accelerating

            // right thumb: floating stick, ~40 px towards the upper right, then held
            val right = pointer(1).down(0.75f * w, 0.65f * h)
            right.dragTo(0.75f * w + 28f, 0.65f * h - 28f, 20)
            shot("flight-3") // stick just deflected
            frames(60)
            shot("flight-4") // after holding the stick for 1 s
            right.up()
            frames(60)
            shot("flight-5") // stick released, cruising
        },

        "system" to gameScenario("system", "Star system milestone: spawn, map, landing, docking") {
            cmd("launch")
            frames(90)
            shot("sys-spawn")
            cmd("map")
            frames(20)
            shot("sys-map")
            cmd("resume")
            cmd("teleport-low")
            frames(40)
            shot("sys-lowalt")
            cmd("action") // LAND -> landing mode, gear sequence
            frames(150)
            shot("sys-landing")
            cmd("menu")
            frames(5)
            cmd("launch")
            frames(5)
            cmd("dock")
            frames(240)
            shot("sys-docking")
            cmd("docked")
            frames(200)
            shot("sys-docked")
        },

        "gameplay" to gameScenario("gameplay", "Checks: land, touch down, take off; Back twice while docked; boost during auto-dock; off-screen lock arrow") {
            fun info() = game.debugInfo.also { log(it) }
            fun field(name: String) = Regex("$name=([^ ]+)").find(game.debugInfo)!!.groupValues[1]
            cmd("launch")
            frames(30)
            // off-screen lock: OUTPOST II is far away and not in view at spawn -> edge arrow
            cmd("lock-outpost")
            frames(10)
            shot("gp-lock-arrow")
            cmd("lock-outpost")
            // --- landing: 60 m above PLANET II, LAND, hold the down thruster until touchdown
            cmd("teleport-surface")
            frames(2)
            cmd("action")
            frames(140) // gear legs + doors
            check(field("landing") == "true") { "landing mode not engaged: ${info()}" }
            val down = touchLayout.downBtn
            val p = pointer(3).down(down.cx, down.y + down.h * 0.5f)
            var n = 0
            while (field("landed") != "true" && n < 60 * 20) { frame(); n++ }
            p.up()
            info()
            check(field("landed") == "true") { "never touched down: ${info()}" }
            frames(30)
            shot("gp-landed")
            // --- take off with the action button: must leave the ground and stay up
            cmd("action")
            frames(90)
            info()
            check(field("landed") == "false") { "re-landed right after take-off: ${info()}" }
            check(field("alt").toFloat() > 20f) { "take-off did not climb: ${info()}" }
            shot("gp-liftoff")
            // --- docked: Back twice (pause, then resume) must return to the docked screen
            cmd("menu"); frames(5); cmd("launch"); frames(5)
            cmd("docked")
            frames(30)
            cmd("boost") // pressed during the slide-in: must not latch
            frames(400)
            check(game.stateName == "docked") { "not docked: ${info()}" }
            back(); frames(5)
            check(game.stateName == "paused") { "back did not pause: ${info()}" }
            back(); frames(5)
            check(game.stateName == "docked") { "resume left the docked screen: ${info()}" }
            game.launchFromStation()
            frames(10)
            info()
            check(field("boosting") == "false") { "latched boost fired on launch: ${info()}" }
            shot("gp-launched")
        },

        "boost" to gameScenario("boost", "Flight with throttle up, then boost") {
            cmd("launch")
            frames(60)
            throttleUp(0.30f, 20)
            frames(30)
            cmd("boost")
            frames(20)
            shot("boost")
        },

        "pause" to gameScenario("pause", "Pause overlay over flight (dimmed HUD)") {
            cmd("launch")
            frames(30)
            throttleUp(0.25f, 15)
            frames(30)
            cmd("pause")
            frames(30)
            shot("pause")
        },

        "cameras" to gameScenario("cameras", "The three flight cameras (chase, far chase, cockpit) while turning") {
            cmd("launch")
            frames(30)
            throttleUp(0.30f, 20)
            val right = pointer(1).down(0.75f * w, 0.65f * h)
            right.dragTo(0.75f * w - 30f, 0.65f * h - 20f, 15)
            frames(30)
            shot("camera-chase")
            cmd("camera")
            frames(30)
            shot("camera-far")
            cmd("camera")
            frames(30)
            shot("camera-cockpit")
            right.up()
            cmd("camera")
            frames(2)
        },

        "perf" to Scenario(
            "perf",
            "Flight with continuous two-thumb input (--perf-frames, default 600): CPU ms per drawFrame and bytes allocated per frame",
        ) { Perf.run(it) },
    )
}
