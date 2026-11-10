package com.starwake.desktop

import com.starwake.core.terrain.FieldScratch
import com.starwake.core.terrain.StyleCatalog
import com.starwake.core.terrain.SurfaceField
import com.starwake.core.terrain.SurfaceSample
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The game's own world in the harness: Sol at real scale, from MERIDIAN STATION down to the ground
 * of the Earth through the real [com.starwake.core.Game] (session, terrain body, hand-over, physics).
 *
 * - `earth`: the menu over the Earth, the docked start, the launch towards the planet, then still
 *   views down one descent (each waits until its tiles are built) and a landing on the gear.
 * - `earth-entry`: a real-time dive from 140 km at full throttle (nothing waits: the shots show what a
 *   player sees), with the speed, heat and hull read-outs noted along the way.
 */
object EarthScenarios {
    /** Runs frames (the ship at rest) until the terrain has built every tile the view wants, at most [maxFrames]. */
    fun GameDriver.settle(maxFrames: Int = 1500) {
        var quiet = 0
        var n = 0
        while (n < maxFrames) {
            frame()
            n++
            if (game.terrainIdle) {
                if (++quiet >= 4) break
            } else {
                quiet = 0
                Thread.sleep(4)
            }
        }
        log("settled after $n frames: ${game.terrainStats}")
    }

    /**
     * Latitude and longitude (degrees) of a place on the Earth worth flying down to: a coast with
     * mountains near it, found on the same height function the game builds (SOL_LAT / SOL_LON override it).
     */
    fun earthTarget(): Pair<Double, Double> {
        val lat = System.getenv("SOL_LAT")?.toDoubleOrNull()
        val lon = System.getenv("SOL_LON")?.toDoubleOrNull()
        if (lat != null && lon != null) return lat to lon
        val system = com.starwake.core.world.WorldBuilder.sol()
        val earth = system.bodies.first { it.name == "Earth" }
        // the sub-solar direction in the Earth's own axes (the star is at the origin)
        val sun = com.starwake.core.math.Vec3()
        earth.rot.rotateInverse((-earth.pos.x).toFloat(), (-earth.pos.y).toFloat(), (-earth.pos.z).toFloat(), sun)
        sun.normalize()
        val profile = com.starwake.core.game.WorldTerrain.profileOf(system, earth)
        val field = SurfaceField.create(earth.radius, StyleCatalog.chooseStyle(profile)!!, profile.seed, profile.bodyClass)
        field.buildMeanColours()
        val fs = FieldScratch()
        val s = SurfaceSample()
        val n = 9000
        val golden = Math.PI * (3.0 - sqrt(5.0))
        var best = -1e30
        var bx = 0.0
        var by = 0.0
        var bz = 1.0
        val r = earth.radius
        for (i in 0 until n) {
            val y = 1.0 - 2.0 * (i + 0.5) / n
            val rr = sqrt(1 - y * y)
            val x = rr * cos(golden * i)
            val z = rr * sin(golden * i)
            // the sun at least 50° up
            if (x * sun.x + y * sun.y + z * sun.z < 0.77) continue
            field.collect(x * r - 1e4, y * r - 1e4, z * r - 1e4, x * r + 1e4, y * r + 1e4, z * r + 1e4, 20_000.0, fs)
            field.sample(x, y, z, 20_000.0, fs, s)
            if (s.isWater) continue
            val m = s.macro
            // land a little above the sea, not desert, not ice, with water within ~150 km and relief around
            var water = 0
            var hi = -1e9
            var lo = 1e9
            for (k in 0 until 8) {
                val a = k * Math.PI / 4
                val px = x + 0.022 * cos(a) * (if (kotlin.math.abs(y) < 0.9) -z else 1.0)
                val pz = z + 0.022 * cos(a) * (if (kotlin.math.abs(y) < 0.9) x else 0.0)
                val py = y + 0.022 * sin(a)
                val l = sqrt(px * px + py * py + pz * pz)
                field.sample(px / l, py / l, pz / l, 20_000.0, fs, s)
                if (s.isWater) water++
                hi = max(hi, s.macro.height)
                lo = min(lo, s.macro.height)
            }
            val score = (if (water in 2..5) 2.0 else 0.0) + min(hi - lo, 3000.0) / 1000.0 - m.dry * 2 - m.cold * 2 - m.frost * 3
            if (score > best) {
                best = score
                bx = x; by = y; bz = z
            }
        }
        return Math.toDegrees(asin(by)) to Math.toDegrees(atan2(bz, bx))
    }

