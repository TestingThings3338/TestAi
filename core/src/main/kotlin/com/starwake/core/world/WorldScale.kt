package com.starwake.core.world

/**
 * The uniform world scale (DECISIONS 1, 03-world.md 1.3 and Appendix B): a Roblox length of x studs
 * becomes x·S metres. [SolData] and every other definition stay in studs, verbatim from the Lua;
 * [WorldBuilder] applies S in exactly one place.
 *
 * What scales (B.6): body radii, orbit radii, ring radii, star heat and mass-shadow radii, station
 * distances, the field radius and spawn points. What stays absolute: ship sizes and speeds,
 * station-local ranges (docking, zones, hangar), landing, terrain and atmosphere heights, and all
 * times (orbit periods are kept, so orbital speeds become ×S).
 */
object WorldScale {
    /** Metres per Roblox stud of world length: Earth 6,360 km, Luna at 384,000 km. */
    const val S = 3000.0

    /** A Roblox world length (studs) in metres. */
    fun len(studs: Double): Double = studs * S
}
