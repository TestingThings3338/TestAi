package com.starwake.core.econ

import com.starwake.core.math.LuaRandom
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.save.Clock
import com.starwake.core.save.GalaxyStore
import com.starwake.core.save.MonoClock
import com.starwake.core.save.Profile
import com.starwake.core.save.WallClock
import com.starwake.core.station.BodyInfo
import com.starwake.core.station.SolWorld
import com.starwake.core.station.StationCatalog
import com.starwake.core.station.WorldInfo
import kotlin.math.floor

/** Shared test doubles for the economy tests. */
object EconFixtures {
    /** The clock of Appendix A.1 (epoch seconds). */
    const val NOW = 1_800_000_000L

    /** Seeds as ShipStarCatalog makes them: seed + index · 7919. */
    const val SEED_2 = 20_260_824L + 2 * 7919
    const val SEED_3 = 20_260_824L + 3 * 7919

    /**
     * Sol (the module's own table, ShipSolData verbatim) plus two made-up systems for the rolled
     * rules: "KEPLER" (2: a landable terrestrial world with a moon, a ringed gas giant) and "TARSIS"
     * (3: one rocky planet).
     */
    class TestWorld : WorldInfo {
        override val systemCount: Int get() = 3

        override fun systemName(index: Int): String = when (index) {
            1 -> "Sol"
            2 -> "Kepler"
            3 -> "Tarsis"
            else -> "Unknown"
        }

        override fun systemSeed(index: Int): Long = when (index) {
            2 -> SEED_2
            3 -> SEED_3
            else -> 0L
        }

        override fun bodies(index: Int): List<BodyInfo> = when (index) {
            1 -> SolWorld.BODIES
            2 -> KEPLER
            3 -> TARSIS
            else -> emptyList()
        }
    }

    val KEPLER = listOf(
        BodyInfo("Kepler", BodyInfo.STAR, starClass = "K", radius = 3000.0),
        BodyInfo("Kepler b", "Terrestrial World", landable = true, radius = 1500.0),
        BodyInfo("Kepler b I", BodyInfo.MOON, landable = true, radius = 300.0, parent = "Kepler b"),
        BodyInfo("Kepler c", "Gas Giant", ring = true, radius = 7000.0),
    )

    val TARSIS = listOf(
        BodyInfo("Tarsis", BodyInfo.STAR, starClass = "O", radius = 6000.0),
        BodyInfo("Tarsis a", "Rocky Body", landable = true, radius = 800.0),
    )

    class FakeWall(var t: Long = NOW) : WallClock {
        override fun epochSeconds(): Long = t
    }

    class FakeMono(var t: Double = 1000.0) : MonoClock {
        override fun seconds(): Double = t
    }

    /**
     * A `Random` that answers from a script of fractions u in [0, 1) and logs every call:
     * NextNumber() = u, NextNumber(a, b) = a + (b - a)·u, NextInteger(a, b) = a + floor(u·(b - a + 1)).
     */
    class ScriptedRandom(vararg us: Double) : LuaRandom {
        private val script = ArrayDeque(us.toList())
        val log = ArrayList<String>()

        private fun u(): Double = script.removeFirstOrNull() ?: 0.0

        override fun nextNumber(): Double {
            log.add("NextNumber()")
            return u()
        }

        override fun nextNumber(min: Double, max: Double): Double {
            log.add("NextNumber($min,$max)")
            return min + (max - min) * u()
        }

        override fun nextInteger(min: Int, max: Int): Int {
            log.add("NextInteger($min,$max)")
            return min + floor(u() * (max - min + 1)).toInt()
        }
    }

    /** A factory handing out [random] for every seed, recording the seeds. */
    class ScriptedFactory(val random: LuaRandom) : LuaRandomFactory {
        val seeds = ArrayList<Long>()
        override fun create(seed: Long): LuaRandom {
            seeds.add(seed)
            return random
        }
    }

    /** A fresh engine on [world]: catalogue, clock (wall [now], monotonic 1000 s), galaxy, template profile, commerce. */
    class Rig(val world: WorldInfo = TestWorld(), now: Long = NOW, padSeed: Long = 7L) {
        val wall = FakeWall(now)
        val mono = FakeMono()
        val clock = Clock(wall, mono)
        val catalog = StationCatalog(world)
        val galaxy = GalaxyStore(catalog, clock)
        val profile = Profile.template(now)
        val commerce = Commerce(profile, galaxy, catalog, clock, com.starwake.core.math.Pcg32Random(padSeed))

        /** Advances both clocks by [seconds]. */
        fun pass(seconds: Double) {
            wall.t += seconds.toLong()
            mono.t += seconds
        }

        /** Undocks (if docked), asks for a permit at [stationId] and docks with a full tank. */
        fun dockAt(stationId: String, fuel: Double = 1.0) {
            if (commerce.docked != null) check(commerce.undock().ok)
            check(commerce.requestDocking(stationId).ok) { "request $stationId: ${commerce.reply.reason}" }
            check(commerce.dock(stationId, fuel).ok) { "dock $stationId: ${commerce.reply.reason}" }
        }
    }
}