    private fun GameDriver.state(key: String): String =
        Regex("(?:^| )$key=(\\S+)").find(game.debugInfo)?.groupValues?.get(1) ?: "?"

    val sol = gameScenario("earth", "Sol at real scale: menu over the Earth, docked at MERIDIAN, launch, the descent to the ground in still views, a landing") {
        frames(150)
        shot("earth-menu")
        cmd("launch")
        settle(600)
        shot("earth-docked")
        cmd("undock")
        frames(120)
        settle(600)
        shot("earth-launched")
        cmd("view external")
        frames(30)
        shot("earth-launched-external")
        cmd("view cockpit")
        cmd("map")
        frames(20)
        shot("earth-map")
        cmd("resume")
        frames(5)
        val (lat, lon) = earthTarget()
        report.note("descent over lat %.2f lon %.2f".format(lat, lon))
        cmd("throttle 0")
        for (alt in listOf(2_000_000, 400_000, 120_000, 30_000, 8_000, 2_000, 300)) {
            cmd("over EARTH $lat $lon $alt")
            if (alt > 20_000) {
                // nose down by about 40° to look at the ground from high up
                cmd("stick 0 1")
                frames(36)
                cmd("stick off")
                frames(40)
            }
            settle()
            shot("earth-earth-%07dm".format(alt))
            report.note("earth %d m: zone %s alt %s g %s air %s terrain %s".format(alt, state("zone"), state("alt"), state("g"), state("air"), state("terrain")))
        }
        // inside the cloud deck (2,500 m above the reference sphere): the cloud around the cockpit
        val sea = state("sea").toDoubleOrNull() ?: 0.0
        val ground = sea - (state("alt").toDoubleOrNull() ?: 0.0)
        if (ground < 2300.0) {
            cmd("over EARTH $lat $lon ${2500.0 - ground}")
            settle()
            shot("earth-earth-in-cloud")
            report.note("in the deck: sea %s fog %s".format(state("sea"), state("fog")))
            cmd("over EARTH $lat $lon 300")
            settle()
        }
        cmd("view external")
        settle(200)
        shot("earth-earth-external")
        // down on the gear
        cmd("over EARTH $lat $lon 60")
        cmd("view cockpit")
        settle()
        cmd("action")
        frames(140) // gear legs + doors
        val down = touchLayout.downBtn
        warnOnButtons = false
        val p = pointer(3).down(down.cx, down.y + down.h * 0.5f)
        var n = 0
        while (state("landed") != "true" && n < 60 * 25) {
            frame()
            n++
        }
        p.up()
        warnOnButtons = true
        report.note("landing: landed ${state("landed")} alt ${state("alt")} body ${state("body")} hull ${state("integrity")}")
        settle(300)
        shot("earth-landed")
        cmd("view external")
        frames(40)
        shot("earth-landed-external")
    }

    /**
     * `earth-worlds`: the other worlds from 800 km, 15 km and 300 m over their sunlit side (each
     * view waits for its tiles): the Moon, Mars, Venus under its cloud deck, Titan, Europa, Mercury.
     */
    val bodies = gameScenario("earth-worlds", "The other solid worlds of Sol from 800 km, 15 km and 300 m: Luna, Mars, Venus, Titan, Europa, Mercury") {
        cmd("launch space")
        frames(5)
        cmd("throttle 0")
        val only = System.getenv("SOL_BODIES")?.split(',')?.map { it.trim().uppercase() }
        for (name in listOf("LUNA", "MARS", "VENUS", "TITAN", "EUROPA", "MERCURY")) {
            if (only != null && name !in only) continue
            for (alt in listOf(800_000, 15_000, 300)) {
                cmd("near $name $alt")
                if (alt > 5_000) {
                    cmd("stick 0 1")
                    frames(if (alt > 100_000) 44 else 16)
                    cmd("stick off")
                    frames(40)
                }
                settle()
                shot("earth-%s-%06dm".format(name.lowercase(), alt))
                report.note("%s %d m: zone %s alt %s g %s air %s terrain %s".format(name, alt, state("zone"), state("alt"), state("g"), state("air"), state("terrain")))
            }
        }
    }

