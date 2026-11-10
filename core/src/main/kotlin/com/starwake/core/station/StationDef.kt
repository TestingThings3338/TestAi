package com.starwake.core.station

import com.starwake.core.econ.Economy

/**
 * Where a station (or a build site, or a free orbit slot) hangs at its host (ShipStations.offset):
 * Angle and Elevation in degrees against the host-to-star direction, Distance from the host's
 * centre in Roblox studs. The world scales the distance by S when it places the station
 * (03-world.md 2.5, DECISIONS 1); the rules here (slot clearance) compare studs with studs, so the
 * ratios stay as in Roblox.
 */
interface OrbitSlot {
    val angle: Double
    val elevation: Double
    val distance: Double
}

/**
 * A station definition (an entry of ShipStations, or ShipResources.stationDef for a finished
 * player station). Immutable; the station catalogue builds them.
 */
class StationDef(
    /** "SOL-MERIDIAN", "S<seed>-<n>" (generated) or "P<userId>" (a player station, "P1" in singleplayer). */
    val id: String,
    /** Display name in capitals ("MERIDIAN STATION"). */
    val name: String,
    /** Host body name as the world names it ("Earth", "Luna"). */
    val host: String,
    /** ShipEconomy economy id ("HighTech", "Industrial", "Extraction", "Refinery", "Agricultural", "Service"). */
    val economy: String,
    val shipyard: Boolean,
    /**
     * Station model, as the converted assets name it (content/StationModel.TYPES: "SpireStation" ..).
     * Player stations have none in Roblox and show the default [StationCatalog.DEFAULT_MODEL].
     */
    val model: String,
    /** Roblox studs from the host's centre (×S in the world). */
    override val distance: Double,
    override val angle: Double,
    override val elevation: Double,
    /** Station description (Appendix B.1, B.2, B.4). */
    val description: String,
    val systemIndex: Int,
    val systemName: String,
    /** A finished player station (ShipResources.stationDef). */
    val player: Boolean = false,
    /** Founder's name for player stations ("PILOT"), else null. */
    val owner: String? = null,
    val ownerId: Long = 0L,
    /** Epoch seconds the charter was granted (player stations), else 0. */
    val founded: Long = 0L,
) : OrbitSlot {
    /** `upper(ShipEconomy.Economies[Economy].Name)`: "HIGH TECH", "TOURISM" (Service) .. */
    val economyName: String get() = Economy.economyName(economy)

    override fun toString(): String = "StationDef($id $name @ $host)"
}
