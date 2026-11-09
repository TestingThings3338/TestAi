package com.starwake.desktop

import com.starwake.core.game.StarSystem
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
 * - `sol`: the menu over the Earth, the docked start, the launch towards the planet, then still
 *   views down one descent (each waits until its tiles are built) and a landing on the gear.
 * - `sol-entry`: a real-time dive from 140 km at full throttle (nothing waits: the shots show what a
 *   player sees), with the speed, heat and hull read-outs noted along the way.
 */
object SolScenarios {
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
        val system = StarSystem.sol()
        val earth = system.bodies.first { it.name == "EARTH" }
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

    val sol = gameScenario("sol", "Sol at real scale: menu over the Earth, docked at MERIDIAN, launch, the descent to the ground in still views, a landing", layout = StarSystem.Layout.SOL) {
        frames(150)
        shot("sol-menu")
        cmd("launch")
        settle(600)
        shot("sol-docked")
        cmd("undock")
        frames(120)
        settle(600)
        shot("sol-launched")
        cmd("view external")
        frames(30)
        shot("sol-launched-external")
        cmd("view cockpit")
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
            shot("sol-earth-%07dm".format(alt))
            report.note("earth %d m: zone %s alt %s g %s air %s terrain %s".format(alt, state("zone"), state("alt"), state("g"), state("air"), state("terrain")))
        }
        cmd("view external")
        settle(200)
        shot("sol-earth-external")
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
        shot("sol-landed")
        cmd("view external")
        frames(40)
        shot("sol-landed-external")
    }

    val entry = gameScenario("sol-entry", "A real-time dive into the Earth's air from 140 km at full throttle: glow, heat, drag, the ground coming up", layout = StarSystem.Layout.SOL) {
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
        for (i in 0 until 60 * 40) {
            frame()
            Thread.sleep(8)
            if (i % 60 == 0) {
                report.note("t %2d s: alt %s speed %s heat %s glow %s hull %s zone %s".format(i / 60, state("alt"), state("speed"), state("heat"), state("glow"), state("integrity"), state("zone")))
                if (shots < 14) shot("sol-entry-%02d".format(shots++))
            }
            val alt = state("alt").toDoubleOrNull() ?: 1e9
            if (alt < 1500.0) cmd("throttle 0.2")
            if (alt < 400.0) break
        }
        shot("sol-entry-end")
    }
}
