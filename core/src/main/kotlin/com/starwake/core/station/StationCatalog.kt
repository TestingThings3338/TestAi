package com.starwake.core.station

import com.starwake.core.econ.Lua
import com.starwake.core.math.DVec3
import com.starwake.core.math.LuaRandom
import com.starwake.core.math.LuaRandomFactory
import com.starwake.core.math.Pcg32Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of ShipCore/ShipStations.lua: which stations exist in which system. Sol's six are hand-made;
 * every other system derives 0..2 stations from its seed with the Roblox rng call order
 * ([baseForSystem]); finished player stations from the galaxy store are added on top
 * ([setPlayerStations]). Also the body list for the survey ([bodies]) and the station offset from
 * its host ([offset]).
 *
 * World data (system names, seeds, bodies) comes through [WorldInfo] ([SolWorld] until the world
 * port provides an adapter), `Random` through [LuaRandomFactory]. Lists are built once per system
 * and cached; [find] and [forSystem] do not allocate.
 */
class StationCatalog(
    val world: WorldInfo = SolWorld,
    private val randoms: LuaRandomFactory = Pcg32Random.FACTORY,
) {
    // per-system caches, indexed by system (no boxed keys: find() runs per frame)
    private val baseLists = PerSystem<List<StationDef>>()
    private val lists = PerSystem<List<StationDef>>()
    private val playerStations = PerSystem<List<StationDef>>()
    private val bodyLists = PerSystem<List<BodyInfo>>()
    private val bodyMaps = PerSystem<Map<String, BodyInfo>>()
    private val planetLists = PerSystem<List<BodyInfo>>()

    /** A growable array by system index. */
    private class PerSystem<T : Any> {
        private var items = arrayOfNulls<Any>(16)

        @Suppress("UNCHECKED_CAST")
        operator fun get(index: Int): T? = if (index in items.indices) items[index] as T? else null

        operator fun set(index: Int, value: T?) {
            if (index < 0) return
            if (index >= items.size) items = items.copyOf(maxOf(index + 1, items.size * 2))
            items[index] = value
        }

        inline fun getOrPut(index: Int, make: () -> T): T = get(index) ?: make().also { set(index, it) }

        fun remove(index: Int) = set(index, null)

        /** Every stored value (the registered player stations for findAnywhere). */
        fun values(): List<T> {
            val out = ArrayList<T>()
            for (i in items.indices) {
                @Suppress("UNCHECKED_CAST")
                (items[i] as T?)?.let { out.add(it) }
            }
            return out
        }
    }

    /** Number of systems (1..systemCount are valid). */
    val systemCount: Int get() = world.systemCount

    fun isValidSystem(index: Int): Boolean = index in 1..world.systemCount

    /** `getSystemName(index)`: "Sol", the catalogue name, or "Unknown". */
    fun systemName(index: Int): String = if (index == 1) "Sol" else if (isValidSystem(index)) world.systemName(index) else "Unknown"

    // ---------------------------------------------------------------- bodies (:336)
    /** All bodies of a system in definition order (empty if unknown). */
    fun bodies(index: Int): List<BodyInfo> = bodyLists.getOrPut(index) { if (isValidSystem(index)) world.bodies(index) else emptyList() }

    /** `getBodies(index)[name]`, or null. */
    fun body(index: Int, name: String?): BodyInfo? {
        if (name == null) return null
        val map = bodyMaps.getOrPut(index) { LinkedHashMap<String, BodyInfo>().apply { for (b in bodies(index)) put(b.name, b) } }
        return map[name]
    }

    /** The planets (not the star, not moons) in definition order: ShipSystemBuilder's `Planets`. */
    fun planets(index: Int): List<BodyInfo> = planetLists.getOrPut(index) { bodies(index).filter { it.isPlanet } }

    // ---------------------------------------------------------------- stations per system (:198)
    /** The base list of a system: Sol's six, or the stations generated from its seed. Cached. */
    fun baseForSystem(index: Int): List<StationDef> = baseLists.getOrPut(index) { if (index == 1) SOL else generate(index) }

    /** `forSystem(index)`: the base list plus the finished player stations. Cached until [setPlayerStations]. */
    fun forSystem(index: Int): List<StationDef> = lists.getOrPut(index) {
        val extra = playerStations[index]
        if (extra.isNullOrEmpty()) baseForSystem(index) else baseForSystem(index) + extra
    }

    /** `setPlayerStations(index, defs)`: the finished player stations of a system (replaces the old ones). */
    fun setPlayerStations(index: Int, definitions: List<StationDef>) {
        playerStations[index] = definitions
        lists.remove(index)
    }

    fun playerStations(index: Int): List<StationDef> = playerStations[index] ?: emptyList()

    /** `find(index, id)`: a station of system [index], or null. */
    fun find(index: Int, stationId: String?): StationDef? {
        if (stationId == null) return null
        val list = forSystem(index)
        for (i in list.indices) if (list[i].id == stationId) return list[i]
        return null
    }

    /**
     * `findAnywhere(id)`: "SOL-…" in Sol, "P<digits>" among the registered player stations,
     * "S<seed>-<n>" in the system with that seed.
     */
    fun findAnywhere(stationId: String): StationDef? {
        if (stationId.startsWith("SOL-")) return find(1, stationId)
        if (PLAYER_ID.matches(stationId)) {
            for (defs in playerStations.values()) for (d in defs) if (d.id == stationId) return d
            return null
        }
        val m = GENERATED_ID.matchEntire(stationId) ?: return null
        val seed = m.groupValues[1].toLongOrNull() ?: return null
        for (index in 1..world.systemCount) {
            val systemSeed = if (index == 1) 0L else world.systemSeed(index)
            if (systemSeed == seed) return find(index, stationId)
        }
        return null
    }

    /** The generated stations of system [index] (ShipStations.baseForSystem, the `else` branch). */
    private fun generate(index: Int): List<StationDef> {
        val planets = planets(index)
        if (!isValidSystem(index) || planets.isEmpty()) return emptyList()
        val seed = world.systemSeed(index)
        val systemName = systemName(index)
        val rng = randoms.create(seed * 31 + 7)
        val roll = rng.nextNumber()
        var count = if (roll < 0.35) 0 else if (roll < 0.8) 1 else 2
        count = min(count, planets.size)
        val used = BooleanArray(planets.size + 1)
        val list = ArrayList<StationDef>(count)
        for (n in 1..count) {
            // host: a random planet, moving on to the first one without a station
            var hostIndex = rng.nextInteger(1, planets.size)
            for (k in 1..planets.size) {
                if (!used[hostIndex]) break
                hostIndex = hostIndex % planets.size + 1
            }
            used[hostIndex] = true
            val planet = planets[hostIndex - 1]
            val economy = pickEconomy(planet.cls, rng)
            val suffixes = StationLore.suffixes(economy)
            val first = StationLore.NAMES[rng.nextInteger(1, StationLore.NAMES.size) - 1]
            val name = first + " " + suffixes[rng.nextInteger(1, suffixes.size) - 1]
            // steep above the orbital plane: rings and moons lie in it
            val elevation = rng.nextNumber(35.0, 50.0) * (if (rng.nextNumber() < 0.5) -1 else 1)
            val id = "S$seed-$n"
            val model = pickModel(id, economy) ?: DEFAULT_MODEL
            // the `and` short-circuits: only HighTech and Industrial draw here, before the angle
            val shipyard = (economy == "HighTech" || economy == "Industrial") && rng.nextNumber() < 0.6
            val distance = floor(planet.radius * 2.1 + 2200)
            val angle = rng.nextNumber(0.0, 360.0)
            list.add(
                StationDef(
                    id = id, name = name, host = planet.name, economy = economy, shipyard = shipyard, model = model,
                    distance = distance, angle = angle, elevation = elevation, description = StationLore.blurb(economy),
                    systemIndex = index, systemName = systemName,
                ),
            )
        }
        return list
    }

    companion object {
        /** New pilots start here (ShipStations.HomeStation; DECISIONS 13). */
        const val HOME_STATION = "SOL-MERIDIAN"

        /** ShipConfig.Stations.Model: the default model, used by player stations. */
        const val DEFAULT_MODEL = "RingStation"

        /** ShipStations.offset's default Distance when a station has none. */
        const val DEFAULT_DISTANCE = 4000.0

        private val PLAYER_ID = Regex("^P\\d+$")
        private val GENERATED_ID = Regex("^S(\\d+)-\\d+$")

        private fun sol(
            id: String, name: String, host: String, economy: String, shipyard: Boolean, model: String,
            distance: Double, angle: Double, elevation: Double, description: String,
        ) = StationDef(id, name, host, economy, shipyard, model, distance, angle, elevation, description, 1, "Sol")

        /** Sol's hand-made stations (ShipStations.Sol :41, descriptions Appendix B.1). */
        val SOL: List<StationDef> = listOf(
            sol(
                "SOL-MERIDIAN", "MERIDIAN STATION", "Earth", "HighTech", true, "SpireStation", 6600.0, 90.0, 24.0,
                "THE BUSIEST PORT IN SOL. MERIDIAN BUILDS THE SHIP COMPUTERS HALF THE GALAXY FLIES WITH AND FEEDS ITSELF ON IMPORTED FOOD AND RAW METAL.",
            ),
            sol(
                "SOL-TRANQUILITY", "TRANQUILITY YARDS", "Luna", "Industrial", true, "FoundryStation", 3400.0, 200.0, -32.0,
                "LUNAR FOUNDRIES AND THE OLDEST SHIPYARD IN THE SYSTEM. ALLOYS AND CONSUMER GOODS GO OUT, METALS AND FUEL COME IN.",
            ),
            sol(
                "SOL-ARES", "ARES DEEP", "Mars", "Extraction", false, "HollowStation", 4600.0, 60.0, 28.0,
                "A MINING HUB ABOVE THE RED PLANET. TITANIUM AND COPPER LEAVE BY THE THOUSAND TONS; THE MINERS PAY WELL FOR FOOD AND MEDICINE.",
            ),
            sol(
                "SOL-GALILEAN", "GALILEAN REFINERY", "Jupiter", "Refinery", false, "RefineryStation", 20000.0, 120.0, 30.0,
                "SKIMMER FLEETS DIVE INTO JUPITER'S CLOUDS, AND THIS REFINERY TURNS THE GAS INTO FUEL FOR THE WHOLE SYSTEM.",
            ),
            sol(
                "SOL-TITAN", "TITAN HYDROPONICS", "Titan", "Agricultural", false, "GardenStation", 4100.0, 300.0, 34.0,
                "GREENHOUSE DECKS IN SATURN'S SHADOW. GRAIN AND PROTEIN FOR ALL OF SOL, PAID FOR IN MEDICINE AND MACHINERY.",
            ),
            sol(
                "SOL-HELIOS", "HELIOS FORGE", "Mercury", "Industrial", false, "RingStation", 4400.0, 270.0, -26.0,
                "SOLAR SMELTERS IN MERCURY'S SHADOW. THE HEAT IS FREE; THE ORE COMES IN FROM MARS.",
            ),
        )

        /** ShipConfig.Stations.Types: model choices of generated stations by economy. */
        fun modelTypes(economy: String): Array<String>? = when (economy) {
            "HighTech", "Service" -> TYPES_SPIRE_RING
            "Industrial" -> TYPES_FOUNDRY_RING
            "Extraction" -> TYPES_HOLLOW_FOUNDRY
            "Refinery" -> TYPES_REFINERY
            "Agricultural" -> TYPES_GARDEN_RING
            else -> null
        }

        private val TYPES_SPIRE_RING = arrayOf("SpireStation", "RingStation")
        private val TYPES_FOUNDRY_RING = arrayOf("FoundryStation", "RingStation")
        private val TYPES_HOLLOW_FOUNDRY = arrayOf("HollowStation", "FoundryStation")
        private val TYPES_REFINERY = arrayOf("RefineryStation")
        private val TYPES_GARDEN_RING = arrayOf("GardenStation", "RingStation")

        /**
         * `pickModel(id, economy)`: `Types[economy][h % #types + 1]` with h = 0, h = (h·31 + byte) %
         * 1000003 over the id. Deliberately not from the rng, so positions and names stay as they were.
         */
        fun pickModel(id: String, economy: String): String? {
            val types = modelTypes(economy) ?: return null
            if (types.isEmpty()) return null
            val h = Lua.hash(0L, 31L, 1_000_003L, id)
            return types[(h % types.size).toInt()]
        }

        /**
         * `pickEconomy(class, rng)`: one roll; under 0.15 a second draw picks HighTech (< 0.6) or
         * Service; otherwise by host class (Gas Giant Refinery, Ice Giant Refinery/Extraction at
         * 0.6, Ocean World Agricultural, Ice World Extraction, Volcanic Body Industrial/Extraction at
         * 0.55, anything else Extraction/Industrial at 0.55).
         */
        fun pickEconomy(cls: String, rng: LuaRandom): String {
            val roll = rng.nextNumber()
            if (roll < 0.15) return if (rng.nextNumber() < 0.6) "HighTech" else "Service"
            return when (cls) {
                "Gas Giant" -> "Refinery"
                "Ice Giant" -> if (roll < 0.6) "Refinery" else "Extraction"
                "Ocean World" -> "Agricultural"
                "Ice World" -> "Extraction"
                "Volcanic Body" -> if (roll < 0.55) "Industrial" else "Extraction"
                else -> if (roll < 0.55) "Extraction" else "Industrial"
            }
        }

        /**
         * `ShipStations.offset(station, host, star)` into [out], in the units of [slot]'s distance
         * (Roblox studs here; the world passes S-scaled values to StationPlacement.offset): the
         * host-to-star direction flattened onto XZ ((0, 0, -1) if the star is straight above or
         * below), turned by Angle about +Y, lifted by Elevation, times Distance.
         */
        fun offset(
            slot: OrbitSlot, hx: Double, hy: Double, hz: Double, sx: Double, sy: Double, sz: Double, out: DVec3,
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
            val a = Math.toRadians(slot.angle)
            val dx = bx * cos(a) + bz * sin(a)
            val dz = -bx * sin(a) + bz * cos(a)
            val e = Math.toRadians(slot.elevation)
            val d = slot.distance
            return out.set(dx * cos(e) * d, sin(e) * d, dz * cos(e) * d)
        }
    }
}
