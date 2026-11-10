package com.starwake.core.game

/**
 * What destroyed the ship (Roblox `startCrash(cause)`, 05-atmosphere-entry.md 4.7): its heat reached
 * `Warnings.Overheat.FatalHeat` (200 %), it hit the ground faster than `Surface.SafeLandingSpeed`
 * (38 m/s), or a station hull faster than `Stations.CrashSpeed` (45 m/s along the hull's normal).
 * [Session.startCrash] takes it.
 */
enum class CrashCause { HEAT, GROUND, STATION }
