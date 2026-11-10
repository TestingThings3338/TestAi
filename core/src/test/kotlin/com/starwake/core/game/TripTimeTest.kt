package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.flight.FlightLaw
import com.starwake.core.input.InputFrame
import com.starwake.core.world.WorldBuilder
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trip times in Sol at S = 3000 with the Roblox flight model and the S-classified speed law
 * (02-flight.md 9.4, 9.5; DECISIONS 2, 3): the real session at full throttle, Flight Assist on,
 * the nose re-aimed at the target every half second as a pilot would. A trip "arrives" at the
 * target's mass-shadow edge (DECISIONS 2: about a minute, 52-72 s there for neighbours); the table
 * also gives the 12-radii approach, the 60 km mark and the landing (the near band brings the ship
 * to the surface at the minimum approach speed). Legs between planets start 50 m above the
 * departure planet at a conjunction (the line of closest approach). The table is written to
 * build/reports/trip-times.txt.
 */
class TripTimeTest {
    private val settings = Settings()
    private val host = RecordingHost()
    private val input = InputFrame()

    private class Trip(
        val label: String,
        val distance: Double,
        var shadow: Double = Double.NaN,
        var approach: Double = Double.NaN,
        var low: Double = Double.NaN,
        var ground: Double = Double.NaN,
        var groundSpeed: Double = Double.NaN,
        var peak: Double = 0.0,
        var minAlt: Double = Double.MAX_VALUE,
        var crashed: Boolean = false,
    ) {
        override fun toString() =
            "%-34s gap %8.0f Mm  shadow %6.1f s  12 R %6.1f s  60 km %6.1f s  ground %6.1f s at %5.1f m/s  peak %6.1f Mm/s (%.2f c)"
                .format(label, distance / 1e6, shadow, approach, low, ground, groundSpeed, peak / 1e6, peak / 2.998e8)
    }

