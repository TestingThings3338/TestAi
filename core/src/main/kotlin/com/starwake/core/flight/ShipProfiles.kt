package com.starwake.core.flight

/**
 * One ship's flight numbers, `ShipConfig.Ships[<id>]` (`ShipCore/ShipConfig.lua:87-370`, 02-flight.md
 * 4.3): masses in t, speeds in m/s, accelerations in m/s², turn rates in °/s, turn accelerations in
 * °/s². Every value holds at [designMass]; [ShipHandling] converts them with the current mass. The
 * external camera lives in [com.starwake.core.content.ShipCatalog] with the rest of the hull.
 */
class ShipProfile(
    /** Catalog id ([com.starwake.core.content.ShipCatalog.Entry.id]), lower case: "varrow". */
    val id: String,
    val displayName: String,
    val hullMass: Double,
    val moduleMass: Double,
    /** Full tank, t. */
    val fuelMass: Double,
    val cargoCapacity: Double,
    val designMass: Double,
    /** Heavier than this: overloaded (no boost). */
    val maxMass: Double,
    val topSpeed: Double,
    val boostSpeed: Double,
    val reverseSpeed: Double,
    val strafeSpeed: Double,
    val accelForward: Double,
    val accelReverse: Double,
    val accelLateral: Double,
    val accelVertical: Double,
    val turnRatePitch: Double,
    val turnRateYaw: Double,
    val turnRateRoll: Double,
    val turnAccelPitch: Double,
    val turnAccelYaw: Double,
    val turnAccelRoll: Double,
    val boostDuration: Double,
    val boostCooldown: Double,
    val boostAccelerationMultiplier: Double,
    val boostHeat: Double,
    // ---- Perks (`ShipConfig.perk`: 1 when the ship has none)
    val mineSpeed: Double = 1.0,
    val surveyValue: Double = 1.0,
    val scanRange: Double = 1.0,
    val fuelBurn: Double = 1.0,
    val scoopRate: Double = 1.0,
) {
    /** Hull and modules with a full tank, t. */
    val fullMass: Double get() = hullMass + moduleMass + fuelMass
}

/** The 8 Roblox ships (`ShipConfig.Ships`), verbatim; [get] falls back to VARROW as `ShipHandling.getProfile`. */
object ShipProfiles {
    val VARROW = ShipProfile(
        "varrow", "VARROW",
        hullMass = 260.0, moduleMass = 78.0, fuelMass = 32.0, cargoCapacity = 16.0, designMass = 370.0, maxMass = 480.0,
        topSpeed = 260.0, boostSpeed = 340.0, reverseSpeed = 130.0, strafeSpeed = 130.0,
        accelForward = 35.0, accelReverse = 25.0, accelLateral = 20.0, accelVertical = 22.0,
        turnRatePitch = 38.0, turnRateYaw = 20.0, turnRateRoll = 100.0,
        turnAccelPitch = 120.0, turnAccelYaw = 60.0, turnAccelRoll = 170.0,
        boostDuration = 2.0, boostCooldown = 4.0, boostAccelerationMultiplier = 2.2, boostHeat = 0.06,
    )

    /** Medium freighter: four times the cargo, heavy and slow. */
    val BRANNOCK = ShipProfile(
        "brannock", "BRANNOCK",
        hullMass = 540.0, moduleMass = 156.0, fuelMass = 64.0, cargoCapacity = 64.0, designMass = 760.0, maxMass = 1000.0,
        topSpeed = 205.0, boostSpeed = 285.0, reverseSpeed = 100.0, strafeSpeed = 90.0,
        accelForward = 24.0, accelReverse = 17.0, accelLateral = 12.0, accelVertical = 14.0,
        turnRatePitch = 24.0, turnRateYaw = 13.0, turnRateRoll = 60.0,
        turnAccelPitch = 70.0, turnAccelYaw = 36.0, turnAccelRoll = 100.0,
        boostDuration = 2.4, boostCooldown = 5.5, boostAccelerationMultiplier = 2.0, boostHeat = 0.08,
    )

    /** Large freighter: two decks, 224 t of cargo, slow in every axis. 2 t below its design mass at a full tank (kept). */
    val MORVEN = ShipProfile(
        "morven", "MORVEN",
        hullMass = 1150.0, moduleMass = 360.0, fuelMass = 128.0, cargoCapacity = 224.0, designMass = 1640.0, maxMass = 2100.0,
        topSpeed = 170.0, boostSpeed = 230.0, reverseSpeed = 80.0, strafeSpeed = 60.0,
        accelForward = 16.0, accelReverse = 11.0, accelLateral = 7.0, accelVertical = 9.0,
        turnRatePitch = 15.0, turnRateYaw = 9.0, turnRateRoll = 38.0,
        turnAccelPitch = 42.0, turnAccelYaw = 22.0, turnAccelRoll = 64.0,
        boostDuration = 2.8, boostCooldown = 7.0, boostAccelerationMultiplier = 1.8, boostHeat = 0.10,
    )

