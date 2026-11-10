package com.starwake.desktop

import com.starwake.core.Game
import com.starwake.core.game.Session
import com.starwake.core.game.StarSystem
import com.starwake.core.world.WorldBuilder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Harness scenarios of the Sol world (03-world.md, port order 1-3) in the real [Game]:
 *  - "sol-start": the docked start at MERIDIAN in the station's frame while Earth carries it, the
 *    launch into Earth's frame, the map, a jump of the save-game clock;
 *  - "sol-views": Earth, Luna, Mars, Jupiter and Saturn from the cockpit, with their markers and a
 *    lock; Jupiter's cloud-top barrier and its LANDING BLOCKED prompt;
 *  - "sol-trip": MERIDIAN to Mars at full throttle, timed to Mars's mass-shadow edge and to 60 km;
 *  - "sol-moon": GALILEAN REFINERY to Io at full throttle: Io, not Jupiter, limits the speed;
 *  - "sol-docking": the real auto-dock at all six Sol stations (no-op GL), then each station on
 *    approach (GL);
 *  - "ring-debris" and "orbits" (03-world.md 9.5): Saturn's ring from inside, the B ring against the
 *    Cassini division; bodies turning on the save-game clock with a parked ship riding along.
 * The timed flights run on the no-op GL (the same game logic at 60 Hz, nothing drawn) and their
 * shots come from a replay of the same deterministic script. The game's world is a pure function of its save-game clock, so the scripts rebuild Sol at the
 * clock the debug read-out shows to know where the bodies are.
 */
object SolScenarios {
    /** The trip marks' approach: 12 radii from the target's centre. */
    private const val APPROACH_RADII = 12.0

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

    /** The same scripts without rendering ([NoopGl]): long timed flights at the cost of the game logic only. */
    private fun noop(env: ScenarioEnv, script: GameDriver.() -> Unit) {
        val platform = DesktopPlatform(env.opts.assetsDir, env.opts.settings)
        val d = GameDriver(Game(platform), NoopGl(), null, platform, env.report, env.opts, env.opts.insets)
        d.start()
        d.script()
    }

    /** [env] at half the resolution (same layout in dp): a long flight's shots at a quarter of the fill cost. */
    private fun half(env: ScenarioEnv): ScenarioEnv {
        val o = env.opts
        val opts = Options(
            o.outDir, o.assetsDir, o.width / 2, o.height / 2, o.density / 2f, o.scenarios, o.glDebug, o.msaa,
            FloatArray(4) { o.insets[it] / 2f }, o.perfFrames, o.settings,
        )
        return ScenarioEnv(opts, env.egl, env.report)
    }

    private fun GameDriver.fail(msg: String): Nothing = throw IllegalStateException("$msg\n  ${game.debugInfo}")

    /** Sol at the game's clock. */
    private fun GameDriver.world(): StarSystem = WorldBuilder.sol().also { it.snapTo(num("clock")) }

