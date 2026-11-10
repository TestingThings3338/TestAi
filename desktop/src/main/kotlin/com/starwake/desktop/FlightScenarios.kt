package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.flight.FlightLaw
import com.starwake.core.flight.GravityField
import com.starwake.core.flight.ShipHandling
import com.starwake.core.flight.ShipProfiles
import com.starwake.core.flight.SpeedLaw
import com.starwake.core.game.StarSystem
import com.starwake.core.world.WorldBuilder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Harness scenarios of the flight port (02-flight.md 10.5) in the real [Game]:
 *  - "flight-throttle": open space, the lever to 100 % from rest: the combat band, the hand-over at
 *    0.98 TopSpeed and Roblox's doubling-limited cruise curve (5.1), OPEN SPACE all the way;
 *  - "flight-handling": all 8 ships, full pitch from rest, at half TopSpeed and in cruise (agility
 *    0.65 / 1 / 0.42 of the ship's rate) and the FA-off spin (cap 1.1x, no decay);
 *  - "flight-boost": a boost from TopSpeed, a second request at 2 s (refused, cooldown) and at 4.1 s
 *    (accepted), the heat of each ignition and the decay after the burn;
 *  - "flight-trip": Earth to Luna and Earth to Mars (closest approach) from half a barrier radius
 *    above Earth's barrier, as 02 9.4's script: times to the shadow edge and to the ground within
 *    10 % of its table, 0.5 c at most, no tunnelling, landing speed;
 *  - "flight-dive": 50 Mm straight down at Earth: the near band, the attitude indicator inside the
 *    air, the gear at 60 m, the ground at the minimum approach speed;
 *  - "flight-star": into the star at full throttle: HEAT ZONE at the scaled floor, the approach zone,
 *    scooping, the heat timeline and heat death (the crash contract's HEAT);
 *  - "flight-autopilot": AUTO on a locked Luna from the MERIDIAN spawn: ALIGNING, CRUISING, BRAKING,
 *    ARRIVED with their times, and the HUD's autopilot line at the 10 Hz text cadence;
 *  - "flight-dock-load": a boost and a hard pull, then the auto-dock: no G load from the first
 *    docking frame, the boost cooldown runs on (review fix);
 *  - "flight-sensitivity": SENSITIVITY 0.5 and a real thumb: the full stick is the whole cursor and
 *    takes the ship back from the autopilot and the docking computer (review fix);
 *  - "flight-arrow": the Roblox flight arrow for stick deflections 0, 0.1, 0.5 and 1 and with the
 *    head turned; the 8 directions steer the right way;
 *  - "flight-alloc": every flight path with its instruments, measured on the no-op GL: no frame
 *    allocates (run it with -Pnoea, as the perf scenario);
 *  - "gear": the gear sequence in the external view (doors 0.6 s, legs 1.6 s, and back).
 * Long flights run on the no-op GL (the game logic at 60 Hz, nothing drawn); their shots are
 * re-staged afterwards in GL at half resolution: the same clock, place, nose and speed, a few frames
 * later.
 */
object FlightScenarios {
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

    private fun noop(env: ScenarioEnv, script: GameDriver.() -> Unit) {
        val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
        val d = GameDriver(Game(platform), NoopGl(), null, platform, env.report, env.opts, env.opts.insets)
        d.start()
        d.script()
    }

    /** [env] at half the resolution (the same layout in dp). */
    private fun half(env: ScenarioEnv): ScenarioEnv {
        val o = env.opts
        val opts = Options(
            o.outDir, o.assetsDir, o.width / 2, o.height / 2, o.density / 2f, o.scenarios, o.glDebug, o.msaa,
            FloatArray(4) { o.insets[it] / 2f }, o.perfFrames, o.settings,
        )
        return ScenarioEnv(opts, env.egl, env.report)
    }

    private fun GameDriver.fail(msg: String): Nothing = throw IllegalStateException("$msg\n  ${game.debugInfo}")

    private inline fun GameDriver.expect(ok: Boolean, msg: () -> String) {
        if (!ok) fail(msg())
    }

    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)
    private fun f3(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    /** [w] snapped to the game's clock. */
    private fun GameDriver.at(w: StarSystem): StarSystem = w.also { it.snapTo(num("clock")) }

    private fun GameDriver.flying() {
        if (game.stateName != "flight") {
            cmd("launch space")
            frames(5)
        }
    }

    /** At rest at ([x], [y], [z]) system metres, the nose towards ([tx], [ty], [tz]). */
    private fun GameDriver.teleport(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double) {
        cmd("teleport ${f1(x)} ${f1(y)} ${f1(z)} ${f3(tx - x)} ${f3(ty - y)} ${f3(tz - z)}")
    }

    /**
     * At rest 6 Gm from the star and 2 Gm above its plane: no body, station or edge limits (OPEN
     * SPACE); the nose on the star, FA on, lever 0, a full tank, the stick free.
     */
    private fun GameDriver.openSpace(w: StarSystem) {
        flying()
        val sun = at(w).bodies[w.star].pos
        teleport(sun.x, sun.y + 2e9, sun.z + 6e9, sun.x, sun.y, sun.z)
        cmd("fa on")
        cmd("throttle 0")
        cmd("stick off")
        cmd("fuel 1")
        cmd("heat 0")
        frames(2)
    }

    private fun GameDriver.distTo(w: StarSystem, body: Int): Double {
        val p = vec("pos")
        val b = at(w).bodies[body].pos
        val dx = p[0] - b.x
        val dy = p[1] - b.y
        val dz = p[2] - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    // ------------------------------------------------------------------ flight-throttle

    /** 02 5.1: Roblox's open-space curve (s after the lever went to 100 %, m/s); doubling-limited, so the port's 0.5 c reference changes nothing below 3 Mm/s. */
    private val CRUISE_CURVE = doubleArrayOf(
        8.0, 474.0, 9.0, 1_130.0, 10.0, 2_680.0, 11.0, 6_380.0, 12.0, 15_200.0, 13.0, 36_100.0,
        14.0, 85_900.0, 15.0, 204_000.0, 16.0, 486_000.0, 17.0, 1_160_000.0,
    )

    fun throttle(env: ScenarioEnv) {
        noop(env) {
            val w = WorldBuilder.sol()
            openSpace(w)
            val start = frame
            cmd("throttle 1")
            val speed = DoubleArray(21)
            var handover = -1.0
            while (frame - start < 20 * 60) {
                frame()
                val n = frame - start
                if (handover < 0 && field("cruising") == "true") handover = n / 60.0
                if (n % 60 == 0L) {
                    val t = (n / 60).toInt()
                    speed[t] = num("speed")
                    log("t %2d s: speed %s m/s, limit %s, zone %s, cruising %s, lever %s".format(t, f1(speed[t]), field("limit"), field("zone"), field("cruising"), field("throttle")))
                    expect(field("zone") == "OPEN_SPACE") { "the zone at $t s is ${field("zone")}, not OPEN SPACE" }
                }
            }
            report.note("lever 100 % at rest in open space: cruise from ${f2(handover)} s; " + (1..20).joinToString(", ") { "$it s ${f1(speed[it])}" } + " m/s")
            // the combat band: 35 m/s² from rest, no cruise yet
            expect(abs(speed[5] - 175.0) < 3.0) { "5 s: ${speed[5]} m/s, expected 175 (35 m/s² from rest)" }
            // the band hands over at 0.98 TopSpeed: 254.8 m/s after 7.28 s
            expect(abs(handover - 7.28) < 0.1) { "the cruise took over after $handover s (7.28 expected)" }
            var i = 0
            while (i < CRUISE_CURVE.size) {
                val t = CRUISE_CURVE[i].toInt()
                val v = CRUISE_CURVE[i + 1]
                expect(abs(speed[t] - v) <= v * 0.02) { "$t s: ${speed[t]} m/s, Roblox's curve $v (±2 %)" }
                i += 2
            }
            // past Roblox's 3 Mm/s cap the port keeps doubling towards 0.5 c
            expect(speed[20] > 1e7 && speed[20] <= FlightLaw.HALF_LIGHT) { "20 s: ${speed[20]} m/s" }
        }
        run(half(env)) {
            val w = WorldBuilder.sol()
            openSpace(w)
            val start = frame
            cmd("throttle 1")
            for (mark in intArrayOf(5, 12, 18)) {
                while (frame - start < mark * 60) frame()
                log("t $mark s: speed ${field("speed")} m/s, zone ${field("zone")}, cruising ${field("cruising")}")
                shot("flight-throttle-%02ds".format(mark))
            }
        }
    }

    // ------------------------------------------------------------------ flight-handling

    private fun deg(rad: Float) = rad.toDouble() * 180.0 / Math.PI

    fun handling(env: ScenarioEnv) {
        val table = ArrayList<String>()
        noop(env) {
            val w = WorldBuilder.sol()
            flying()
            for (p in ShipProfiles.all) {
                cmd("ship ${p.id}")
                frames(2)
                expect(field("ship") == p.id) { "the ship command did not switch to ${p.id}" }
                // what the handling of a full tank gives at a speed: the turn rates include the agility
                val h = ShipHandling(p)
                fun pitchAt(speed: Double): Double {
                    h.update(0.0, speed, 1.0, 0.0)
                    return deg(h.turnRatePitch)
                }
                h.update(0.0, 0.0, 1.0, 0.0)
                val top = h.topSpeed

                fun pitch(n: Int): Double {
                    cmd("stick 0 -1")
                    frames(n)
                    val r = vec("rates")[0]
                    cmd("stick off")
                    return r
                }

                fun near(what: String, expected: Double, got: Double) =
                    expect(abs(got - expected) <= maxOf(0.05, expected * 0.02)) { "${p.id} $what: ${f2(got)} °/s, expected ${f2(expected)}" }

                // from rest: agility 0.65
                openSpace(w)
                val rest = pitch(45)
                near("pitch from rest", pitchAt(0.0), rest)
                // half TopSpeed: agility 1 (the lever's linear band holds the speed)
                openSpace(w)
                cmd("throttle ${f3(top * 0.5 / 1000.0)}")
                cmd("speed ${f1(top * 0.5)}")
                frames(30)
                val halfTop = pitch(45)
                near("pitch at half TopSpeed (${f1(num("speed"))} m/s)", pitchAt(top * 0.5), halfTop)
                // in cruise: the agility floor 0.42
                openSpace(w)
                cmd("throttle 1")
                cmd("speed ${f1(top * 5.0)}")
                frames(10)
                expect(field("cruising") == "true") { "${p.id} not cruising at 5x TopSpeed" }
                val cruise = pitch(45)
                near("pitch in cruise", pitchAt(top * 5.0), cruise)
                // FA off: torque up to 1.1x the standing rate, then the spin goes on without input
                openSpace(w)
                cmd("fa off")
                val spin = pitch(60)
                frames(60)
                val kept = vec("rates")[0]
                near("FA-off spin cap", pitchAt(0.0) * 1.1, spin)
                expect(abs(kept - spin) < 0.01) { "${p.id}: the FA-off spin decayed from $spin to $kept °/s" }
                cmd("fa on")
                val row = "%-9s mass %6.1f t  top %6.2f m/s  pitch deg/s: rest %6.2f  half top %6.2f  cruise %6.2f  FA-off spin %6.2f (kept %6.2f)"
                    .format(p.id.uppercase(), num("mass"), top, rest, halfTop, cruise, spin, kept)
                table += row
                log(row)
            }
            for (r in table) report.note(r)
        }
        // one shot per ship: the external view, pitching up in cruise
        run(half(env)) {
            val w = WorldBuilder.sol()
            flying()
            cmd("view external")
            for (p in ShipProfiles.all) {
                cmd("ship ${p.id}")
                frames(2)
                openSpace(w)
                cmd("throttle 1")
                cmd("speed ${f1(ShipHandling(p).also { it.update(0.0, 0.0, 1.0, 0.0) }.topSpeed * 5.0)}")
                cmd("stick 0.3 -1")
                frames(40)
                shot("flight-handling-${p.id}")
                cmd("stick off")
            }
        }
    }

    // ------------------------------------------------------------------ flight-boost

    fun boost(env: ScenarioEnv) = run(half(env)) {
        val w = WorldBuilder.sol()
        openSpace(w)
        cmd("view external")
        val top = num("top")
        cmd("throttle ${f3(top / 1000.0)}")
        cmd("speed ${f1(top)}")
        frames(60)
        expect(abs(num("speed") - top) < 1.0) { "not holding TopSpeed before the boost" }
        val heat0 = num("heat")
        cmd("boost")
        val t0 = frame
        var peak = 0.0
        var end = -1.0
        var at103 = 0.0
        var at38 = 0.0
        var heat3 = 0.0
        var third = -1
        while (frame - t0 < 8 * 60) {
            frame()
            val n = (frame - t0).toInt()
            val v = num("speed")
            peak = maxOf(peak, v)
            val boosting = field("boosting") == "true"
            val cruising = field("cruising") == "true"
            when (n) {
                1 -> {
                    expect(boosting) { "the boost did not ignite" }
                    expect(abs(num("heat") - heat0 - 0.06) < 0.003) { "the boost heat: ${num("heat") - heat0}, expected 0.06" }
                }
                30 -> shot("flight-boost-0.5s")
                62 -> at103 = v
                120 -> cmd("boost") // 2.0 s: the cooldown (4 s from the ignition) refuses it
                126 -> {
                    expect(!boosting) { "a second boost 2 s after the first ignited" }
                    expect(num("cooldown") > 1.8) { "the cooldown runs from the ignition: ${field("cooldown")} s left at 2.1 s" }
                }
                180 -> shot("flight-boost-3.0s")
                228 -> at38 = v
                // 4.1 s: the cooldown is over, but the ship still cruises down from the burn (above
                // 1.02 TopSpeed): Roblox ignites only when it was not cruising the frame before
                246 -> {
                    expect(num("cooldown") == 0.0) { "the cooldown is not over at 4.1 s" }
                    expect(cruising) { "not cruising down at 4.1 s (${f1(v)} m/s)" }
                    cmd("boost")
                }
                250 -> expect(!boosting) { "a boost while cruising down after the burn ignited" }
            }
            // back in the combat band (<= 1.02 TopSpeed): the next request ignites
            if (n > 250 && third < 0 && !cruising && !boosting) {
                third = n
                heat3 = num("heat")
                cmd("boost")
            }
            if (third > 0 && n == third + 1) {
                expect(boosting) { "the boost back in the combat band was refused" }
                expect(abs(num("heat") - heat3 - 0.06) < 0.003) { "the third boost's heat: ${num("heat") - heat3}" }
            }
            if (third > 0 && n == third + 15) shot("flight-boost-again")
            if (end < 0 && n > 1 && !boosting && n < 240) end = n / 60.0
            if (n % 30 == 0) log("t %.2f s: speed %s m/s, boosting %s, cruising %s, cooldown %s s, heat %s".format(n / 60.0, f1(v), field("boosting"), field("cruising"), field("cooldown"), field("heat")))
        }
        report.note(
            ("boost from %s m/s: %s m/s after 1.03 s (340 in 1.04 s), peak %s, burn %.2f s, %s m/s 1.8 s after the burn (260 + 80/e = 289.4); " +
                "requests at 2.0 s (cooldown) and 4.1 s (cruising down at %s) refused, the next accepted %.2f s after the first ignition, back in the band")
                .format(f1(top), f1(at103), f1(peak), end, f1(at38), "> 1.02 TopSpeed", third / 60.0),
        )
        expect(abs(peak - 340.0) < 1.5) { "the boost peaked at $peak m/s (BoostSpeed 340)" }
        expect(at103 > 336.0) { "the boost reached only $at103 m/s after 1.03 s (340 in 1.04 s)" }
        expect(abs(end - 2.0) < 0.05) { "the burn lasted $end s (2 s)" }
        expect(abs(at38 - 289.4) < 3.0) { "1.8 s after the burn: $at38 m/s (the 1.8 s decay towards TopSpeed gives 289.4)" }
        // 80 m/s above TopSpeed decays to 1.02 TopSpeed in 1.8 ln(80 / 5.2) = 4.92 s after the burn
        expect(third > 0 && abs(third / 60.0 - 6.92) < 0.15) { "the next boost came ${third / 60.0} s after the first ignition (6.92 expected)" }
    }

    // ------------------------------------------------------------------ flight-trip

    /** One leg of the trip: times (s) and speeds; the frames to re-stage for the shots. */
    private class Leg(val label: String, val target: String) {
        var gap = Double.NaN
        var shadow = Double.NaN
        var low = Double.NaN
        var ground = Double.NaN
        var groundSpeed = 0.0
        var peak = 0.0
        var minAlt = Double.MAX_VALUE
        val marks = ArrayList<Mark>()
    }

    /** A moment to draw: the clock, the place (system m) and the speed. */
    private class Mark(val name: String, val clock: Double, val x: Double, val y: Double, val z: Double, val speed: Double)

    private fun GameDriver.mark(leg: Leg, name: String) {
        val p = vec("pos")
        leg.marks += Mark(name, num("clock"), p[0], p[1], p[2], num("speed"))
    }

    /** The clock of the conjunction of bodies [a] and [b] nearest after 0 (their least distance). */
    private fun conjunction(system: StarSystem, a: Int, b: Int): Double {
        val ta = system.bodies[a].orbitPeriod
        val tb = system.bodies[b].orbitPeriod
        val synodic = 1.0 / abs(1.0 / ta - 1.0 / tb)
        var best = 0.0
        var bestD = Double.MAX_VALUE
        var lo = 0.0
        var hi = synodic
        repeat(3) {
            val step = (hi - lo) / 2000.0
            var c = lo
            while (c <= hi) {
                system.snapTo(c)
                val pa = system.bodies[a].pos
                val pb = system.bodies[b].pos
                val d = sqrt((pa.x - pb.x) * (pa.x - pb.x) + (pa.y - pb.y) * (pa.y - pb.y) + (pa.z - pb.z) * (pa.z - pb.z))
                if (d < bestD) {
                    bestD = d
                    best = c
                }
                c += step
            }
            lo = best - step
            hi = best + step
        }
        return best
    }

    /** At [clock], half a barrier radius above [from]'s barrier on the line to [to], at rest, nose and lock on [to], lever 100 %. */
    private fun GameDriver.legStart(w: StarSystem, from: String, to: String, clock: Double) {
        flying()
        cmd("clock ${f3(clock)}")
        frames(1)
        at(w)
        val a = w.bodies[w.bodyIndex(from)]
        val b = w.bodies[w.bodyIndex(to)]
        val dx = b.pos.x - a.pos.x
        val dy = b.pos.y - a.pos.y
        val dz = b.pos.z - a.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val r = a.collisionRadius * 1.5
        teleport(a.pos.x + dx / d * r, a.pos.y + dy / d * r, a.pos.z + dz / d * r, b.pos.x, b.pos.y, b.pos.z)
        cmd("lock-body ${to.lowercase()}")
        cmd("throttle 1")
    }

    private fun GameDriver.fly(w: StarSystem, leg: Leg) {
        val ti = w.bodyIndex(leg.target)
        val tb = w.bodies[ti]
        val edge = tb.radius + GravityField(FlightLaw.PORT).shadowOf(tb)
        val crashes0 = num("crashes")
        val start = frame
        var markAt = 10.0
        var peakMarked = false
        while (frame - start < 60 * 200) {
            if ((frame - start) % 30 == 0L) game.debugCommand("face ${leg.target.lowercase()}")
            val before = num("speed")
            frame()
            val t = (frame - start) / 60.0
            val v = num("speed")
            val d = distTo(w, ti)
            val alt = d - tb.radius
            leg.minAlt = minOf(leg.minAlt, alt)
            if (leg.gap.isNaN()) leg.gap = alt // (the start's commands run with the first frame)
            if (v > leg.peak) leg.peak = v
            if (t >= markAt && markAt == 10.0) {
                mark(leg, "cruise-10s")
                markAt = -1.0
            }
            if (!peakMarked && leg.peak > 1e7 && v < leg.peak * 0.999) {
                peakMarked = true
                mark(leg, "peak")
            }
            if (leg.shadow.isNaN() && d < edge) {
                leg.shadow = t
                mark(leg, "shadow-edge")
            }
            if (leg.low.isNaN() && alt < 60_000.0) {
                leg.low = t
                mark(leg, "60km")
            }
            if (num("crashes") > crashes0) fail("${leg.label}: crashed on arrival")
            if (alt < 2_000.0 && leg.marks.none { it.name == "2km" }) mark(leg, "2km")
            if (num("alt") < 1.0 && field("body") == leg.target) {
                leg.ground = t
                leg.groundSpeed = maxOf(before, v)
                break
            }
        }
        if (leg.ground.isNaN()) fail("${leg.label}: never reached the ground in 200 s")
    }

    fun trip(env: ScenarioEnv) {
        val legs = ArrayList<Pair<Leg, Double>>()
        val refs = mapOf("Luna" to doubleArrayOf(51.8, 85.3, 0.19), "Mars" to doubleArrayOf(55.5, 95.4, 0.38))
        noop(env) {
            val w = WorldBuilder.sol()
            val earth = w.bodyIndex("Earth")
            for (target in listOf("Luna", "Mars")) {
                // Luna circles Earth at a fixed distance; Mars at its closest (02 9.4 "closest approach")
                val clock = if (target == "Luna") 0.0 else conjunction(WorldBuilder.sol(), earth, w.bodyIndex("Mars"))
                val leg = Leg("Earth -> $target", target)
                legStart(w, "Earth", target, clock)
                fly(w, leg)
                val ref = refs.getValue(target)
                val row = "%s (clock %.0f s, %,.0f km): shadow edge %.1f s (9.4: %.1f), 60 km %.1f s, ground %.1f s (9.4: %.1f) at %.1f m/s, peak %.1f Mm/s = %.3f c (9.4: %.2f c), lowest %.1f m"
                    .format(leg.label, clock, leg.gap / 1000.0, leg.shadow, ref[0], leg.low, leg.ground, ref[1], leg.groundSpeed, leg.peak / 1e6, leg.peak / 2.998e8, ref[2], leg.minAlt)
                log(row)
                report.note(row)
                expect(abs(leg.shadow - ref[0]) <= ref[0] * 0.1) { "${leg.label}: ${leg.shadow} s to the shadow edge, 9.4 gives ${ref[0]} (±10 %)" }
                expect(abs(leg.ground - ref[1]) <= ref[1] * 0.1) { "${leg.label}: ${leg.ground} s to the ground, 9.4 gives ${ref[1]} (±10 %)" }
                expect(abs(leg.peak / 2.998e8 - ref[2]) <= ref[2] * 0.1) { "${leg.label}: peak ${leg.peak} m/s" }
                expect(leg.peak <= FlightLaw.HALF_LIGHT * 1.0001) { "${leg.label}: faster than 0.5 c" }
                expect(leg.minAlt > -1.0) { "${leg.label}: went ${leg.minAlt} m into the ground" }
                expect(leg.groundSpeed <= 16.0) { "${leg.label}: came down at ${leg.groundSpeed} m/s" }
                legs += leg to clock
            }
        }
        // the shots: each mark re-staged (clock, place, nose on the target, speed) and drawn a few frames later
        run(half(env)) {
            val w = WorldBuilder.sol()
            flying()
            for ((leg, _) in legs) {
                val ti = w.bodyIndex(leg.target)
                for (m in leg.marks) {
                    cmd("clock ${f3(m.clock)}")
                    frames(1)
                    val b = at(w).bodies[ti].pos
                    teleport(m.x, m.y, m.z, b.x, b.y, b.z)
                    cmd("lock-body ${leg.target.lowercase()}")
                    cmd("throttle 1")
                    cmd("speed ${f1(m.speed)}")
                    frames(3)
                    log("${leg.label} ${m.name}: speed ${field("speed")} m/s, zone ${field("zone")}, limit ${field("limit")}, body ${field("body")}")
                    shot("flight-trip-${leg.target.lowercase()}-${m.name}")
                }
            }
        }
    }

    // ------------------------------------------------------------------ flight-dive

    fun dive(env: ScenarioEnv) {
        val marks = ArrayList<Pair<String, Double>>()
        noop(env) {
            val w = WorldBuilder.sol()
            flying()
            cmd("near earth 5e7")
            cmd("face earth")
            cmd("lock-body earth")
            cmd("throttle 1")
            frames(1)
            val earth = w.bodyIndex("Earth")
            val r = w.bodies[earth].radius
            val crashes0 = num("crashes")
            val start = frame
            var band = Double.NaN
            var bandZone = ""
            var air = Double.NaN
            var gearAt = Double.NaN
            var v1km = Double.NaN
            var landedSpeed = Double.NaN
            var lastLog = -1
            while (frame - start < 60 * 240) {
                if ((frame - start) % 30 == 0L) game.debugCommand("face earth")
                val before = num("speed")
                frame()
                val t = (frame - start) / 60.0
                val alt = distTo(w, earth) - r
                val attitude = field("attitude") == "true"
                val gear = num("gear")
                if (alt > 30_000.0) expect(!attitude) { "the attitude indicator above Earth's air (${f1(alt)} m)" }
                if (alt < 25_000.0) expect(attitude) { "no attitude indicator inside Earth's air (${f1(alt)} m)" }
                if (air.isNaN() && attitude) air = alt
                if (alt > 100.0) expect(gear == 0.0) { "the gear came down at ${f1(alt)} m" }
                if (gearAt.isNaN() && gear > 0.0) gearAt = alt
                if (band.isNaN() && alt < 11_428.0) {
                    band = t
                    bandZone = field("zone") ?: ""
                }
                if (alt < 11_000.0) expect(num("limit") <= 4000.0) { "above 4000 m/s inside the near band" }
                // Roblox's approach zone below the shadow floor (954 km/s at Earth), above the near band: LOW ALTITUDE
                if (alt in 15_000.0..1_000_000.0) expect(field("zone") == "LOW_ALTITUDE") { "the zone ${f1(alt)} m up is ${field("zone")}" }
                if (v1km.isNaN() && alt < 1000.0) v1km = maxOf(before, num("speed"))
                val sec = t.toInt()
                if (sec != lastLog && (sec % 5 == 0 || alt < 20_000.0)) {
                    lastLog = sec
                    log("t %5.1f s: alt %s m, speed %s m/s, limit %s, zone %s, gear %s, attitude %s".format(t, f1(alt), field("speed"), field("limit"), field("zone"), field("gear"), field("attitude")))
                }
                if (num("crashes") > crashes0) fail("crashed on the dive")
                if (num("alt") < 1.0) {
                    landedSpeed = maxOf(before, num("speed"))
                    report.note(
                        "dive from 50 Mm: near band after %.1f s (zone %s), attitude from %s m, gear down at %s m, 1 km at %s m/s, ground after %.1f s at %s m/s"
                            .format(band, bandZone, f1(air), f1(gearAt), f1(v1km), t, f1(landedSpeed)),
                    )
                    break
                }
            }
            expect(!landedSpeed.isNaN()) { "never reached the ground" }
            expect(landedSpeed <= 14.5) { "came down at $landedSpeed m/s (14 m/s)" }
            expect(v1km <= SpeedLaw.approachReach(1000.0, 70.0) * 1.05) { "1 km up at $v1km m/s" }
            expect(gearAt in 40.0..61.0) { "the gear came down at $gearAt m (60 m)" }
            for (alt in doubleArrayOf(40_000.0, 20_000.0, 5_000.0, 300.0, 40.0)) marks += "%.0fm".format(Locale.ROOT, alt) to alt
        }
        // shots re-staged on the way down: the speed the dive had at that height
        run(half(env)) {
            flying()
            for ((name, alt) in marks) {
                cmd("near earth ${f1(alt)}")
                cmd("face earth")
                cmd("lock-body earth")
                cmd("throttle 1")
                val v = when {
                    alt > 11_428.0 -> alt / 2.0
                    else -> maxOf(14.0, SpeedLaw.approachReach(alt, 70.0))
                }
                cmd("speed ${f1(v)}")
                frames(3)
                log("dive $name: alt ${field("alt")}, speed ${field("speed")}, zone ${field("zone")}, gear ${field("gear")}, attitude ${field("attitude")}")
                shot("flight-dive-$name")
            }
        }
    }

    // ------------------------------------------------------------------ flight-star

    fun star(env: ScenarioEnv) {
        noop(env) {
            val w = WorldBuilder.sol()
            flying()
            cmd("fuel 0.5")
            cmd("near sol 6e7")
            cmd("face sol")
            cmd("throttle 1")
            frames(1)
            val sun = w.star
            val r = w.bodies[sun].radius
            val start = frame
            var heatZone = Double.NaN
            var heatLimit = Double.MAX_VALUE
            var surface = Double.NaN
            var warm = Double.NaN
            var dead = Double.NaN
            var scooped = false
            val fuel0 = num("fuel")
            var fuelMax = fuel0
            var lastLog = -1
            val crashes0 = num("crashes")
            while (frame - start < 60 * 240) {
                if ((frame - start) % 30 == 0L) game.debugCommand("face sol")
                frame()
                val t = (frame - start) / 60.0
                val alt = distTo(w, sun) - r
                if (field("zone") == "HEAT_ZONE") {
                    if (heatZone.isNaN()) heatZone = alt
                    heatLimit = minOf(heatLimit, num("limit"))
                }
                if (field("scooping") == "true") scooped = true
                fuelMax = maxOf(fuelMax, num("fuel"))
                if (surface.isNaN() && alt < 10.0) surface = t
                if (warm.isNaN() && num("heat") >= 1.0) warm = t
                val sec = t.toInt()
                if (sec != lastLog) {
                    lastLog = sec
                    log("t %3d s: alt %s m, speed %s m/s, limit %s, zone %s, heat %s, star heat %s, scooping %s, fuel %s".format(sec, f1(alt), field("speed"), field("limit"), field("zone"), field("heat"), field("starHeat"), field("scooping"), field("fuel")))
                }
                if (num("crashes") > crashes0) {
                    dead = t
                    break
                }
            }
            fun after(t: Double) = if (t.isNaN()) "never" else "after %.1f s".format(t)
            report.note(
                "into the star from 60 Mm: HEAT ZONE from %s m at %s m/s at least, at the surface %s, 100 %% heat %s, heat death %s (cause %s), fuel %.3f -> %.3f by scooping"
                    .format(f1(heatZone), f1(heatLimit), after(surface), after(warm), after(dead), field("cause"), fuel0, fuelMax),
            )
            expect(!heatZone.isNaN()) { "never in the HEAT ZONE" }
            // 02 3.5: the pilot can always leave: the heat zone's floor 1 km/s, x S in the port
            expect(heatLimit >= 1000.0 * 3000.0 * 0.999) { "the HEAT ZONE limit fell to $heatLimit m/s" }
            expect(!warm.isNaN() && !dead.isNaN() && warm < dead) { "no 100 % warning before the heat death" }
            expect(field("cause") == "HEAT" && field("docked") == "true") { "the crash was not the heat (or no docked restart)" }
            expect(scooped && fuelMax > fuel0 + 0.05) { "no scooping near the star" }
            // the heat builds all the way in (star heat 0.46 at 60 Mm, 1 at the surface): 100 % ->
            // 200 % takes 17.4 s held at the surface (02 3.6), longer while still descending; a dive
            // straight in at full throttle burns up on the way down, before the barrier
            expect(dead - warm in 17.4..30.0) { "100 % to 200 % took ${dead - warm} s (17.4 at the surface)" }
        }
        run(half(env)) {
            flying()
            // the heat zone at the scaled floor
            cmd("fuel 0.5")
            cmd("near sol 5e7")
            cmd("face sol")
            cmd("throttle 1")
            cmd("speed 3000000")
            cmd("heat 0.3")
            frames(3)
            log("star heat zone: ${field("zone")} ${field("limit")} heat ${field("heat")}")
            shot("flight-star-heat-zone")
            // held at the surface, over 100 %
            cmd("near sol 5")
            cmd("face sol")
            cmd("heat 1.2")
            frames(20)
            log("star surface: ${field("zone")} speed ${field("speed")} heat ${field("heat")} scooping ${field("scooping")}")
            shot("flight-star-surface-120")
            // heat death: the crash contract (Session.startCrash(HEAT)); its placeholder until the
            // crash flow: SHIP DESTROYED, then the docked start at the station nearest the crash
            val crashes0 = num("crashes")
            cmd("heat 2.01")
            frames(2)
            log("star heat death: crashes ${field("crashes")} cause ${field("cause")} docked ${field("docked")} station ${field("station")} message ${game.messageLine}")
            expect(num("crashes") == crashes0 + 1 && field("cause") == "HEAT") { "no heat death at 201 %" }
            expect(field("docked") == "true" && game.messageLine == "SHIP DESTROYED") { "no docked restart after the heat death" }
            expect(num("heat") == 0.0) { "the docked start did not cool the ship" }
            frames(10)
            shot("flight-star-heat-death")
        }
    }

    // ------------------------------------------------------------------ flight-autopilot

    fun autopilot(env: ScenarioEnv) {
        var aligned = -1L
        var cruiseShot = -1L
        var braking: Mark? = null
        var arrived: Mark? = null
        noop(env) {
            val w = WorldBuilder.sol()
            flying()
            cmd("lock-body luna")
            cmd("autopilot")
            frame()
            expect(field("apOn") == "true") { "AUTO did not engage on a locked Luna" }
            // the HUD's line (and AUTO's sub-label) shows in the frame it engaged, then reads the
            // status at Roblox's 10 Hz text cadence (ShipHud's TextUpdateInterval, 02 9.6)
            var hudShown = game.hudAutopilot
            expect(hudShown == field("ap")?.replace('_', ' ')) { "the HUD shows '$hudShown' in the frame AUTO engaged" }
            var hudChanges = 0
            var hudLastChange = frame
            var hudMinGap = Long.MAX_VALUE
            val start = frame
            val luna = w.bodyIndex("Luna")
            val r = w.bodies[luna].radius
            val seen = ArrayList<String>()
            var last = ""
            var changes = 0
            while (frame - start < 60 * 400) {
                frame()
                val t = (frame - start) / 60.0
                val st = field("ap") ?: ""
                val hudNow = game.hudAutopilot
                if (field("apOn") == "true") {
                    expect(hudNow.isNotEmpty()) { "the HUD dropped the autopilot line while it flies" }
                    if (hudNow != hudShown) {
                        hudChanges++
                        hudMinGap = minOf(hudMinGap, frame - hudLastChange)
                        hudLastChange = frame
                        hudShown = hudNow
                    }
                } else {
                    expect(hudNow.isEmpty()) { "the HUD still shows '$hudNow' after the autopilot went off" }
                }
                // the first braking (the status is re-evaluated every frame, as in Roblox: near 1.05 x
                // the desired speed it flips between CRUISING and BRAKING); re-staged 20 % faster so
                // the shot shows a clear BRAKING
                if (st == "BRAKING" && braking == null) {
                    val p = vec("pos")
                    braking = Mark("braking", num("clock"), p[0], p[1], p[2], num("speed") * 1.2)
                }
                if (st != last) {
                    last = st
                    changes++
                    if (st !in seen) {
                        seen += st
                        val alt = distTo(w, luna) - r
                        val row = "t %6.2f s: %s first (speed %s m/s, Luna %s km below, limit %s)".format(t, st, field("speed"), f1(alt / 1000.0), field("limit"))
                        log(row)
                        report.note(row)
                    }
                    if (st == "CRUISING" && aligned < 0) {
                        aligned = frame
                        cruiseShot = frame + 60
                    }
                    if (st == "ARRIVED") {
                        val p = vec("pos")
                        arrived = Mark("arrived", num("clock"), p[0], p[1], p[2], num("speed"))
                        val row = "t %6.2f s: ARRIVED %s km above Luna at %s m/s after %d status changes".format(t, f1((distTo(w, luna) - r) / 1000.0), field("speed"), changes)
                        log(row)
                        report.note(row)
                    }
                }
                if (field("apOn") != "true") break
            }
            val order = listOf("ALIGNING", "CRUISING", "BRAKING", "ARRIVED")
            var k = 0
            for (s in seen) if (k < order.size && s == order[k]) k++
            expect(k == order.size) { "the autopilot went ${seen.joinToString(" -> ")}, not ALIGNING -> CRUISING -> BRAKING -> ARRIVED" }
            expect(field("ap") == "ARRIVED" && field("apOn") == "false") { "the autopilot did not finish ARRIVED" }
            val alt = distTo(w, luna) - r
            expect(alt <= r * 0.35 + 1000.0 && num("speed") <= 25.5) { "ARRIVED ${f1(alt)} m above Luna at ${field("speed")} m/s" }
            expect(num("crashes") == 0.0) { "crashed" }
            val flown = (frame - start) / 60.0
            val row = "HUD line: the live status changed %d times in %s s, the HUD's line %d times (%s per s, closest %d frames apart)"
                .format(changes, f1(flown), hudChanges, f2(hudChanges / flown), hudMinGap)
            log(row)
            report.note(row)
            expect(hudMinGap >= 6) { "the HUD's autopilot line changed twice within $hudMinGap frames (10 Hz: 6 frames)" }
            expect(hudChanges <= flown * 10.0 + 1.0) { "the HUD's autopilot line changed $hudChanges times in ${f1(flown)} s" }
        }
        run(half(env)) {
            val w = WorldBuilder.sol()
            flying()
            cmd("lock-body luna")
            cmd("autopilot")
            frames(30)
            shot("flight-autopilot-aligning")
            while (frame < cruiseShot) frame()
            shot("flight-autopilot-cruising")
            val luna = w.bodyIndex("Luna")
            for (m in listOfNotNull(braking, arrived)) {
                cmd("clock ${f3(m.clock)}")
                frames(1)
                val b = at(w).bodies[luna].pos
                teleport(m.x, m.y, m.z, b.x, b.y, b.z)
                cmd("speed ${f1(m.speed)}")
                cmd("lock-body luna")
                frames(1)
                if (field("apOn") != "true") cmd("autopilot")
                frames(4)
                log("autopilot ${m.name}: ${field("ap")}, speed ${field("speed")}")
                shot("flight-autopilot-${m.name}")
            }
        }
    }

    // ------------------------------------------------------------------ flight-arrow

    fun arrow(env: ScenarioEnv) {
        // the 8 directions at full deflection steer the right way (default MOUSE X AXIS = ROLL)
        noop(env) {
            val w = WorldBuilder.sol()
            for (k in 0 until 8) {
                val a = k * Math.PI / 4.0
                val x = Math.cos(a)
                val y = -Math.sin(a) // screen Y down: k = 2 is straight up
                openSpace(w)
                cmd("stick ${f3(x)} ${f3(y)}")
                frames(20)
                val aim = vec("aimArrow")
                val rates = vec("rates")
                cmd("stick off")
                log("direction %d (%s, %s): arrow %s, %s, rates pitch %s roll %s °/s".format(k, f2(x), f2(y), f2(aim[0]), f2(aim[1]), f2(rates[0]), f2(rates[2])))
                expect(abs(aim[0] - x) < 0.011 && abs(aim[1] - y) < 0.011) { "direction $k: the arrow shows ${aim.toList()}" }
                if (y < -0.1) expect(rates[0] > 0.5) { "direction $k (up): the nose did not rise" }
                if (y > 0.1) expect(rates[0] < -0.5) { "direction $k (down): the nose did not drop" }
                if (x > 0.1) expect(rates[2] < -0.5) { "direction $k (right): no roll to the right" }
                if (x < -0.1) expect(rates[2] > 0.5) { "direction $k (left): no roll to the left" }
            }
        }
        run(env) {
            val w = WorldBuilder.sol()
            openSpace(w)
            cmd("throttle 0.2")
            frames(30)
            for ((name, xy) in listOf("0" to (0.0 to 0.0), "0.1-right" to (0.1 to 0.0), "0.5-up-right" to (0.354 to -0.354), "1.0-down-left" to (-0.707 to 0.707))) {
                cmd("stick ${f3(xy.first)} ${f3(xy.second)}")
                frames(6)
                log("arrow $name: ${field("aimArrow")} cursor ${field("cursor")}")
                shot("flight-arrow-$name")
            }
            // the head turned in the cockpit: the arrow stays on the flight direction's screen
            cmd("look 35 12")
            cmd("stick 0 -1")
            frames(20)
            shot("flight-arrow-look")
            cmd("look off")
            cmd("stick off")
        }
    }

    // ------------------------------------------------------------------ flight-alloc

    /**
     * One pass over every flight path with its instruments, [n] frames a phase: cruise towards 0.5 c
     * with the right thumb circling on the stick (the arrow), a boost in the external view, the
     * autopilot on a locked Luna (AUTO and its status line), a push-over at 200 m/s 3 km above Earth
     * (the redout overlay, the attitude indicator, the near band), the gear coming down 40 m up, the
     * star's heat zone over 80 % (the overheat line, scooping). [meter] records only [Game.drawFrame];
     * debug commands are processed in the frame after them, so every phase settles for two frames
     * before it is measured.
     */
    private fun GameDriver.allocPass(w: StarSystem, n: Int, meter: PerfMeter?) {
        val thumb = pointer(1)
        val cx = 0.72f * this.w
        val cy = 0.62f * h
        val radius = 0.08f * h
        fun measure(frames: Int, circle: Boolean) {
            frames(2)
            if (circle) thumb.down(cx + radius, cy)
            // the desktop platform's log lines (haptics) are the harness's allocations, not the game's
            platform.quiet = true
            observer = meter
            for (i in 0 until frames) {
                if (circle) {
                    val a = i * 0.05f
                    thumb.move(cx + kotlin.math.cos(a) * radius, cy + kotlin.math.sin(a) * radius)
                }
                frame()
                if (meter != null && meter.count > 0 && meter.allocBytes[meter.count - 1] > 0) {
                    log("allocated ${meter.allocBytes[meter.count - 1]} B: ${game.debugInfo}")
                }
            }
            observer = null
            platform.quiet = false
            if (circle) thumb.up()
        }
        openSpace(w)
        cmd("view cockpit")
        cmd("throttle 1")
        measure(n, true)
        openSpace(w)
        cmd("view external")
        cmd("throttle ${f3(num("top") / 1000.0)}")
        cmd("speed ${f1(num("top"))}")
        frames(2)
        cmd("boost")
        measure(n, true)
        cmd("view cockpit")
        flying()
        cmd("near earth 5e7")
        cmd("lock-body luna")
        cmd("autopilot")
        measure(n, false) // the autopilot steers: no thumb
        expect(field("apOn") == "true") { "the autopilot is not flying" }
        cmd("autopilot") // off again (P)
        cmd("near earth 3000")
        cmd("throttle 0.2")
        cmd("speed 200")
        cmd("stick 0 0.9")
        // the push-over: the FA band kills the climb with doubled vertical thrust (-Gz, redout)
        measure(n, false)
        expect(num("redout") > 0.5 && num("dark") > 0.0) { "no redout in the push-over: ${field("redout")}, ${field("dark")}" }
        cmd("stick off")
        cmd("near earth 40")
        measure(n / 2, false) // the gear comes down
        expect(num("gear") > 0.0) { "the gear did not come down 40 m up" }
        cmd("near sol 3e7")
        cmd("heat 0.9")
        cmd("throttle 1")
        measure(n, true)
        expect(field("scooping") == "true" && num("heat") > 0.8) { "not scooping over 80 % heat" }
    }

    fun alloc(env: ScenarioEnv) {
        val ea = java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments
        val noEa = "-XX:-DoEscapeAnalysis" in ea
        env.report.note(
            if (noEa) "escape analysis disabled: allocation counts approximate ART"
            else "WARNING: escape analysis on (HotSpot may hide allocations ART makes); run ./gradlew :desktop:run -Pnoea --args=\"--scenario flight-alloc\"",
        )
        noop(env) {
            val w = WorldBuilder.sol()
            flying()
            // two passes load and compile every path (the same phases and lengths: a path the JIT has not
            // seen deoptimises, and HotSpot's deoptimisation allocates); the third one is measured
            val n = 240
            allocPass(w, n, null)
            allocPass(w, n, null)
            val meter = PerfMeter(n * 6) { 0 }
            meter.game = game
            allocPass(w, n, meter)
            meter.summary("flight paths on the no-op GL", 0, report)
            var allocating = 0
            var total = 0L
            for (i in 0 until meter.count) {
                if (meter.allocBytes[i] > 0) allocating++
                total += maxOf(0L, meter.allocBytes[i])
            }
            report.note("flight-alloc: ${meter.count} measured frames, $allocating allocated ($total B)")
            // HotSpot installs compiled code and deoptimises on its own schedule, and such a transition
            // now and then allocates once in a frame (48 B to 1.4 KB at a random frame of a random
            // phase; never with -Xint; the perf scenario's warm-up shows the same). A flight path that
            // allocates allocates every frame, or every few: at most two isolated frames pass
            if (noEa) expect(allocating <= 2) { "$allocating of ${meter.count} flight frames allocated ($total B)" }
        }
    }

    // ------------------------------------------------------------------ gear

    fun gear(env: ScenarioEnv) = run(env) {
        flying()
        cmd("view external")
        // the external camera swung round to the left side, a little below the hull: the legs swing
        // down across the view (straight from below they would swing towards the camera)
        cmd("look 90 20")
        cmd("throttle 0")
        cmd("gear up")
        frames(200)
        expect(field("stowed") == "true") { "the gear is not stowed to start with" }
        fun state() = "gear ${field("gear")}, legs ${field("legT")}, doors ${field("doorT")}, stowed ${field("stowed")}"
        // down: the doors open (0.6 s), then the legs swing out (1.6 s)
        cmd("gear down")
        val t0 = frame
        shot("gear-down-0.0s")
        for ((sec, check) in listOf<Pair<Double, () -> Boolean>>(
            0.6 to { num("doorT") < 0.02 && num("legT") > 0.98 },
            1.6 to { abs(num("legT") - 0.375) < 0.02 },
            2.2 to { num("legT") < 0.001 && num("gear") > 0.999 },
        )) {
            // (+2 frames at the end: float steps may finish a phase one frame late)
            while (frame - t0 < (sec * 60).toLong() + (if (sec > 2.0) 2 else 0)) frame()
            log("down %.1f s: %s".format(sec, state()))
            expect(check()) { "down %.1f s: %s".format(sec, state()) }
            shot("gear-down-%.1fs".format(Locale.ROOT, sec))
        }
        // up: the legs fold (1.6 s), then the doors close (0.6 s) and the covers show
        cmd("gear up")
        val t1 = frame
        for ((sec, check) in listOf<Pair<Double, () -> Boolean>>(
            1.6 to { num("legT") > 0.98 && num("doorT") < 0.05 },
            2.2 to { field("stowed") == "true" },
        )) {
            while (frame - t1 < (sec * 60).toLong() + (if (sec > 2.0) 2 else 0)) frame()
            log("up %.1f s: %s".format(sec, state()))
            expect(check()) { "up %.1f s: %s".format(sec, state()) }
            shot("gear-up-%.1fs".format(Locale.ROOT, sec))
        }
        cmd("gear auto")
        cmd("look off")
    }

    // ------------------------------------------------------------------ flight-dock-load

    /**
     * A boost and a hard pull at TopSpeed inside MERIDIAN's lock, then K: the G load of [pull] frames
     * of full stick (the boost still burning) before the docking computer takes the ship.
     */
    private fun GameDriver.pullThenDock(pull: Int) {
        flying()
        cmd("view cockpit")
        cmd("throttle 1")
        frames(180)
        cmd("boost")
        frames(2)
        cmd("stick 1 -1")
        frames(pull)
    }

    /**
     * The docking computer flies without the last flown frame's load (review fix, C23's idle thrust
     * step): after the pull the G meter falls from the first docking frame with its own smoothing
     * (τ 0.12 s: 1.6 % left after 0.5 s), reads zero through the auto-dock and docked, and the boost's
     * cooldown runs down meanwhile. Before, the meter held the pull's value all the way in.
     */
    fun dockLoad(env: ScenarioEnv) {
        noop(env) {
            pullThenDock(30)
            val pulled = num("g")
            val cooldown = num("cooldown")
            expect(pulled > 2.0) { "the pull did not load the pilot: $pulled g" }
            cmd("stick off")
            cmd("dock")
            frames(30) // the commands run in the first of these frames: 0.5 s of docking computer
            expect(field("docking") == "true") { "the docking computer did not take the ship" }
            val g30 = num("g")
            frames(60)
            val g90 = num("g")
            val cooldown90 = num("cooldown")
            val row = "dock after a %s g pull: %s g 0.5 s in, %s g 1.5 s in (dark %s); boost cooldown %s s -> %s s"
                .format(f2(pulled), f2(g30), f2(g90), field("dark"), f2(cooldown), f2(cooldown90))
            log(row)
            report.note(row)
            expect(g30 <= pulled * 0.03 + 0.01) { "0.5 s into the auto-dock the G meter reads $g30 (was $pulled)" }
            // the overlay fades with its own 0.35 s
            expect(g90 == 0.0 && num("dark") < 0.03) { "1.5 s into the auto-dock: $g90 g, darkness ${field("dark")}" }
            expect(abs(cooldown90 - (cooldown - 1.5)) < 0.03) { "the boost cooldown stood still while docking: $cooldown -> $cooldown90" }
            frames(30)
            expect(field("boosting") == "false") { "the 2 s burn did not end in the auto-dock" }
            var maxG = 0.0
            var n = 0
            while (field("docked") != "true" && n < 60 * 90) {
                frame()
                maxG = maxOf(maxG, num("g"))
                n++
            }
            report.note("docked %s s later, at most %s g on the way, darkness %s".format(f1(n / 60.0), f2(maxG), field("dark")))
            expect(field("docked") == "true") { "never docked" }
            expect(maxG == 0.0 && num("dark") == 0.0) { "the docking computer's flight loaded the pilot: $maxG g, darkness ${field("dark")}" }
        }
        run(half(env)) {
            pullThenDock(30)
            log("pull: ${field("g")} g, strain ${field("strain")}, dark ${field("dark")}")
            shot("flight-dock-pull")
            cmd("stick off")
            cmd("dock")
            frames(90)
            log("auto-dock 1.5 s: ${field("g")} g, dark ${field("dark")}, docking ${field("docking")}")
            expect(num("g") < 0.5) { "the HUD would show a G load during the auto-dock" }
            shot("flight-dock-auto")
        }
    }

    // ------------------------------------------------------------------ flight-sensitivity

    /**
     * SENSITIVITY at its lowest (0.5) with a real thumb on the touch stick (review fix): a drag past
     * the stick's rim is the whole cursor (the flight arrow's full length) and takes the ship back
     * from the autopilot (MANUAL OVERRIDE) and from the docking computer, as at every setting. Before,
     * 0.5 halved the deflection: never past the 0.5 override, 19 % of the turn rate at most.
     */
    fun sensitivity(env: ScenarioEnv) = run(half(env)) {
        game.settings.sensitivity = 0.5f
        flying()
        cmd("view cockpit")
        cmd("throttle 0.2")
        cmd("lock-body luna")
        cmd("autopilot")
        frames(10)
        expect(field("apOn") == "true") { "AUTO did not engage on a locked Luna" }
        val thumb = pointer(1)
        val cx = 0.72f * w
        val cy = 0.62f * h
        fun push(dx: Float, dy: Float) {
            thumb.down(cx, cy)
            frames(2)
            thumb.dragTo(cx + dx * h, cy + dy * h, 10)
            frames(3)
        }
        // straight up past the rim (the floating base follows the thumb)
        push(0f, -0.25f)
        val aim = vec("aimArrow")
        val m = sqrt(aim[0] * aim[0] + aim[1] * aim[1])
        log("sensitivity 0.5, full stick: arrow ${field("aimArrow")}, autopilot ${field("ap")}, rates ${field("rates")}")
        expect(abs(m - 1.0) < 0.01 && aim[1] < -0.99) { "the full stick is ${field("aimArrow")} at sensitivity 0.5" }
        expect(field("ap") == "MANUAL_OVERRIDE" && field("apOn") == "false") { "the full stick did not take the ship back from the autopilot" }
        shot("flight-sens-full")
        thumb.up()
        frames(30)
        // the docking computer hands over the same way
        cmd("dock")
        frames(20)
        expect(field("docking") == "true") { "the docking computer did not take the ship" }
        push(-0.25f, 0f)
        log("sensitivity 0.5, full stick in the auto-dock: docking ${field("docking")}, message '${game.messageLine}'")
        expect(field("docking") == "false" && game.messageLine == "DOCKING CANCELLED") { "the full stick did not cancel the auto-dock" }
        thumb.up()
        frames(5)
        report.note("sensitivity 0.5: the full stick is the whole cursor (|aim| ${f3(m)}), MANUAL OVERRIDE and DOCKING CANCELLED")
    }
}
