package com.starwake.core.save

/** The device's wall clock in epoch seconds (Roblox `os.time()`). */
fun interface WallClock {
    fun epochSeconds(): Long

    companion object {
        val SYSTEM = WallClock { System.currentTimeMillis() / 1000L }
    }
}

/** A monotonic clock in seconds for short timers (Roblox `os.clock()`): docking permits, the mining rate limit. */
fun interface MonoClock {
    fun seconds(): Double

    companion object {
        val SYSTEM = MonoClock { System.nanoTime() / 1e9 }
    }
}

/**
 * The game's clocks. [now] is wall time in epoch seconds for prices, build timers and storage
 * (DECISIONS 24: the device clock), clamped so it never goes backwards: not below the last value
 * it returned, nor below the newest time found in a save ([observe]); a device clock set back
 * therefore freezes the economy until real time catches up instead of undoing builds (08 Q10).
 * [seconds] is monotonic time for the short timers. [offset] is the harness's `time +<s>`.
 *
 * No allocation.
 */
class Clock(private val wall: WallClock = WallClock.SYSTEM, private val mono: MonoClock = MonoClock.SYSTEM) {
    private var floor = Long.MIN_VALUE

    /** Debug: seconds added to the wall clock (the harness command `time +<s>`). */
    var offset = 0L

    /** Epoch seconds, never smaller than any value returned before or observed in a save. */
    fun now(): Long {
        val t = wall.epochSeconds() + offset
        if (t > floor) floor = t
        return floor
    }

    /** A time read from a save (its SavedAt): the clock never reports less from now on. Nonsense values are ignored. */
    fun observe(savedEpochSeconds: Long) {
        if (savedEpochSeconds in 0L..MAX_PLAUSIBLE && savedEpochSeconds > floor) floor = savedEpochSeconds
    }

    /** Monotonic seconds (arbitrary origin). */
    fun seconds(): Double = mono.seconds()

    companion object {
        /** Saves claiming a time after 2100 are corrupt, not the future. */
        const val MAX_PLAUSIBLE = 4_102_444_800L
    }
}
