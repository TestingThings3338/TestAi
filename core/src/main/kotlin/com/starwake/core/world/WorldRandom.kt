package com.starwake.core.world

/**
 * The seeded random streams of `ShipSystemBuilder.build` (planet phase and orbit tilt, spin yaw, moon
 * phase and tilt). Roblox `Random` is engine-internal; DECISIONS 10 ports it as a best-effort PCG32
 * behind an interface, so this is the PCG32 (XSH-RR, 64-bit state) of Luau's `math.random` with its
 * seeding. Until golden values from Studio exist (03-world.md Appendix C) Sol's phases, tilts and
 * yaws are provisional: same seeds and draw order as Roblox, possibly different dice. Swap the
 * implementation here when a verified one exists; the rest of the world only sees [nextNumber].
 */
class WorldRandom(seed: Long) {
    private var state = 0L

    init {
        next32()
        state += seed
        next32()
    }

    private fun next32(): Int {
        val old = state
        state = old * 6364136223846793005L + INCREMENT
        val xorShifted = (((old ushr 18) xor old) ushr 27).toInt()
        val rot = (old ushr 59).toInt()
        return Integer.rotateRight(xorShifted, rot)
    }

    /** `NextNumber()`: uniform in [0, 1), from two 32-bit outputs (low word first). */
    fun nextNumber(): Double {
        val lo = next32().toLong() and 0xFFFFFFFFL
        val hi = next32().toLong() and 0xFFFFFFFFL
        // the top 53 bits of the 64-bit value, so the result never rounds up to 1
        val bits = ((hi shl 32) or lo) ushr 11
        return bits * TWO_POW_M53
    }

    /** `NextNumber(a, b)` = a + (b − a)·NextNumber(). */
    fun nextNumber(a: Double, b: Double): Double = a + (b - a) * nextNumber()

    private companion object {
        /** PCG stream increment of Luau's generator: (105 << 1) | 1. */
        const val INCREMENT = (105L shl 1) or 1L
        const val TWO_POW_M53 = 1.0 / 9007199254740992.0
    }
}
