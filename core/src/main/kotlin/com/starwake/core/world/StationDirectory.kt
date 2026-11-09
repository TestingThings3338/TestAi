package com.starwake.core.world

import com.starwake.core.content.StationMesh
import com.starwake.core.math.DVec3
import com.starwake.core.math.Quat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A station of the catalog (`ShipCore/ShipStations.lua`, 03-world.md 2.5): where it hangs, in Roblox
 * units. [distance] is in studs (the builder scales it by S, DECISIONS 9); [angleDeg] turns the
 * host→star direction about +Y in the orbit plane, [elevationDeg] lifts it out of the plane.
 */
class StationDef(
    val id: String,
    val name: String,
    /** Display name of the host body (planet or moon), matched exactly (`findHost`). */
    val host: String,
    val economy: String,
    val shipyard: Boolean,
    /** Roblox model name under Assets.Stations. */
    val model: String,
    val distance: Double,
    val angleDeg: Double,
    val elevationDeg: Double,
    val description: String,
)

/**
 * The station catalog's Sol part and its placement rule (03-world.md 2.5.1, 2.5.4). Generated and
 * player stations arrive with the catalog/economy port.
 */
object StationDirectory {
    /** `ShipStations.HomeStation`: new pilots start docked here (DECISIONS 13). */
    const val HOME_STATION = "SOL-MERIDIAN"

    /** `ShipStations.Sol`, verbatim (distances in studs). */
    val SOL: List<StationDef> = listOf(
        StationDef(
            "SOL-MERIDIAN", "MERIDIAN STATION", "Earth", "HighTech", true, "SpireStation", 6600.0, 90.0, 24.0,
            "THE BUSIEST PORT IN SOL. MERIDIAN BUILDS THE SHIP COMPUTERS HALF THE GALAXY FLIES WITH AND FEEDS ITSELF ON IMPORTED FOOD AND RAW METAL.",
        ),
        StationDef(
            "SOL-TRANQUILITY", "TRANQUILITY YARDS", "Luna", "Industrial", true, "FoundryStation", 3400.0, 200.0, -32.0,
            "LUNAR FOUNDRIES AND THE OLDEST SHIPYARD IN THE SYSTEM. ALLOYS AND CONSUMER GOODS GO OUT, METALS AND FUEL COME IN.",
        ),
        StationDef(
            // steeper than the others: Phobos orbits at 3600 in the plane
            "SOL-ARES", "ARES DEEP", "Mars", "Extraction", false, "HollowStation", 4600.0, 60.0, 28.0,
            "A MINING HUB ABOVE THE RED PLANET. TITANIUM AND COPPER LEAVE BY THE THOUSAND TONS; THE MINERS PAY WELL FOR FOOD AND MEDICINE.",
        ),
        StationDef(
            // outside the ring (to 16000) and above its plane
            "SOL-GALILEAN", "GALILEAN REFINERY", "Jupiter", "Refinery", false, "RefineryStation", 20000.0, 120.0, 30.0,
            "SKIMMER FLEETS DIVE INTO JUPITER'S CLOUDS, AND THIS REFINERY TURNS THE GAS INTO FUEL FOR THE WHOLE SYSTEM.",
        ),
        StationDef(
            "SOL-TITAN", "TITAN HYDROPONICS", "Titan", "Agricultural", false, "GardenStation", 4100.0, 300.0, 34.0,
            "GREENHOUSE DECKS IN SATURN'S SHADOW. GRAIN AND PROTEIN FOR ALL OF SOL, PAID FOR IN MEDICINE AND MACHINERY.",
        ),
        StationDef(
            // the classic ring station stays in Sol
            "SOL-HELIOS", "HELIOS FORGE", "Mercury", "Industrial", false, "RingStation", 4400.0, 270.0, -26.0,
            "SOLAR SMELTERS IN MERCURY'S SHADOW. THE HEAT IS FREE; THE ORE COMES IN FROM MARS.",
        ),
    )

    /**
     * `ShipStations.offset` (`:321`): the host→station offset in metres for [def], from the host's
     * REST position ([hx], [hy], [hz]) and the star at ([sx], [sy], [sz]), with the distance scaled
     * by [scale]. The host→star direction is flattened onto the XZ plane (−Z when the host sits on
     * the star), turned [StationDef.angleDeg] about +Y (x' = x·cosA + z·sinA, z' = −x·sinA + z·cosA)
     * and lifted [StationDef.elevationDeg]. Allocation free.
     */
    fun offset(
        def: StationDef, hx: Double, hy: Double, hz: Double, sx: Double, sy: Double, sz: Double, scale: Double, out: DVec3,
    ): DVec3 {
        var bx = sx - hx
        var bz = sz - hz
        val l = sqrt(bx * bx + bz * bz)
        if (l > 1e-3) {
            bx /= l
            bz /= l
        } else {
            bx = 0.0
            bz = -1.0
        }
        val a = Math.toRadians(def.angleDeg)
        val dx = bx * cos(a) + bz * sin(a)
        val dz = -bx * sin(a) + bz * cos(a)
        val e = Math.toRadians(def.elevationDeg)
        val d = def.distance * scale
        return out.set(dx * cos(e) * d, sin(e) * d, dz * cos(e) * d)
    }

    /**
     * The station's fixed rotation (`ShipStation.placement`, `:576`): `lookAt(0, −offset.unit, up)`
     * with up = +Y, or +X when the look is within ~18° of vertical, so the mouth (−Z) faces the host.
     */
    fun rotation(ox: Double, oy: Double, oz: Double, out: Quat): Quat {
        val l = sqrt(ox * ox + oy * oy + oz * oz)
        if (l < 1e-9) return out.identity()
        val lx = (-ox / l).toFloat()
        val ly = (-oy / l).toFloat()
        val lz = (-oz / l).toFloat()
        return if (abs(ly) > 0.95f) out.lookRotation(lx, ly, lz, 1f, 0f, 0f) else out.lookRotation(lx, ly, lz, 0f, 1f, 0f)
    }

    /**
     * The stand-in mesh ([StationMesh]) drawn for a Roblox station model until the converted models
     * land (another agent's work): the big spire, garden and ring stations as the ring station, the
     * foundry, hollow and refinery as the outpost. Docking and collision follow the stand-in.
     */
    fun standIn(model: String): Int = when (model) {
        "SpireStation", "GardenStation", "RingStation" -> StationMesh.RING_STATION
        else -> StationMesh.OUTPOST
    }
}
