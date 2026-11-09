package com.starwake.core.game

import com.starwake.core.Settings
import com.starwake.core.input.InputFrame
import com.starwake.core.world.WorldBuilder
import java.io.File
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Trip times in Sol at S = 3000 with the kept speed law (`StarSystem.speedLimit`: the limit grows
 * with the clearance at a per-second rate, capped at 0.5 c) and the flight model at full throttle,
 * Flight Assist on, the nose re-aimed at the target every half second as a pilot would. A trip
 * "arrives" at the target's mass-shadow edge (DECISIONS 2: about a minute, 52-72 s there), and the
 * table also gives the 12-radii approach and the 60 km low-altitude marks (03-world.md B.3).
 * Legs between planets start 50 m above the departure planet, at a conjunction (the line of
 * closest approach, as B.3 measures them). The table is written to build/reports/trip-times.txt.
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
        var peak: Float = 0f,
    ) {
        override fun toString() =
            "%-34s gap %8.0f Mm  shadow %6.1f s  12 R %6.1f s  60 km %6.1f s  peak %6.1f Mm/s (%.2f c)"
                .format(label, distance / 1e6, shadow, approach, low, peak / 1e6, peak / 2.998e8)
    }

    private fun dist(s: Session, i: Int): Double {
        val b = s.system.bodies[i].pos
        val dx = s.ship.pos.x - b.x
        val dy = s.ship.pos.y - b.y
        val dz = s.ship.pos.z - b.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /** The mass-shadow edge from the centre: Rc above the barrier times the shadow factor (`ShipGravity`). */
    private fun shadowEdge(b: StarSystem.Body) = b.collisionRadius * (1.0 + b.massShadowFactor)

    /** Flies [s] at full throttle towards body [to] until 60 km above it (or [maxSeconds]). */
    private fun fly(s: Session, to: Int, label: String, maxSeconds: Double = 400.0): Trip {
        val target = s.system.bodies[to]
        val trip = Trip(label, dist(s, to) - target.radius)
        val dt = 1f / 60f
        s.debugFace(to)
        s.setThrottle(1f)
        var n = 0
        while (n < maxSeconds * 60) {
            if (n % 30 == 0) s.debugFace(to)
            input.begin()
            s.frame(dt, input)
            n++
            val t = n / 60.0
            trip.peak = maxOf(trip.peak, s.ship.speed)
            val d = dist(s, to)
            if (trip.shadow.isNaN() && d < shadowEdge(target)) trip.shadow = t
            if (trip.approach.isNaN() && d < target.radius * StarSystem.APPROACH_RADII) trip.approach = t
            if (d - target.radius < StarSystem.LOW_ALTITUDE) {
                trip.low = t
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
            assertTrue(!t.low.isNaN(), "never arrived: $t")
            assertTrue(t.peak <= StarSystem.MAX_SPEED * 1.0001f, "faster than 0.5 c: $t")
        }
        // DECISIONS 2: neighbouring planets about a minute apart, at their mass-shadow edge
        for (t in legs) assertTrue(t.shadow in 35.0..75.0, "not about a minute to the shadow edge: $t")
        // and the trips that are long enough ride the 0.5 c cap
        for (t in legs) if (t.distance > 2e9) assertTrue(t.peak >= StarSystem.MAX_SPEED * 0.999f, "never reached the cap: $t")
        // the game's opening trip: MERIDIAN to Mars, wherever Mars is at clock 0, about a minute (the
        // station lets go of the ship once it leaves its zone)
        for (t in fromHome) if (t.label.contains("Mars") || t.label.contains("Luna")) assertTrue(t.shadow < 90.0, "too long from home: $t")
    }

    /** An approach to a moon: when the ship first came within 1 km of the surface, and how fast it was then. */
    private class Arrival(val label: String) {
        var seconds = Double.NaN
        var speedAt1km = Float.NaN
        var peak = 0f
        var limitingBody = ""

        override fun toString() =
            "%-36s 1 km after %6.1f s at %8.1f m/s  peak %7.2f Mm/s  limit from %s".format(label, seconds, speedAt1km, peak / 1e6, limitingBody)
    }

    /** Full throttle at body [to], the nose re-aimed every half second, until 1 km above it (or [maxSeconds]). */
    private fun arrive(s: Session, to: Int, label: String, maxSeconds: Double = 240.0): Arrival {
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
                a.limitingBody = s.system.bodies[s.here.limitingBody].name
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
     * and the other way round, 03-world.md B.3). With the nearest body's limit alone, the planet's
     * altitude carried the ship to the moon's surface at Mm/s.
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
            // the approach law at 1 km (0.32/s x 1 km) plus a little lag behind the falling limit
            assertTrue(a.speedAt1km <= 400f, "too fast 1 km above the moon: $a")
        }
    }
}