    /** Prospector: mines twice as fast, calm in altitude. */
    val KESSOCK = ShipProfile(
        "kessock", "KESSOCK",
        hullMass = 640.0, moduleMass = 210.0, fuelMass = 80.0, cargoCapacity = 96.0, designMass = 930.0, maxMass = 1210.0,
        topSpeed = 190.0, boostSpeed = 255.0, reverseSpeed = 90.0, strafeSpeed = 80.0,
        accelForward = 20.0, accelReverse = 15.0, accelLateral = 13.0, accelVertical = 16.0,
        turnRatePitch = 20.0, turnRateYaw = 12.0, turnRateRoll = 50.0,
        turnAccelPitch = 56.0, turnAccelYaw = 30.0, turnAccelRoll = 84.0,
        boostDuration = 2.6, boostCooldown = 6.0, boostAccelerationMultiplier = 1.9, boostHeat = 0.09,
        mineSpeed = 2.0,
    )

    /** Surveyor: faster than the VARROW, better survey, frugal drive, bigger scoops. */
    val LOCHRAN = ShipProfile(
        "lochran", "LOCHRAN",
        hullMass = 236.0, moduleMass = 72.0, fuelMass = 48.0, cargoCapacity = 8.0, designMass = 356.0, maxMass = 440.0,
        topSpeed = 280.0, boostSpeed = 365.0, reverseSpeed = 130.0, strafeSpeed = 120.0,
        accelForward = 33.0, accelReverse = 23.0, accelLateral = 18.0, accelVertical = 21.0,
        turnRatePitch = 34.0, turnRateYaw = 18.0, turnRateRoll = 90.0,
        turnAccelPitch = 104.0, turnAccelYaw = 52.0, turnAccelRoll = 150.0,
        boostDuration = 2.2, boostCooldown = 4.5, boostAccelerationMultiplier = 2.1, boostHeat = 0.07,
        surveyValue = 1.5, scanRange = 1.5, fuelBurn = 0.6, scoopRate = 1.5,
    )

    /** Multi-purpose between the VARROW and the LOCHRAN. */
    val CALDER = ShipProfile(
        "calder", "CALDER",
        hullMass = 248.0, moduleMass = 75.0, fuelMass = 40.0, cargoCapacity = 12.0, designMass = 363.0, maxMass = 460.0,
        topSpeed = 270.0, boostSpeed = 352.0, reverseSpeed = 130.0, strafeSpeed = 125.0,
        accelForward = 34.0, accelReverse = 24.0, accelLateral = 19.0, accelVertical = 22.0,
        turnRatePitch = 36.0, turnRateYaw = 19.0, turnRateRoll = 95.0,
        turnAccelPitch = 112.0, turnAccelYaw = 56.0, turnAccelRoll = 160.0,
        boostDuration = 2.1, boostCooldown = 4.2, boostAccelerationMultiplier = 2.15, boostHeat = 0.065,
    )

    /** Light courier: the fastest, the best roll. */
    val TORRIN = ShipProfile(
        "torrin", "TORRIN",
        hullMass = 190.0, moduleMass = 57.0, fuelMass = 28.0, cargoCapacity = 4.0, designMass = 275.0, maxMass = 344.0,
        topSpeed = 300.0, boostSpeed = 400.0, reverseSpeed = 140.0, strafeSpeed = 130.0,
        accelForward = 38.0, accelReverse = 27.0, accelLateral = 21.0, accelVertical = 24.0,
        turnRatePitch = 40.0, turnRateYaw = 21.0, turnRateRoll = 110.0,
        turnAccelPitch = 128.0, turnAccelYaw = 63.0, turnAccelRoll = 192.0,
        boostDuration = 1.9, boostCooldown = 3.8, boostAccelerationMultiplier = 2.3, boostHeat = 0.07,
        fuelBurn = 0.85,
    )

    /** Heavy multi-purpose, eight thrusters. */
    val DORNOCH = ShipProfile(
        "dornoch", "DORNOCH",
        hullMass = 760.0, moduleMass = 230.0, fuelMass = 96.0, cargoCapacity = 64.0, designMass = 1086.0, maxMass = 1410.0,
        topSpeed = 225.0, boostSpeed = 295.0, reverseSpeed = 100.0, strafeSpeed = 85.0,
        accelForward = 22.0, accelReverse = 16.0, accelLateral = 12.0, accelVertical = 14.0,
        turnRatePitch = 22.0, turnRateYaw = 13.0, turnRateRoll = 52.0,
        turnAccelPitch = 62.0, turnAccelYaw = 33.0, turnAccelRoll = 88.0,
        boostDuration = 2.5, boostCooldown = 6.0, boostAccelerationMultiplier = 1.95, boostHeat = 0.085,
        mineSpeed = 1.4, scanRange = 1.25,
    )

    /** In `ShipConfig.Ships` order. */
    val all: List<ShipProfile> = listOf(VARROW, BRANNOCK, MORVEN, KESSOCK, LOCHRAN, CALDER, TORRIN, DORNOCH)

    /** The profile of ship [id] (case-insensitive), VARROW when there is none. No allocation (the menu asks every frame). */
    fun get(id: String): ShipProfile {
        var i = 0
        while (i < all.size) {
            val p = all[i++]
            if (p.id.equals(id, ignoreCase = true)) return p
        }
        return VARROW
    }
}