    /**
     * `earth-sea`: low over the open sea on the Earth's day side, towards the sun and away from it,
     * from the cockpit and from outside: swells, glitter, the sky in the water.
     */
    val sea = gameScenario("earth-sea", "Low over the Earth's sea: swells, sun glitter and the reflected sky, cockpit and external") {
        cmd("launch space")
        frames(5)
        cmd("throttle 0")
        val (lat0, lon0) = earthTarget()
        var found = false
        var lat = lat0
        var lon = lon0
        search@ for (dl in 0..12) for (sign in listOf(1, -1)) for (dlat in listOf(0.0, 4.0, -4.0)) {
            lat = lat0 + dlat
            lon = lon0 + sign * dl * 1.5
            cmd("over EARTH $lat $lon 120")
            frame()
            frame()
            if (state("ground") == "water") {
                found = true
                break@search
            }
        }
        report.note("sea at lat %.2f lon %.2f: %s".format(lat, lon, if (found) "water" else "none found, showing land"))
        for (alt in listOf(40, 400, 3000)) {
            cmd("over EARTH $lat $lon $alt")
            cmd("stick 0 1")
            frames(if (alt > 1000) 14 else 6)
            cmd("stick off")
            frames(40)
            settle()
            shot("earth-sea-%04dm".format(alt))
        }
        cmd("over EARTH $lat $lon 60")
        cmd("view external")
        settle()
        shot("earth-sea-external")
    }

    /**
     * `earth-perf`: the engine's own cost of a descent over the Earth on a no-op GL (no driver): CPU
     * time and bytes allocated per frame on the game thread while the terrain streams under a
     * moving ship. The per-frame path must not allocate (run with -Pnoea, see the perf scenario).
     */
    val perf = Scenario("earth-perf", "CPU ms and allocations per frame of a descent over the Earth on a no-op GL (use -Pnoea)") { env ->
        val opts = env.opts
        val r = env.report
        val (lat, lon) = earthTarget()
        val noop = NoopGl()
        val platform = DesktopPlatform(opts.assetsDir, opts.settings)
        val game = com.starwake.core.Game(platform)
        val d = GameDriver(game, noop, null, platform, r, opts, opts.insets)
        d.start()
        d.cmd("launch space")
        d.frames(5)
        d.cmd("over EARTH $lat $lon 60000")
        d.frames(5)
        // a shallow dive at half throttle: from 60 km to the ground in about a minute
        d.cmd("stick 0 0.35")
        d.frames(40)
        d.cmd("stick off")
        d.cmd("throttle 0.5")
        // warm-up: the JIT, the first tiles, the far look
        for (i in 0 until 600) {
            d.frame()
            Thread.sleep(2)
        }
        val frames = opts.perfFrames.coerceAtLeast(600)
        val meter = PerfMeter(frames) { val c = noop.drawCalls.toInt(); noop.resetCounters(); c }
        meter.game = game
        d.platform.quiet = true
        d.observer = meter
        for (i in 0 until frames) {
            d.frame()
            Thread.sleep(2)
            val alt = Regex(" alt=(\\S+)").find(game.debugInfo)?.groupValues?.get(1)?.toDoubleOrNull() ?: 1e9
            if (alt < 800.0) d.cmd("over EARTH $lat ${lon + 0.02 * i} 30000")
        }
        d.observer = null
        d.platform.quiet = false
        meter.summary("sol descent, no-op GL", 0, r)
        r.note(game.terrainStats)
    }

    val entry = gameScenario("earth-entry", "A real-time dive into the Earth's air from 140 km at full throttle: glow, heat, drag, the ground coming up") {
        cmd("launch space")
        frames(5)
        val (lat, lon) = earthTarget()
        cmd("throttle 0")
        cmd("over EARTH $lat $lon 140000")
        settle()
        cmd("stick 0 0.55")
        frames(50)
        cmd("stick off")
        cmd("throttle 1")
        var shots = 0
        val seconds = System.getenv("SOL_ENTRY_SECONDS")?.toIntOrNull() ?: 40
        for (i in 0 until 60 * seconds) {
            frame()
            Thread.sleep(8)
            if (i % 60 == 0) {
                report.note("t %2d s: alt %s speed %s heat %s glow %s hull %s zone %s".format(i / 60, state("alt"), state("speed"), state("heat"), state("glow"), state("integrity"), state("zone")))
                if (shots < 14) shot("earth-entry-%02d".format(shots++))
                // the shock from outside, every third second
                if (i % 180 == 120 && shots < 14) {
                    cmd("view external")
                    frame()
                    frame()
                    shot("earth-entry-%02d-external".format(shots))
                    // and from the side
                    cmd("look 75 10")
                    frames(3)
                    shot("earth-entry-%02d-side".format(shots))
                    cmd("look off")
                    cmd("view cockpit")
                }
            }
            val alt = state("alt").toDoubleOrNull() ?: 1e9
            if (alt < 1500.0) cmd("throttle 0.2")
            if (alt < 400.0) break
        }
        shot("earth-entry-end")
    }
}
