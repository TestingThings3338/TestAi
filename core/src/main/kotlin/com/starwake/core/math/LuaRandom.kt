package com.starwake.core.math

/**
 * The Roblox `Random` datatype as the Lua rules use it (`Random.new(seed)`, `NextNumber()`,
 * `NextNumber(min, max)`, `NextInteger(min, max)`). Every generator in the port that must give the
 * same results as a Roblox `Random` goes through this interface, so a bit-exact implementation can
 * replace [Pcg32Random] without touching the rules (DECISIONS 10: "best-effort PCG32 behind an
 * interface"; generated systems are "same rules, possibly different dice", Sol is exact).
 *
 * The rules depend on the ORDER of the calls, never on a particular sequence of numbers.
 */
interface LuaRandom {
    /** `Random:NextNumber()`: uniform in [0, 1). */
    fun nextNumber(): Double

    /** `Random:NextNumber(min, max)`: uniform in [min, max). */
    fun nextNumber(min: Double, max: Double): Double

    /** `Random:NextInteger(min, max)`: uniform integer in [min, max] (both inclusive). */
    fun nextInteger(min: Int, max: Int): Int
}

/** `Random.new(seed)`: a new generator for an integer seed (the Lua code seeds with whole numbers). */
fun interface LuaRandomFactory {
    fun create(seed: Long): LuaRandom
}

/**
 * Best-effort Roblox `Random`: PCG32 (XSH-RR, 64-bit state) seeded and sampled exactly as Luau's own
 * `math.random` does it (lmathlib.cpp: `pcg32_seed` with increment 105, a 64-bit fraction for real
 * numbers, multiply-shift for integer ranges). Roblox does not document its `Random` internals, so
 * the dice may differ from Roblox for generated systems (DECISIONS 10). Deterministic on every
 * device; no allocation after construction.
 */
class Pcg32Random(seed: Long) : LuaRandom {
    private var state = 0L

    init {
        // pcg32_seed: state = 0; step; state += seed; step
        state = 0L
        next32()
        state += seed
        next32()
    }

    /** One PCG32 output (pcg32_random): 32 random bits. */
    fun next32(): Int {
        val old = state
        state = old * MULTIPLIER + INCREMENT
        val xorShifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return Integer.rotateRight(xorShifted, rot)
    }

    override fun nextNumber(): Double {
        // ldexp(double(lo | hi << 32), -64): the 64 random bits as an unsigned fraction
        val lo = next32().toLong() and 0xFFFFFFFFL
        val hi = next32().toLong() and 0xFFFFFFFFL
        return (lo or (hi shl 32)).toULong().toDouble() * TWO_POW_MINUS_64
    }

    override fun nextNumber(min: Double, max: Double): Double = min + (max - min) * nextNumber()

    override fun nextInteger(min: Int, max: Int): Int {
        require(min <= max) { "interval is empty" }
        // multiply-shift: range * u32 >> 32 (the product stays below 2^64, so the unsigned shift is exact)
        val range = max.toLong() - min.toLong() + 1L
        val x = range * (next32().toLong() and 0xFFFFFFFFL)
        return (min.toLong() + (x ushr 32)).toInt()
    }

    companion object {
        private const val MULTIPLIER = 6364136223846793005L
        private const val INCREMENT = 105L // (105 | 1), Luau's fixed stream
        private const val TWO_POW_MINUS_64 = 5.421010862427522E-20 // 2^-64

        /** `Random.new` for the whole port until a bit-exact Roblox generator exists. */
        val FACTORY = LuaRandomFactory { seed -> Pcg32Random(seed) }
    }
}
