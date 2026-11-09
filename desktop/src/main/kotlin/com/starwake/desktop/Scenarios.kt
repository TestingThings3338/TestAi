package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.game.Session

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

/**
 * The harness scenarios. A launch starts docked at the home station since the session port
 * (D13: new pilots start docked); scripts that test free flight start with "launch space", the
 * pre-port spawn in front of the home station at 30 % throttle. The default view is the cockpit
 * (DECISIONS 21a); "view external" and "view chase" pick the others.
 */
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
            "Sol at S = 3000 through BodyRenderer without Game: measured angular sizes (Luna 0.53 deg from Earth, Earth 37.5 deg from MERIDIAN, ...), every body from 3.5 radii, the hosts from their stations, Earth's limb and 20 km horizon, Saturn's rings and Cassini division, Uranus's ring at 82 deg, an occluded moon, the bakes",
        ) { Bodies.run(it) },

        "stations-real" to Scenario(
            "stations-real",
            "The 6 Roblox stations + build site (assets/stations): approach, mouth, side, rear, hangar, pads with a parked VARROW, LOD 1/2 and the 60 km marker; site stages as hologram (no Game)",
        ) { RealStationsScenario.run(it) },

        "stations" to Scenario(
            "stations",
            "Every StationMesh model through the lit shader + light glows, from several angles (no Game)",
        ) { StationsScenario.run(it) },

        "station-collision" to Scenario(
            "station-collision",
            "Station collision proxies (CPU-traced, overlaid on the mesh) and routed auto-dock paths around both station models (no Game)",
        ) { CollisionScenario.run(it) },

        "station-depth" to Scenario(
            "station-depth",
            "MERIDIAN STATION at 1-200 km (oblique front, telephoto) vs a fitted-depth reference: depth precision of far stations",
        ) { RenderProbe.stationDepth(it) },

        "render-probe" to Scenario(
            "render-probe",
            "Draw calls, depth slices, body full-screen passes per frame: MERIDIAN spawn, 30 km out, deep space, star behind/ahead",
        ) { RenderProbe.passes(it) },

        "ships" to Scenario(
            "ships",
            "The 8 Roblox ships: 3/4 front + side views, VARROW gear down/half/stowed (no Game); then in Game: cockpit, external, chase, gear in flight, menu backdrop per ship; every hull docks at TRANQUILITY YARDS (outpost stand-in) and MERIDIAN STATION (ring stand-in), the MORVEN flies the whole route into TRANQUILITY",
        ) { ShipsScenario.run(it) },

        "holograms" to Scenario(
            "holograms",
            "The ForceField holograms of the KESSOCK, LOCHRAN and MORVEN up close (see-through rim glow), at two moments of their shimmer (no Game)",
        ) { ShipsScenario.holograms(it) },

        "map" to Scenario(
            "map",
            "Sol system map without Game: opening view (none / MERIDIAN / Saturn selected), pinch on Earth, zoomed to Earth's moons and stations, tap-select Jupiter, ship close-ups, no per-frame allocation",
        ) { MapScenario.run(it) },

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

        "settings" to gameScenario("settings", "Settings overlay over the menu (with FIELD OF VIEW, CAMERA SHAKE, CHASE VIEW)") {
            frames(10)
            cmd("settings")
            frames(30)
            shot("settings")
        },

        "flight" to gameScenario("flight", "Free flight from the cockpit: throttle up with the left thumb, steer with the right stick") {
            cmd("launch space")
            frames(60)
            shot("flight-1") // just launched at 30 % throttle

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
            cmd("launch space")
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
            cmd("launch space")
            frames(5)
            cmd("dock")
            frames(240)
            shot("sys-docking")
            cmd("docked")
            frames(200)
            shot("sys-docked")
        },

        "gameplay" to gameScenario("gameplay", "Checks: off-screen lock arrow (MERIDIAN behind the spawn); land, touch down, take off; Back twice while docked; boost during auto-dock") {
            fun info() = game.debugInfo.also { log(it) }
            cmd("launch space")
            frames(30)
            // off-screen lock: the free-flight spawn is 3 km out of MERIDIAN's mouth with the nose
            // straight away from the station, so MERIDIAN is behind the ship -> the edge arrow
            cmd("lock-station meridian")
            frames(10)
            info()
            check(field("lock") == "${Session.LOCK_STATION}:0") { "MERIDIAN is not locked: ${info()}" }
            check(field("lockArrow") == "true") { "no edge arrow for MERIDIAN behind the ship: ${info()}" }
            shot("gp-lock-arrow")
            cmd("lock clear")
            // --- landing: 60 m above Earth, LAND, hold the down thruster until touchdown
            cmd("teleport-surface")
            frames(2)
            cmd("action")
            frames(140) // gear legs + doors
            check(field("landing") == "true") { "landing mode not engaged: ${info()}" }
            val down = touchLayout.downBtn
            warnOnButtons = false
            val p = pointer(3).down(down.cx, down.y + down.h * 0.5f)
            var n = 0
            while (field("landed") != "true" && n < 60 * 20) { frame(); n++ }
            p.up()
            warnOnButtons = true
            info()
            check(field("landed") == "true") { "never touched down: ${info()}" }
            frames(30)
            shot("gp-landed")
            // --- take off with the action button: must leave the ground and stay up
            cmd("action")
            frames(90)
            info()
            check(field("landed") == "false") { "re-landed right after take-off: ${info()}" }
            check(num("alt") > 20.0) { "take-off did not climb: ${info()}" }
            shot("gp-liftoff")
            // --- docked: Back twice (pause, then resume) must return to the docked screen
            cmd("menu"); frames(5); cmd("launch space"); frames(5)
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

        "station-game" to gameScenario("station-game", "Checks in the real Game: auto-dock from behind MERIDIAN STATION routes round the hull; ramming the hull stops at it") {
            fun info() = game.debugInfo.also { log(it) }
            cmd("launch space")
            frames(30)
            // --- auto-dock from 1.5 km behind the station: the straight line goes through the hub
            cmd("behind-station")
            frames(2)
            cmd("dock")
            frames(1)
            var minHull = Float.MAX_VALUE
            var n = 0
            while (game.stateName != "docked" && n < 60 * 120) {
                frame(); n++
                minHull = minOf(minHull, num("hull").toFloat())
                if (n == 300) shot("st-detour")
            }
            info()
            log("auto-dock from behind: $n frames, closest hull gap %.1f m".format(minHull))
            check(game.stateName == "docked") { "auto-dock from behind never docked: ${info()}" }
            check(minHull > 0f) { "auto-dock touched the hull (gap $minHull m)" }
            frames(30)
            check(num("prox") > 1e30) { "slot wall latched into the proximity warning while docked: ${info()}" }
            shot("st-docked")
            // --- launch, pause at once for 3 s, resume: the launch grace must not run out under pause
            game.launchFromStation()
            frames(2)
            cmd("pause")
            frames(180)
            cmd("resume")
            var minProx = Float.MAX_VALUE
            for (i in 0 until 60) { frame(); minProx = minOf(minProx, num("prox").toFloat()) }
            info()
            check(minProx >= 60f) { "PROXIMITY flashed after a launch + pause (prox $minProx m)" }
            // --- ram the back of the hub at 120 m/s with the throttle held: must stop at the hull
            cmd("ram-station")
            frames(1)
            minHull = Float.MAX_VALUE
            for (i in 0 until 360) {
                frame()
                minHull = minOf(minHull, num("hull").toFloat())
                if (i == 200) shot("st-ram")
            }
            info()
            log("ram: closest hull gap %.1f m".format(minHull))
            check(minHull > -2.5f) { "ship sank into the hull (gap $minHull m)" }
            check(minHull < 5f) { "ram never reached the hull (gap $minHull m)" }
        },

        "boost" to gameScenario("boost", "Flight with throttle up, then boost") {
            cmd("launch space")
            frames(60)
            throttleUp(0.30f, 20)
            frames(30)
            cmd("boost")
            frames(20)
            shot("boost")
        },

        "pause" to gameScenario("pause", "Pause overlay over flight (dimmed HUD)") {
            cmd("launch space")
            frames(30)
            throttleUp(0.25f, 15)
            frames(30)
            cmd("pause")
            frames(30)
            shot("pause")
        },

        "cameras" to gameScenario("cameras", "The flight views while turning: cockpit (default), external, and the optional chase") {
            cmd("launch space")
            frames(30)
            throttleUp(0.30f, 20)
            val right = pointer(1).down(0.75f * w, 0.65f * h)
            right.dragTo(0.75f * w - 30f, 0.65f * h - 20f, 15)
            frames(30)
            check(field("view") == "COCKPIT") { "the cockpit is the default view: ${game.debugInfo}" }
            shot("camera-cockpit")
            cmd("camera") // VIEW: cockpit -> external
            frames(30)
            check(field("view") == "EXTERNAL") { "VIEW goes to the external view: ${game.debugInfo}" }
            shot("camera-external")
            cmd("camera") // without the setting the chase view is skipped
            frames(2)
            check(field("view") == "COCKPIT") { "no chase view unless enabled: ${game.debugInfo}" }
            cmd("view chase")
            frames(30)
            check(field("view") == "CHASE") { "the chase view when enabled: ${game.debugInfo}" }
            shot("camera-chase")
            right.up()
            cmd("view cockpit")
            frames(2)
        },

        "terrain" to Scenario(
            "terrain",
            "Seamless planet terrain: Earth, Luna, Mars from 20,000 km to 20 m (down and horizon, HIGH and LOW); impostor-to-terrain hand-over, ship and outposts on the ground through SceneRenderer; then real-time flight-speed descents",
        ) { TerrainScenarios.run(it) },

        "terrain-perf" to Scenario(
            "terrain-perf",
            "Terrain descents on a no-op GL: game-thread ms, allocations, uploads, worker ms/tile, tiles, triangles, draw calls, memory; llvmpipe frames and depth modes",
        ) { TerrainScenarios.perf(it) },

        "frame-order" to Scenario(
            "frame-order",
            "The Roblox frame order: one traced frame in flight, under the map and docked equals the documented step list; the deck and destruction branches and the early return",
        ) { SessionScenarios.frameOrder(it) },

        "boot-reveal" to Scenario(
            "boot-reveal",
            "LAUNCH from the menu: fade, LOADING curtain black until two frames after the build, the 0.6 s sine-out reveal, the services at the reveal; curtain timeline and shots",
        ) { SessionScenarios.bootReveal(it) },

        "start-docked" to Scenario(
            "start-docked",
            "D13: a fresh start (real LAUNCH tap) parks the ship docked in MERIDIAN STATION's slot above Earth, in the station's frame, services after the reveal, the welcome lines over them 5 s later; LAUNCH on the services flies out with the first-flight hints unspent (30 s docked, no-op GL) and clear of BOOST",
        ) { SessionScenarios.startDocked(it) },

        "camera-views" to Scenario(
            "camera-views",
            "Cockpit look-around (±140°, +80°, -70°, clamp), FOV 60/100, the Roblox external view of every ship, external look 180°, the chase view; \"near <body> [alt]\" by index and by name",
        ) { SessionScenarios.cameraViews(it) },

        "map-nonpausing" to Scenario(
            "map-nonpausing",
            "DECISIONS 21: the ship flies on under the map (position advances by v·3 s), the stick is released and recentred, PAUSE freezes",
        ) { SessionScenarios.mapNonPausing(it) },

        "sol-start" to Scenario(
            "sol-start",
            "Sol: docked at MERIDIAN in the station's frame while Earth carries it (11.16 km/s, the slot holds still), LAUNCH into Earth's frame, a lock on Earth, the map, the save-game clock a day on",
        ) { SolScenarios.start(it) },

        "sol-views" to Scenario(
            "sol-views",
            "Sol from the cockpit: Earth, Luna, Mars, Jupiter and Saturn from a few radii with their locks, Earth from Luna; Jupiter offers no LAND, answers L with LANDING BLOCKED and holds a dive at its cloud tops",
        ) { SolScenarios.views(it) },

        "sol-trip" to Scenario(
            "sol-trip",
            "Sol: MERIDIAN to Mars at full throttle with the nose on Mars, timed to Mars's mass-shadow edge (about a minute) and to 60 km; peak speed at most 0.5 c; shots on the way",
        ) { SolScenarios.trip(it) },

        "sol-moon" to Scenario(
            "sol-moon",
            "Sol: GALILEAN REFINERY to Io at full throttle with the nose on Io; Io limits the speed while Jupiter is the nearest body (the limit is the minimum over all bodies), so the ship comes within 1 km of Io at landing speeds (at most 400 m/s) and the zone 12 radii out is an APPROACH (to Io); shots on the way",
        ) { SolScenarios.moon(it) },

        "sol-docking" to Scenario(
            "sol-docking",
            "Sol: the real auto-dock from 1.5 km behind each of the six stations (no-op GL), in its frame, clear of the hull in and out; then each station on approach (GL)",
        ) { SolScenarios.docking(it) },

        "orbits" to Scenario(
            "orbits",
            "03 9.5: a ship parked over Phobos rides a 6 h clock jump (Phobos turns 1.05 times, 18.2 deg net) and over Earth 4 days (88.9 deg round the Sun), keeping its spot in the frame; the map at t0, 6 h and 4 days on",
        ) { SolScenarios.orbits(it) },

        "ring-debris" to Scenario(
            "ring-debris",
            "03 9.5: flying through Saturn's ring at 300 m/s (FA off), half resolution: many rocks in the B ring (f 0.75), almost none in the Cassini division (the profile's gap, f 0.863), the hit and heat log; none away from rings; a quit to the menu from the B ring leaves no rocks in the menu scene",
        ) { SolScenarios.ringDebris(it) },

        "perf" to Scenario(
            "perf",
            "Flight with continuous two-thumb input (--perf-frames, default 600): CPU ms per drawFrame and bytes allocated per frame",
        ) { Perf.run(it) },
    )
}