    private fun dist(s: Session, i: Int): Double {
        val b = s.system.bodies[i].pos
        val dx = s.ship.pos.x - b.x
        val dy = s.ship.pos.y - b.y
        val dz = s.ship.pos.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The mass-shadow edge from the centre: the surface radius plus five (moons four) radii (the port's law). */
    private fun shadowEdge(s: Session, b: StarSystem.Body) = b.radius + s.gravity.shadowOf(b)

    /** Flies [s] at full throttle towards body [to] until it stands on the ground (or [maxSeconds]). */
    private fun fly(s: Session, to: Int, label: String, maxSeconds: Double = 300.0): Trip {
        val target = s.system.bodies[to]
        val trip = Trip(label, dist(s, to) - target.radius)
        val dt = 1f / 60f
        s.debugFace(to)
        s.setThrottle(1f)
        var n = 0
        while (n < maxSeconds * 60) {
            if (n % 30 == 0) s.debugFace(to)
            val before = s.ship.speed
            input.begin()
            s.frame(dt, input)
            n++
            val t = n / 60.0
            trip.peak = maxOf(trip.peak, s.ship.speed)
            val d = dist(s, to)
            trip.minAlt = minOf(trip.minAlt, d - target.radius)
            if (trip.shadow.isNaN() && d < shadowEdge(s, target)) trip.shadow = t
            if (trip.approach.isNaN() && d < target.radius * 12.0) trip.approach = t
            if (trip.low.isNaN() && d - target.radius < 60_000.0) trip.low = t
            if (s.crashes > 0) {
                trip.crashed = true
                break
            }
            // on the ground: the hull's probes rest on the surface and the ship has stopped sinking
            if (d - target.radius < s.spec.reach + 1.0) {
                trip.ground = t
                trip.groundSpeed = maxOf(before, s.ship.speed)
                break
            }
        }
        return trip
    }

    /** The clock (s) of the conjunction of bodies [a] and [b] nearest after 0: their least distance. */
    private fun conjunction(system: StarSystem, a: Int, b: Int): Double {
        val ta = system.bodies[a].orbitPeriod
        val tb = system.bodies[b].orbitPeriod
        val synodic = 1.0 / kotlin.math.abs(1.0 / ta - 1.0 / tb)
        var best = 0.0
        var bestD = Double.MAX_VALUE
        // coarse scan over one synodic period, then refine around the best sample
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

    /** 50 m above body [from] on the line to [to], nose on [to], at rest, at clock [clock]. */
    private fun legStart(from: Int, to: Int, clock: Double?): Session {
        val world = WorldBuilder.sol()
        val c = clock ?: conjunction(world, from, to)
        world.snapTo(c)
        val s = Session(world, settings, host, GearRig())
        s.startInSpace()
        val a = world.bodies[from]
        val b = world.bodies[to]
        val dx = b.pos.x - a.pos.x
        val dy = b.pos.y - a.pos.y
        val dz = b.pos.z - a.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val r = a.radius + 50.0 + s.spec.groundClearance
        s.teleport(a.pos.x + dx / d * r, a.pos.y + dy / d * r, a.pos.z + dz / d * r, dx.toFloat(), dy.toFloat(), dz.toFloat(), true)
        return s
    }

    @Test
    fun everyNeighbourIsAboutAMinuteAway() {
        val sol = WorldBuilder.sol()
        fun idx(name: String) = sol.bodyIndex(name)
        val legs = ArrayList<Trip>()
        // planet to planet at conjunction, 50 m above the departure surface (the B.3 legs)
        for ((a, b) in listOf("Earth" to "Venus", "Earth" to "Mars", "Mars" to "Jupiter", "Jupiter" to "Saturn", "Uranus" to "Neptune")) {
            legs += fly(legStart(idx(a), idx(b), null), idx(b), "$a -> $b (conjunction)")
        }
        legs += fly(legStart(idx("Earth"), idx("Luna"), 0.0), idx("Luna"), "Earth -> Luna (clock 0)")
        legs += fly(legStart(idx("Earth"), idx("Mars"), 0.0), idx("Mars"), "Earth -> Mars (clock 0)")
        // from the free-flight spawn off MERIDIAN at clock 0, straight at the target
        val fromHome = ArrayList<Trip>()
        for (name in listOf("Luna", "Mars", "Venus", "Jupiter")) {
            val world = WorldBuilder.sol()
            val s = Session(world, settings, host, GearRig())
            s.startInSpace()
            fromHome += fly(s, world.bodyIndex(name), "MERIDIAN -> $name (clock 0)")
        }

        val table = (legs + fromHome).joinToString("\n")
        println(table)
        File("build/reports").mkdirs()
        File("build/reports/trip-times.txt").writeText(table + "\n")
        for (t in legs + fromHome) {
            assertTrue(!t.shadow.isNaN() && !t.low.isNaN(), "never arrived: $t")
            assertTrue(t.peak <= FlightLaw.HALF_LIGHT * 1.0001, "faster than 0.5 c: $t")
            assertTrue(!t.crashed, "crashed on arrival: $t")
            // no tunnelling: the near band brings the ship down to the ground, never through it
            assertTrue(t.minAlt > -1.0, "went through the surface: $t")
        }
        // DECISIONS 2: neighbouring rocky planets about a minute apart at their closest, 52-72 s to
        // the mass-shadow edge (here the climb from 50 m above the departure ground included); the
        // giants' longer legs ride the 0.5 c cap for a while (02 9.4: 15-20 s longer)
        for (t in legs) {
            if (!t.label.endsWith("(conjunction)")) continue
            val giant = t.label.contains("Jupiter") || t.label.contains("Saturn") || t.label.contains("Neptune")
            if (giant) assertTrue(t.shadow in 60.0..130.0, "a giant's leg: $t")
            else assertTrue(t.shadow in 52.0..72.0, "not about a minute to the shadow edge: $t")
        }
        // Luna from Earth is within the minute too
        val luna = legs.first { it.label.startsWith("Earth -> Luna") }
        assertTrue(luna.shadow in 52.0..72.0, "not about a minute to Luna: $luna")
        // the same pair farther apart (Mars at clock 0, three times the closest gap) costs the extra
        // distance at the 0.5 c cap and nothing more
        val closest = legs.first { it.label == "Earth -> Mars (conjunction)" }
        val apart = legs.first { it.label == "Earth -> Mars (clock 0)" }
        val extra = (apart.distance - closest.distance) / FlightLaw.HALF_LIGHT
        assertTrue(kotlin.math.abs(apart.shadow - (closest.shadow + extra)) < 3.0, "the extra distance at 0.5 c: $apart ($extra s more than $closest)")
        // the trips that are long enough ride the 0.5 c cap
        for (t in legs) if (t.distance > 2e9) assertTrue(t.peak >= FlightLaw.HALF_LIGHT * 0.999, "never reached the cap: $t")
        // the game's opening trips from MERIDIAN (inside Earth's mass shadow, 6 km of station lock at
        // 260 m/s first): Luna in about a minute and a half; Mars at clock 0 lies behind Earth, and
        // the straight line crosses Earth's shadow at its floor (0.15 R, 954 km/s): under two minutes
        for (t in fromHome) {
            if (t.label.contains("Luna")) assertTrue(t.shadow < 90.0, "too long from home: $t")
            if (t.label.contains("Mars")) assertTrue(t.shadow < 120.0, "too long from home: $t")
        }
        // and the landings reach a solid ground at the minimum approach speed (14 m/s, moons 12)
        for (t in legs + fromHome) {
            if (t.ground.isNaN()) continue
            assertTrue(t.groundSpeed <= 16.0, "came down too fast: $t")
        }
    }

    /** An approach to a moon: when the ship first came within 1 km of the surface, and how fast it was then. */
    private class Arrival(val label: String) {
        var seconds = Double.NaN
        var speedAt1km = Double.NaN
        var peak = 0.0
        var limitingBody = ""

        override fun toString() =
            "%-36s 1 km after %6.1f s at %8.1f m/s  peak %7.2f Mm/s  limit from %s".format(label, seconds, speedAt1km, peak / 1e6, limitingBody)
    }

    /** Full throttle at body [to], the nose re-aimed every half second, until 1 km above it (or [maxSeconds]). */
    private fun arrive(s: Session, to: Int, label: String, maxSeconds: Double = 300.0): Arrival {
        val target = s.system.bodies[to]
        val a = Arrival(label)
        s.debugFace(to)
        s.setThrottle(1f)
        var n = 0
        while (n < maxSeconds * 60) {
            if (n % 30 == 0) s.debugFace(to)
            val before = s.ship.speed
            input.begin()
            s.frame(1f / 60f, input)
            n++
            a.peak = maxOf(a.peak, s.ship.speed)
            if (dist(s, to) - target.radius < 1000.0) {
                a.seconds = n / 60.0
                // the speed the ship came in with (this frame's collision may already have stopped it)
                a.speedAt1km = maxOf(before, s.ship.speed)
                a.limitingBody = if (s.gs.limitingBody >= 0) s.system.bodies[s.gs.limitingBody].name else "-"
                break
            }
        }
        return a
    }

    /** Launched from station [station] at clock 0 (the slot, LAUNCH, two seconds out). */
    private fun launched(station: Int): Session {
        val s = Session(WorldBuilder.sol(), settings, host, GearRig())
        s.startDocked(station)
        repeat(5) {
            input.begin()
            s.frame(1f / 60f, input)
        }
        s.launchFromStation()
        repeat(120) {
            input.begin()
            s.frame(1f / 60f, input)
        }
        return s
    }

    /** [alt] metres above body [from] on the line to body [to], nose on it, at rest, at clock 0. */
    private fun above(from: String, alt: Double, to: String): Session {
        val world = WorldBuilder.sol()
        val s = Session(world, settings, host, GearRig())
        s.startInSpace()
        val a = world.bodies[world.bodyIndex(from)]
        val b = world.bodies[world.bodyIndex(to)]
        val dx = b.pos.x - a.pos.x
        val dy = b.pos.y - a.pos.y
        val dz = b.pos.z - a.pos.z
        val d = sqrt(dx * dx + dy * dy + dz * dz)
        val r = a.radius + alt
        s.teleport(a.pos.x + dx / d * r, a.pos.y + dy / d * r, a.pos.z + dz / d * r, dx.toFloat(), dy.toFloat(), dz.toFloat(), true)
        return s
    }

    /**
     * A moon beside a big planet slows the ship down by itself (Roblox `ShipGravity:getState`: the
     * limit is the minimum over all bodies, "a small moon close by must not hide a braking giant"
     * and the other way round, 03-world.md B.3): 1 km above the moon the near band holds the ship
     * at landing speeds.
     */
    @Test
    fun moonsBesideBigPlanetsAreReachedAtLandingSpeed() {
        val sol = WorldBuilder.sol()
        fun station(id: String) = sol.stationIndex(id)
        fun body(name: String) = sol.bodyIndex(name)
        val runs = ArrayList<Arrival>()
        // at clock 0 the line from GALILEAN REFINERY to Europa passes 22 Mm from Jupiter's centre,
        // inside its cloud tops: Europa is flown from Jupiter
        for (moon in listOf("Io", "Ganymede", "Callisto")) {
            runs += arrive(launched(station("SOL-GALILEAN")), body(moon), "GALILEAN REFINERY -> $moon")
        }
        for (moon in listOf("Europa", "Callisto")) runs += arrive(above("Jupiter", 50_000.0, moon), body(moon), "50 km above Jupiter -> $moon")
        for (moon in listOf("Phobos", "Deimos")) runs += arrive(launched(station("SOL-ARES")), body(moon), "ARES DEEP -> $moon")
        runs += arrive(launched(station("SOL-MERIDIAN")), body("Luna"), "MERIDIAN -> Luna")
        runs += arrive(above("Saturn", 50_000.0, "Titan"), body("Titan"), "50 km above Saturn -> Titan")
        runs += arrive(above("Neptune", 50_000.0, "Triton"), body("Triton"), "50 km above Neptune -> Triton")
        println(runs.joinToString("\n"))
        for (a in runs) {
            assertTrue(!a.seconds.isNaN(), "never arrived: $a")
            // the near band 1 km up: approachReach(1 km) = 347 m/s at most straight down
            assertTrue(a.speedAt1km <= 400.0, "too fast 1 km above the moon: $a")
        }
    }
}