    private fun dist(a: DoubleArray, b: com.starwake.core.math.DVec3): Double {
        val dx = a[0] - b.x
        val dy = a[1] - b.y
        val dz = a[2] - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun f4(v: Double) = String.format(Locale.ROOT, "%.4f", v)

    /**
     * Puts the ship [radii] radii from [body]'s centre, [sunDeg] degrees round from its sunward
     * side and [elevDeg] above the orbital plane, nose on the body (a teleport: at rest, in the
     * body's frame).
     */
    private fun GameDriver.viewOf(body: String, radii: Double, sunDeg: Double, elevDeg: Double) {
        val w = world()
        val b = w.bodies[w.bodyIndex(body)]
        val sun = w.bodies[w.star].pos
        var sx = sun.x - b.pos.x
        var sz = sun.z - b.pos.z
        val sn = sqrt(sx * sx + sz * sz)
        sx /= sn
        sz /= sn
        // round from the sun direction in the XZ plane, then up
        val a = Math.toRadians(sunDeg)
        val e = Math.toRadians(elevDeg)
        val hx = sx * cos(a) - sz * sin(a)
        val hz = sx * sin(a) + sz * cos(a)
        val dx = hx * cos(e)
        val dy = sin(e)
        val dz = hz * cos(e)
        val r = b.radius * radii
        cmd("teleport ${f(b.pos.x + dx * r)} ${f(b.pos.y + dy * r)} ${f(b.pos.z + dz * r)} ${f4(-dx)} ${f4(-dy)} ${f4(-dz)}")
    }

    /**
     * Puts the ship [alt] metres over [from]'s surface where [to] stands on the horizon, on the
     * day side as far as possible, nose on [to] (an "Earthrise": the ground below, [to] above it).
     */
    private fun GameDriver.horizonView(from: String, alt: Double, to: String) {
        val w = world()
        val a = w.bodies[w.bodyIndex(from)]
        val b = w.bodies[w.bodyIndex(to)]
        val sun = w.bodies[w.star].pos
        var ux = b.pos.x - a.pos.x
        var uy = b.pos.y - a.pos.y
        var uz = b.pos.z - a.pos.z
        val un = sqrt(ux * ux + uy * uy + uz * uz)
        ux /= un; uy /= un; uz /= un
        // the sun's direction with its part along u removed: the lit side, square to the target
        var nx = sun.x - a.pos.x
        var ny = sun.y - a.pos.y
        var nz = sun.z - a.pos.z
        val k = nx * ux + ny * uy + nz * uz
        nx -= k * ux; ny -= k * uy; nz -= k * uz
        var nn = sqrt(nx * nx + ny * ny + nz * nz)
        if (nn < 1e-6 * un) {
            nx = -uz; ny = 0.0; nz = ux
            nn = sqrt(nx * nx + nz * nz)
        }
        nx /= nn; ny /= nn; nz /= nn
        val r = a.radius + alt
        // up = the local vertical, so the horizon lies level across the view
        cmd("teleport ${f(a.pos.x + nx * r)} ${f(a.pos.y + ny * r)} ${f(a.pos.z + nz * r)} ${f4(ux)} ${f4(uy)} ${f4(uz)} ${f4(nx)} ${f4(ny)} ${f4(nz)}")
    }

    // ------------------------------------------------------------------ sol-start

    fun start(env: ScenarioEnv) {
        // the checks, on the no-op GL (the launch to Earth's frame takes a few hundred frames)
        noop(env) {
            cmd("launch")
            frames(30)
            if (field("docked") != "true" || field("station") != "0") fail("a start docks at MERIDIAN (station 0)")
            if (field("ref") != "MERIDIAN_STATION") fail("docked, the ship rides in MERIDIAN's frame: ${field("ref")}")
            if (field("body") != "Earth") fail("MERIDIAN hangs over Earth: ${field("body")}")
            val alt = num("alt")
            if (abs(alt - 13.44e6) > 0.05e6) fail("MERIDIAN is 4,480 studs (13.44 Mm) above Earth: $alt m")
            // the world turns while docked: Earth carries the station and the ship, the slot holds still
            val p0 = vec("pos")
            val q0 = vec("fpos")
            val c0 = num("clock")
            frames(60)
            val p1 = vec("pos")
            val q1 = vec("fpos")
            val c1 = num("clock")
            val moved = sqrt((p1[0] - p0[0]) * (p1[0] - p0[0]) + (p1[1] - p0[1]) * (p1[1] - p0[1]) + (p1[2] - p0[2]) * (p1[2] - p0[2]))
            val slot = sqrt((q1[0] - q0[0]) * (q1[0] - q0[0]) + (q1[1] - q0[1]) * (q1[1] - q0[1]) + (q1[2] - q0[2]) * (q1[2] - q0[2]))
            val earthSpeed = 3.72 * 3000.0
            report.note("docked 1 s: the clock ran %.3f s, the ship moved %.0f m with Earth (%.0f m/s), %.3f m in MERIDIAN's frame".format(c1 - c0, moved, earthSpeed, slot))
            if (abs(c1 - c0 - 1.0) > 0.02) fail("the save-game clock did not run 1 s in 60 frames: ${c1 - c0}")
            if (abs(moved - earthSpeed) > earthSpeed * 0.05) fail("Earth did not carry the docked ship at 11.16 km/s: $moved m in 1 s")
            if (slot > 0.05) fail("the ship drifted in its slot: $slot m")
            // LAUNCH: out of the slot, out of MERIDIAN's frame (2.2 km, x1.25) into Earth's
            cmd("undock")
            frames(30)
            cmd("throttle 1")
            var n = 30
            var lastSpeed = 0.0
            while (field("ref") != "EARTH") {
                frame()
                lastSpeed = num("speed")
                if (++n > 60 * 40) fail("never left MERIDIAN's frame for Earth's")
            }
            val fp = vec("fpos")
            report.note(
                "launched: in Earth's frame %.1f s after LAUNCH, %.0f km from Earth's centre, at %.0f m/s (relative)".format(
                    n / 60.0, sqrt(fp[0] * fp[0] + fp[1] * fp[1] + fp[2] * fp[2]) / 1000.0, lastSpeed,
                ),
            )
            // the switch keeps the relative speed (no jump by Earth's 11 km/s)
            frame()
            if (abs(num("speed") - lastSpeed) > 50.0) fail("the speed jumped at the frame switch: $lastSpeed -> ${num("speed")}")
            // a day later on the save-game clock: Earth has moved along its orbit, the ship with it
            cmd("throttle 0")
            frames(120)
            val before = vec("fpos")
            val pa = vec("pos")
            cmd("clock 86400")
            frame()
            val after = vec("fpos")
            val pb = vec("pos")
            val keep = sqrt((after[0] - before[0]) * (after[0] - before[0]) + (after[1] - before[1]) * (after[1] - before[1]) + (after[2] - before[2]) * (after[2] - before[2]))
            val jump = sqrt((pb[0] - pa[0]) * (pb[0] - pa[0]) + (pb[1] - pa[1]) * (pb[1] - pa[1]) + (pb[2] - pa[2]) * (pb[2] - pa[2]))
            report.note("clock 86400: Earth carried the ship %.0f Mm along its orbit, %.1f m moved in Earth's frame (one frame of flight)".format(jump / 1e6, keep))
            if (abs(num("clock") - 86400.0) > 0.1) fail("the clock did not jump to a day")
            if (keep > num("speed") / 60.0 * 2 + 1.0) fail("the clock jump moved the ship in its frame: $keep m")
            if (jump < 0.5e9) fail("Earth did not move along its orbit: $jump m")
            if (field("ref") != "EARTH") fail("the clock jump lost Earth's frame: ${field("ref")}")
        }
        // the shots
        run(env) {
            cmd("launch")
            frames(20)
            shot("sol-docked-meridian")
            cmd("undock")
            frames(150)
            shot("sol-launched")
            val earthIndex = WorldBuilder.sol().bodyIndex("Earth")
            cmd("lock-body earth")
            frames(10)
            if (field("lock") != "${Session.LOCK_BODY}:$earthIndex") fail("LOCK did not hold Earth: ${field("lock")}")
            shot("sol-lock-earth")
            cmd("lock clear")
            cmd("map")
            frames(30)
            if (game.stateName != "map") fail("the map did not open")
            shot("sol-map")
            back()
            frames(2)
            cmd("clock 86400")
            frames(10)
            shot("sol-a-day-later")
        }
    }

    // ------------------------------------------------------------------ sol-views

    fun views(env: ScenarioEnv) = run(env) {
        cmd("launch space")
        frames(10)
        cmd("throttle 0")
        // gibbous views from a few radii out, above the orbital plane
        for ((body, radii, sun, elev) in listOf(
            Quad("Earth", 3.0, 60.0, 10.0),
            Quad("Luna", 3.0, 60.0, 10.0),
            Quad("Mars", 3.0, 60.0, 10.0),
            Quad("Jupiter", 2.6, 50.0, 8.0),
            Quad("Saturn", 4.5, 40.0, 22.0),
        )) {
            viewOf(body, radii, sun, elev)
            frames(3)
            // the nearest body is the one at the smallest altitude (Roblox `getState`): from 3 radii
            // a small moon inside them (Phobos round Mars) can be nearer than the planet
            val w = world()
            val near = w.bodyIndex(field("body") ?: "")
            if (near < 0 || (w.bodies[near].name != body && w.bodies[near].parent != w.bodyIndex(body))) {
                fail("the nearest body is ${field("body")}, not $body or one of its moons")
            }
            cmd("lock-body ${body.lowercase()}")
            frames(20)
            shot("sol-view-${body.lowercase()}")
            cmd("lock clear")
        }
        // Earth over Luna's horizon (1.9 degrees wide from there)
        horizonView("Luna", 20_000.0, "Earth")
        frames(20)
        if (field("body") != "Luna") fail("20 km over Luna, the nearest body is ${field("body")}")
        shot("sol-view-earth-from-luna")
        // Jupiter's barrier: no solid surface, the cloud tops stop the ship
        cmd("near jupiter 2000")
        frames(5)
        if (field("zone") != "LOW_ALTITUDE") fail("2 km over Jupiter is low altitude: ${field("zone")}")
        // no LAND pill over a giant (the HUD shows the blocked prompt); the L key answers with it
        if (field("action") != "-") fail("an action is offered over a gas giant: ${field("action")}")
        cmd("key LAND")
        frames(2)
        if (game.messageLine != Session.LANDING_BLOCKED_GAS) fail("L over Jupiter: '${game.messageLine}', expected '${Session.LANDING_BLOCKED_GAS}'")
        if (field("landing") != "false") fail("landing mode engaged over a gas giant")
        shot("sol-jupiter-landing-blocked")
        // dive at the cloud tops: the barrier holds the ship at the surface
        cmd("face jupiter")
        cmd("throttle 1")
        var minAlt = Double.MAX_VALUE
        var blocked = false
        for (i in 0 until 60 * 12) {
            frame()
            if (i % 30 == 0) game.debugCommand("face jupiter")
            minAlt = minOf(minAlt, num("alt"))
            if (game.messageLine == Session.LANDING_BLOCKED_GAS) blocked = true
        }
        report.note("dive at Jupiter: lowest %.1f m above the cloud tops (gear clearance included), barrier message %b".format(minAlt, blocked))
        if (minAlt < -5.0) fail("the ship sank through Jupiter's cloud tops: $minAlt m")
        if (minAlt > 200.0) fail("the dive never reached the cloud tops: $minAlt m")
        if (!blocked) fail("no LANDING BLOCKED message at Jupiter's barrier")
        cmd("throttle 0")
        frames(5)
        shot("sol-jupiter-barrier")
    }

    private data class Quad(val body: String, val radii: Double, val sun: Double, val elev: Double)

    // ------------------------------------------------------------------ sol-trip

    /** Times of the trip's shots, s after the throttle went up. */
    private val TRIP_MARKS = doubleArrayOf(3.0, 12.0, 25.0, 40.0, 55.0)

    /** Launch from MERIDIAN, nose and lock on Mars, full throttle; returns the frame the trip starts on. */
    private fun GameDriver.tripStart(marsIndex: Int): Long {
        cmd("launch")
        frames(10)
        if (field("station") != "0") fail("not docked at MERIDIAN")
        cmd("undock")
        frames(90)
        cmd("face mars")
        cmd("lock-body mars")
        cmd("throttle 1")
        frame()
        if (field("lock") != "${Session.LOCK_BODY}:$marsIndex") fail("Mars is not locked: ${field("lock")}")
        return frame
    }

    /** One frame of the trip: the pilot keeps the nose on Mars (twice a second). */
    private fun GameDriver.tripFrame(start: Long) {
        if ((frame - start) % 30 == 0L) game.debugCommand("face mars")
        frame()
    }

    fun trip(env: ScenarioEnv) {
        val sol = WorldBuilder.sol()
        val marsIndex = sol.bodyIndex("Mars")
        val mars0 = sol.bodies[marsIndex]
        val edge = mars0.collisionRadius * (1.0 + mars0.massShadowFactor)
        var arrival = -1L
        // (frame, shot name) of the moments to draw: the timed marks, 12 radii, the shadow edge
        val marks = ArrayList<Pair<Long, String>>()
        // the timed flight (no-op GL: the game logic at 60 Hz, nothing drawn)
        noop(env) {
            val start = tripStart(marsIndex)
            val d0 = dist(vec("pos"), world().bodies[marsIndex].pos) - mars0.radius
            report.note("MERIDIAN -> Mars: %,.0f km to Mars's surface at clock %.1f s".format(d0 / 1000.0, num("clock")))
            var tEdge = -1.0
            var t12 = -1.0
            var tLow = -1.0
            var peak = 0.0
            var minEarth = Double.MAX_VALUE
            var mark = 0
            while (frame - start < 60 * 150) {
                tripFrame(start)
                val t = (frame - start) / 60.0
                val d = dist(vec("pos"), world().bodies[marsIndex].pos)
                peak = maxOf(peak, num("speed"))
                if (field("body") == "Earth") minEarth = minOf(minEarth, num("alt"))
                if (mark < TRIP_MARKS.size && t >= TRIP_MARKS[mark]) {
                    marks += frame to "sol-trip-%02.0fs".format(TRIP_MARKS[mark])
                    log("t %4.1f s: %s".format(t, game.debugInfo.substringAfter(" alt=").substringBefore(" throttle=") + " zone=" + field("zone") + " body=" + field("body") + " limit=" + field("limit")))
                    mark++
                }
                if (t12 < 0 && d < mars0.radius * APPROACH_RADII) {
                    t12 = t
                    marks += frame to "sol-trip-mars-12r"
                }
                if (tEdge < 0 && d < edge) {
                    tEdge = t
                    marks += frame to "sol-trip-mars-shadow-edge"
                }
                if (d - mars0.radius < Session.LAND_RANGE) {
                    tLow = t
                    arrival = frame
                    break
                }
            }
            if (tLow < 0) fail("never reached Mars in 150 s")
            report.note(
                "MERIDIAN -> Mars: mass-shadow edge after %.1f s, 12 radii after %.1f s, 60 km after %.1f s; peak %.1f Mm/s (%.2f c); closest to Earth on the way %,.0f km"
                    .format(tEdge, t12, tLow, peak / 1e6, peak / 2.998e8, minEarth / 1000.0),
            )
            if (peak > com.starwake.core.flight.FlightLaw.HALF_LIGHT * 1.0001) fail("faster than 0.5 c: $peak m/s")
            // the flight port's Roblox law: out of the slot the station holds the ship at 140 m/s
            // (2.2 km) and 260 m/s (6 km lock), and at clock 0 Mars lies behind Earth: the straight
            // line crosses Earth's mass shadow (MERIDIAN orbits inside it) at its 0.15 R floor,
            // 954 km/s. DECISIONS 2's minute is planet to planet at the closest approach (TripTimeTest)
            if (tEdge > 130.0) fail("MERIDIAN -> Mars took $tEdge s to the mass-shadow edge (about two minutes expected)")
            if (field("body") != "Mars") fail("arrived, but the nearest body is ${field("body")}")
        }
        // the same flight drawn at half resolution (the runs are deterministic): shots at the marks
        run(half(env)) {
            val start = tripStart(marsIndex)
            marks.sortBy { it.first }
            var k = 0
            while (frame < arrival + 30) {
                tripFrame(start)
                while (k < marks.size && frame == marks[k].first) {
                    shot(marks[k].second)
                    k++
                }
            }
            shot("sol-trip-mars-arrival")
            log("replay: ${game.debugInfo.substringAfter(" alt=").substringBefore(" throttle=")} body=${field("body")}")
        }
    }

    // ------------------------------------------------------------------ sol-moon

    /** Times of the moon approach's shots, s after the throttle went up. */
    private val MOON_MARKS = doubleArrayOf(5.0, 30.0, 50.0)

    /** Docked at GALILEAN REFINERY (from the docked start), LAUNCH, nose and lock on Io; returns the frame the approach starts on. */
    private fun GameDriver.moonStart(ioIndex: Int, galilean: Int): Long {
        cmd("launch")
        frames(10)
        cmd("docked galilean")
        frames(2)
        if (field("docking") != "true") fail("the slide into GALILEAN REFINERY did not start")
        var n = 0
        while (game.stateName != "docked") {
            frame()
            if (++n > 60 * 60) fail("never docked at GALILEAN REFINERY")
        }
        if (field("station") != "$galilean") fail("not docked at GALILEAN REFINERY")
        cmd("undock")
        frames(90)
        // out of the station's zones on the launch axis first: the straight line to Io may cross
        // the refinery, and its hull crashes a ship above 45 m/s (the flight port, 02 5.7)
        cmd("throttle 1")
        frame()
        var out = 0
        while (field("zone") == "STATION_ZONE") {
            frame()
            if (++out > 60 * 60) fail("still in GALILEAN REFINERY's zone after a minute")
        }
        cmd("face io")
        cmd("lock-body io")
        cmd("throttle 1")
        frame()
        if (field("lock") != "${Session.LOCK_BODY}:$ioIndex") fail("Io is not locked: ${field("lock")}")
        return frame
    }

    /** One frame of the approach: the pilot keeps the nose on Io (twice a second). */
    private fun GameDriver.moonFrame(start: Long) {
        if ((frame - start) % 30 == 0L) game.debugCommand("face io")
        frame()
    }

    /**
     * GALILEAN REFINERY to Io at full throttle. The limit is the minimum over all bodies, not the
     * nearest body's alone (`ShipGravity:getState`): once Io is the nearest body (the smallest
     * altitude, Roblox's rule) Jupiter's mass shadow still limits the speed for a while, then Io
     * brings the ship to its surface at landing speeds instead of Mm/s.
     */
    fun moon(env: ScenarioEnv) {
        val sol = WorldBuilder.sol()
        val ioIndex = sol.bodyIndex("Io")
        val galilean = sol.stationIndex("SOL-GALILEAN")
        val io0 = sol.bodies[ioIndex]
        var arrival = -1L
        val marks = ArrayList<Pair<Long, String>>()
        noop(env) {
            val start = moonStart(ioIndex, galilean)
            val d0 = dist(vec("pos"), world().bodies[ioIndex].pos) - io0.radius
            report.note("GALILEAN REFINERY -> Io: %,.0f km to Io's surface at clock %.1f s".format(d0 / 1000.0, num("clock")))
            var t12 = -1.0
            var tLow = -1.0
            var t1km = -1.0
            var v1km = 0.0
            var peak = 0.0
            var jupiterOverIo = 0.0
            var mark = 0
            while (frame - start < 60 * 150) {
                val before = num("speed")
                moonFrame(start)
                val t = (frame - start) / 60.0
                val alt = dist(vec("pos"), world().bodies[ioIndex].pos) - io0.radius
                peak = maxOf(peak, num("speed"))
                // Io is the nearest body (the smallest altitude), Jupiter's shadow sets the limit
                if (field("body") == "Io" && field("limiter") == "Jupiter") jupiterOverIo += 1.0 / 60.0
                if (mark < MOON_MARKS.size && t >= MOON_MARKS[mark]) {
                    marks += frame to "sol-moon-%02.0fs".format(MOON_MARKS[mark])
                    log("t %4.1f s: Io %,.0f km below, speed %s m/s, limit %s, nearest %s, limiter %s".format(t, alt / 1000.0, field("speed"), field("limit"), field("body"), field("limiter")))
                    mark++
                }
                if (t12 < 0 && alt + io0.radius < io0.radius * APPROACH_RADII) {
                    t12 = t
                    marks += frame to "sol-moon-io-12r"
                    // the zone names the constraint that limits the speed, never a departure from
                    // Jupiter: 12 radii out the ship is still in Jupiter's mass shadow, whose floor
                    // (0.15 R, 4.19 Mm/s in the port's law) can be below Io's approach; Io takes over
                    // closer in (and limits while Jupiter's shadow does, jupiterOverIo below)
                    val z = field("zone")
                    if (z != "APPROACH" && !(z == "GRAVITY_WELL" && field("limiter") == "Jupiter")) {
                        fail("12 radii from Io the zone is $z (limiter ${field("limiter")}), not APPROACH or Jupiter's GRAVITY WELL")
                    }
                }
                if (tLow < 0 && alt < Session.LAND_RANGE) {
                    tLow = t
                    marks += frame to "sol-moon-io-60km"
                }
                if (alt < 1000.0) {
                    t1km = t
                    v1km = maxOf(before, num("speed"))
                    arrival = frame
                    break
                }
            }
            if (t1km < 0) fail("never came within 1 km of Io in 150 s")
            report.note(
                "GALILEAN REFINERY -> Io: 12 radii after %.1f s, 60 km after %.1f s, 1 km after %.1f s at %.0f m/s; peak %.2f Mm/s; Jupiter set the limit for %.1f s while Io was the nearest body"
                    .format(t12, tLow, t1km, v1km, peak / 1e6, jupiterOverIo),
            )
            if (v1km > 400.0) fail("reached Io's surface too fast: %.0f m/s 1 km up".format(v1km))
            if (jupiterOverIo <= 0.0) fail("Jupiter never limited the speed while Io was the nearest body")
            if (field("body") != "Io") fail("arrived, but the nearest body is ${field("body")}")
        }
        // the same approach drawn at half resolution: shots at the marks and on arrival
        run(half(env)) {
            val start = moonStart(ioIndex, galilean)
            marks.sortBy { it.first }
            var k = 0
            while (frame < arrival + 30) {
                moonFrame(start)
                while (k < marks.size && frame == marks[k].first) {
                    shot(marks[k].second)
                    k++
                }
            }
            shot("sol-moon-io-arrival")
            log("replay: ${game.debugInfo.substringAfter(" alt=").substringBefore(" throttle=")} body=${field("body")} limiter=${field("limiter")}")
        }
    }

    // ------------------------------------------------------------------ sol-docking

    fun docking(env: ScenarioEnv) {
        val sol = WorldBuilder.sol()
        // the real auto-dock at every station, from 1.5 km behind it (no-op GL: no rendering cost)
        noop(env) {
            cmd("launch space")
            frames(5)
            for (i in sol.stations.indices) {
                val st = sol.stations[i]
                val frameName = st.name.replace(' ', '_')
                cmd("behind-station $i")
                frames(2)
                if (field("ref") != frameName) fail("1.5 km behind ${st.name} the ship is not in its frame: ${field("ref")}")
                cmd("dock $i")
                frames(1)
                if (field("docking") != "true") fail("auto-dock refused at ${st.name}")
                var n = 0
                var minHull = Double.MAX_VALUE
                while (game.stateName != "docked") {
                    frame()
                    minHull = minOf(minHull, num("hull"))
                    if (++n > 60 * 150) fail("never docked at ${st.name}")
                }
                frames(30)
                if (field("station") != "$i") fail("docked at station ${field("station")}, not ${st.name}")
                if (field("ref") != frameName) fail("docked at ${st.name}, not in its frame: ${field("ref")}")
                if (minHull <= 0.0) fail("the route into ${st.name} touched the hull (gap $minHull m)")
                if (num("hull") <= 0.0) fail("parked inside ${st.name}'s walls")
                // launch: out of the slot and clear of the hull
                game.launchFromStation()
                var launchMin = Double.MAX_VALUE
                for (k in 0 until 240) {
                    frame()
                    launchMin = minOf(launchMin, num("hull"))
                }
                if (field("docked") != "false") fail("still docked at ${st.name} after LAUNCH")
                if (launchMin <= 0.0) fail("the launch from ${st.name} touched the hull (gap $launchMin m)")
                report.note(
                    "%-20s (%s over %s, %s stand-in): docked after %5.1f s from 1.5 km behind, closest hull gap %.1f m; launched clear (gap >= %.1f m), frame %s"
                        .format(st.name, st.id, sol.bodies[st.parent].name, st.modelName, n / 60.0, minHull, launchMin, field("ref")),
                )
            }
        }
        // each station on its approach, its host in the sky (GL)
        run(env) {
            cmd("launch space")
            frames(5)
            cmd("view external")
            for (i in sol.stations.indices) {
                cmd("behind-station $i")
                frames(2)
                cmd("dock $i")
                frames(90)
                shot("sol-dock-${sol.stations[i].id.lowercase()}")
            }
            cmd("view cockpit")
        }
    }

    // ------------------------------------------------------------------ ring-debris

    /**
     * Puts the ship in Saturn's ring plane at f = radius / outer radius, nose along the ring (the
     * orbit direction), and sets it flying there at [speed] with Flight Assist off, so the
     * velocity holds.
     */
    private fun GameDriver.inSaturnsRing(f: Double, speed: Int) {
        val w = world()
        val s = w.bodies[w.bodyIndex("Saturn")]
        val n = s.ringNormal
        // ShipRingDebris's ring frame: x = right (helper X projected into the plane)
        val hx = if (abs(n.x) < 0.9f) 1.0 else 0.0
        val hz = 1.0 - hx
        val d = hx * n.x + hz * n.z
        var rx = hx - n.x * d
        var ry = -n.y * d
        var rz = hz - n.z * d
        val rl = sqrt(rx * rx + ry * ry + rz * rz)
        rx /= rl; ry /= rl; rz /= rl
        // along the ring: normal x radial
        val tx = n.y * rz - n.z * ry
        val ty = n.z * rx - n.x * rz
        val tz = n.x * ry - n.y * rx
        val r = f * s.ringOuter
        cmd("teleport ${f(s.pos.x + rx * r)} ${f(s.pos.y + ry * r)} ${f(s.pos.z + rz * r)} ${f4(tx)} ${f4(ty)} ${f4(tz)}")
        cmd("fa off")
        cmd("throttle 0")
        cmd("speed $speed")
    }

    fun ringDebris(env: ScenarioEnv) = run(half(env)) {
        cmd("launch space")
        frames(5)
        cmd("view external")
        val p = com.starwake.core.world.RingProfiles.values[com.starwake.core.world.RingProfiles.SATURN]
        val gapStep = (108..116).minBy { p[it] }
        val gapF = (gapStep + 0.5) / 128.0
        class Pass(val name: String, val f: Double) {
            var rocks = 0
            var maxRocks = 0
            var hits = 0
            var heat = 0.0
        }
        val passes = listOf(Pass("B ring", 0.75), Pass("Cassini division", gapF), Pass("f 0.62 (03 9.5's gap)", 0.62))
        for (pass in passes) {
            inSaturnsRing(pass.f, 300)
            frames(3)
            if (field("body") != "Saturn") fail("in Saturn's ring the nearest body is ${field("body")}")
            val h0 = num("rhits").toInt()
            val q0 = num("rheat")
            var sum = 0
            for (k in 0 until 600) {
                frame()
                val r = num("rocks").toInt()
                sum += r
                pass.maxRocks = maxOf(pass.maxRocks, r)
                if (k == 120) shot("ring-debris-${pass.name.substringBefore(' ').lowercase()}-%.3f".format(Locale.ROOT, pass.f).replace('.', '_'))
            }
            pass.rocks = sum / 600
            pass.hits = num("rhits").toInt() - h0
            pass.heat = num("rheat") - q0
            val density = com.starwake.core.world.RingProfiles.densityAt(p, pass.f)
            report.note(
                "%-24s f %.3f (profile %.2f): %3d rocks around the ship on average (max %d), %d hits and %.3f heat in 10 s at 300 m/s"
                    .format(pass.name, pass.f, density, pass.rocks, pass.maxRocks, pass.hits, pass.heat),
            )
        }
        val b = passes[0]
        val gap = passes[1]
        if (b.rocks < 100) fail("the B ring holds few rocks: ${b.rocks}")
        if (gap.rocks * 4 > b.rocks) fail("the Cassini division is not almost empty: ${gap.rocks} vs ${b.rocks} in the B ring")
        if (gap.hits > b.hits) fail("more hits in the gap than in the B ring")
        // the cockpit in the B ring, and leaving the ring plane hides the field
        inSaturnsRing(0.75, 60)
        cmd("view cockpit")
        frames(60)
        shot("ring-debris-cockpit")
        cmd("view external")
        cmd("teleport-low")
        frames(3)
        if (num("rocks") != 0.0) fail("rocks shown away from any ring: ${field("rocks")}")
        cmd("fa on")
        cmd("view cockpit")
        // a quit to the menu from inside the B ring: the menu scene draws none of the flight's rocks
        inSaturnsRing(0.75, 60)
        frames(3)
        val inRing = num("sceneRocks").toInt()
        if (inRing <= 0) fail("the scene draws no rocks in the B ring")
        cmd("menu")
        frames(3)
        report.note("quit to the menu from the B ring: the scene drew $inRing rocks in flight, ${field("sceneRocks")} in the menu")
        if (num("sceneRocks") != 0.0) fail("the menu still draws the last flight's ${field("sceneRocks")} rocks")
    }

    // ------------------------------------------------------------------ orbits

    /** The angle (degrees) between two vectors. */
    private fun angle(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double): Double {
        val c = (ax * bx + ay * by + az * bz) / (sqrt(ax * ax + ay * ay + az * az) * sqrt(bx * bx + by * by + bz * bz))
        return Math.toDegrees(kotlin.math.acos(c.coerceIn(-1.0, 1.0)))
    }

    fun orbits(env: ScenarioEnv) {
        // the numbers, on the no-op GL: a ship parked in a body's frame rides along a clock jump
        noop(env) {
            cmd("launch space")
            frames(5)
            fun check(body: String, parent: String, hours: Double) {
                cmd("near ${body.lowercase()} 5000")
                cmd("throttle 0")
                frames(2)
                if (field("ref") != body.uppercase()) fail("5 km over $body the ship is not in its frame: ${field("ref")}")
                val c0 = num("clock")
                val w0 = world()
                val b0 = w0.bodies[w0.bodyIndex(body)].pos
                val p0 = vec("pos")
                val off = doubleArrayOf(p0[0] - b0.x, p0[1] - b0.y, p0[2] - b0.z)
                cmd("clock ${f(c0 + hours * 3600.0)}")
                frame()
                val w1 = world()
                val b1 = w1.bodies[w1.bodyIndex(body)].pos
                val p1 = vec("pos")
                // the ship kept its place in the body's frame: the body is where the ship says
                val carried = sqrt((p1[0] - off[0] - b1.x).let { it * it } + (p1[1] - off[1] - b1.y).let { it * it } + (p1[2] - off[2] - b1.z).let { it * it })
                val par0 = w0.bodies[w0.bodyIndex(parent)].pos
                val par1 = w1.bodies[w1.bodyIndex(parent)].pos
                val turned = angle(b0.x - par0.x, b0.y - par0.y, b0.z - par0.z, b1.x - par1.x, b1.y - par1.y, b1.z - par1.z)
                val period = w0.bodies[w0.bodyIndex(body)].orbitPeriod
                val turns = hours * 3600.0 / period
                var expected = (turns - kotlin.math.floor(turns)) * 360.0
                if (expected > 180.0) expected = 360.0 - expected
                report.note(
                    "%-6s %5.1f h: %.3f turns of its %.2f h orbit, %.2f deg between its places round %s (expected %.2f); the ship stayed %.1f m from its spot in the frame"
                        .format(body, hours, turns, period / 3600.0, turned, parent, expected, carried),
                )
                if (abs(turned - expected) > 0.5) fail("$body turned $turned deg in $hours h, expected $expected")
                if (carried > 50.0) fail("the clock jump left the ship $carried m off its spot in $body's frame")
            }
            check("Phobos", "Mars", 6.0)
            check("Earth", "Sol", 4.0 * 24.0)
        }
        // the system map at t0, 6 h and 4 days on (GL)
        run(env) {
            cmd("launch space")
            frames(5)
            val c0 = num("clock")
            for ((label, dtHours) in listOf("t0" to 0.0, "6h" to 6.0, "4d" to 96.0)) {
                cmd("clock ${f(c0 + dtHours * 3600.0)}")
                cmd("map")
                frames(20)
                if (game.stateName != "map") fail("the map did not open")
                shot("orbits-map-$label")
                back()
                frames(2)
            }
        }
    }
}
