package com.starwake.core.econ

import com.starwake.core.content.ShipCatalog
import com.starwake.core.ui.TextBuf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A trade good (ShipEconomy.Commodities): [index] is its place in the market order (0-based). */
class Commodity(val index: Int, val id: String, val name: String, val category: String, val base: Int, val info: String)

/**
 * A station economy (ShipEconomy.Economies): the price factor per commodity index for what it
 * produces (below 1: HIGH supply, LOW demand) and consumes (above 1: LOW supply, HIGH demand);
 * NaN where it does neither (factor 1, MED/MED).
 */
class EconomyType(val id: String, val name: String, val produces: DoubleArray, val consumes: DoubleArray) {
    /** The price factor of commodity [index]: produced, else consumed, else 1. */
    fun factor(index: Int): Double {
        val p = produces[index]
        if (!p.isNaN()) return p
        val c = consumes[index]
        return if (!c.isNaN()) c else 1.0
    }
}

/** Buy/sell price and supply/demand of one commodity at one station, filled by [Economy.price]. */
class Price {
    var buy = 0
    var sell = 0

    /** [Economy.LOW], [Economy.MED] or [Economy.HIGH]. */
    var supply = Economy.MED
    var demand = Economy.MED
}

/** One market row (ShipEconomy.getMarket plus the hold the commerce server adds). */
class MarketRow(val commodity: Commodity) {
    var buy = 0
    var sell = 0
    var supply = Economy.MED
    var demand = Economy.MED

    /** Tons of it in the pilot's hold. */
    var hold = 0
}

/**
 * A station's market (GetMarket): all 17 rows in market order, preallocated once and refilled in
 * place, plus the hold numbers the trade panel needs.
 */
class Market {
    val rows = Array(Economy.COMMODITIES.size) { MarketRow(Economy.COMMODITIES[it]) }
    var stationId: String? = null
    var economyId: String? = null

    /** Epoch seconds the prices are for. */
    var now = 0L
    var cargoUsed = 0
    var cargoCapacity = 0

    /** False until filled (and after a failed GetMarket). */
    var valid = false

    fun row(commodityId: String): MarketRow? {
        val i = Economy.commodityIndex(commodityId)
        return if (i >= 0) rows[i] else null
    }

    /** What the station pays most for (ShipStationUi:bestSell: the first of the highest Sell). */
    fun bestSell(): MarketRow? {
        if (!valid) return null
        var best: MarketRow? = null
        for (r in rows) if (best == null || r.sell > best.sell) best = r
        return best
    }
}

/**
 * A ship in the shipyard (ShipEconomy.Ships plus the values the shipyard shows from
 * ShipConfig.Ships). Name, role and price come from [catalog] (content/ShipCatalog, the renderer's
 * list in the same order), so both lists can never disagree; [id] is the Roblox id the profile
 * stores ("Varrow").
 */
class ShipType(
    val id: String,
    val catalog: ShipCatalog.Entry,
    /** Shipyard text (Appendix B.6). */
    val description: String,
    /** CargoCapacity (t). */
    val cargoCapacity: Int,
    /** DesignMass (t), the shipyard's "SHIP MASS". */
    val designMass: Int,
    /** FuelMass (t) of a full tank (ShipConfig.Ships); the tank itself always holds [Economy.FUEL_CAPACITY] units. */
    val fuelMass: Int,
    val topSpeed: Int,
    val boostSpeed: Int,
    /** TurnRate pitch / yaw (degrees per second). */
    val pitchRate: Int,
    val yawRate: Int,
    /** Perks (ShipConfig.Ships[..].Perks) by [Economy.PERK_SURVEY_VALUE] ..; 1 = none. */
    val perks: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0),
    /** ShipConfig TestOnly: never sold, never active. None of the eight is (DECISIONS 18). */
    val testOnly: Boolean = false,
) {
    val name: String get() = catalog.name
    val role: String get() = catalog.role
    val price: Int get() = catalog.price
}

/**
 * Port of ShipCore/ShipEconomy.lua: commodities, economies, drifting station prices, survey values,
 * the ship catalogue and credit formatting. Pure data and functions; the numbers are the Lua's,
 * computed in the same order so prices match Roblox to the credit (Appendix A).
 *
 * Nothing here allocates except the String formatters ([groupDigits], [formatCredits]); the
 * [TextBuf] variants are for per-frame text.
 */
object Economy {
    // ---------------------------------------------------------------- constants (ShipEconomy :28)
    const val START_CREDITS = 1500L
    const val FUEL_PRICE_PER_UNIT = 4
    const val REBUY_FRACTION = 0.05
    const val SPREAD = 0.04
    const val DRIFT_AMOUNT = 0.06
    const val DRIFT_PERIOD = 1200.0

    /** ShipConfig.Systems.FuelCapacity: units in a full tank (every ship). */
    const val FUEL_CAPACITY = 100.0

    /** Supply and demand levels; [LEVEL_NAMES] gives the market's words. */
    const val LOW = 0
    const val MED = 1
    const val HIGH = 2
    val LEVEL_NAMES = arrayOf("LOW", "MED", "HIGH")

    // ---------------------------------------------------------------- commodities (:38)
    val COMMODITIES = arrayOf(
        Commodity(0, "Water", "WATER", "CHEMICALS", 120, "PURIFIED ICE MELT. EVERY HABITAT DRINKS, WASHES AND GROWS WITH IT."),
        Commodity(1, "HydrogenFuel", "HYDROGEN FUEL", "CHEMICALS", 140, "LIQUID HYDROGEN FOR REACTORS AND SHIP TANKS, REFINED FROM GAS GIANT CLOUDS."),
        Commodity(2, "Grain", "GRAIN", "FOODS", 260, "HYDROPONIC WHEAT AND RICE IN VACUUM-SEALED BALES."),
        Commodity(3, "SyntheticMeat", "SYNTHETIC MEAT", "FOODS", 340, "CULTURED PROTEIN, DEEP FROZEN. CHEAPER THAN THE REAL THING AND NOBODY ASKS."),
        Commodity(4, "ConstructionAlloys", "CONSTRUCTION ALLOYS", "METALS", 540, "STRUCTURAL BEAMS AND HULL PLATE FOR STATIONS AND SHIPYARDS."),
        Commodity(5, "Copper", "COPPER", "METALS", 680, "REFINED CATHODES FOR WIRING AND POWER GRIDS."),
        Commodity(6, "ConsumerGoods", "CONSUMER GOODS", "CONSUMER ITEMS", 720, "CLOTHING, TOOLS AND HOUSEHOLD TECH FOR STATION MARKETS."),
        Commodity(7, "MedicalSupplies", "MEDICAL SUPPLIES", "MEDICINES", 920, "SURGICAL KITS, ANTIBIOTICS AND RADIATION TREATMENT."),
        Commodity(8, "Titanium", "TITANIUM", "METALS", 1060, "LIGHT, STRONG AND HEAT-PROOF. SHIPYARDS NEVER HAVE ENOUGH OF IT."),
        Commodity(9, "RoboticsParts", "ROBOTICS PARTS", "MACHINERY", 1420, "ACTUATORS, SERVO PACKS AND DRONE CHASSIS."),
        Commodity(10, "Microprocessors", "MICROPROCESSORS", "TECHNOLOGY", 1820, "RADIATION-HARDENED CHIPS FOR SHIP COMPUTERS AND HABITAT CONTROL."),
        Commodity(11, "RareIsotopes", "RARE ISOTOPES", "MINERALS", 2650, "SHIELDED CANISTERS OF REACTOR-GRADE ISOTOPES. HANDLE WITH CARE."),
        Commodity(12, "WaterIce", "WATER ICE", "RAW MATERIALS", 90, "DIRTY ICE CUT FROM FROZEN WORLDS. MELTED AND FILTERED, IT BECOMES WATER AND FUEL."),
        Commodity(13, "MineralOre", "MINERAL ORE", "RAW MATERIALS", 160, "SILICATES, BAUXITE AND RUTILE. THE START OF GLASS, CERAMICS AND ALLOYS."),
        Commodity(14, "MetalOre", "METAL ORE", "RAW MATERIALS", 190, "UNREFINED IRON AND NICKEL ORE, STRAIGHT FROM THE DRILL. FOUNDRIES BUY IT BY THE TON."),
        Commodity(15, "Helium3", "HELIUM-3", "GASES", 700, "FUSION FUEL SKIMMED FROM GAS GIANTS AND MOON DUST. EVERY REACTOR WANTS IT."),
        Commodity(16, "PreciousOre", "PRECIOUS ORE", "RAW MATERIALS", 1100, "ORE VEINED WITH GOLD, PLATINUM AND IRIDIUM. RARE, HEAVY AND WORTH THE TRIP."),
    )

    /** Market position of commodity [id], or -1 (unknown). No allocation. */
    fun commodityIndex(id: String?): Int {
        if (id == null) return -1
        for (c in COMMODITIES) if (c.id == id) return c.index
        return -1
    }

    fun commodity(id: String?): Commodity? {
        val i = commodityIndex(id)
        return if (i >= 0) COMMODITIES[i] else null
    }

    // ---------------------------------------------------------------- economies (:86)
    private fun table(vararg pairs: Pair<String, Double>): DoubleArray {
        val a = DoubleArray(COMMODITIES.size) { Double.NaN }
        for ((id, f) in pairs) a[commodityIndex(id).also { require(it >= 0) { id } }] = f
        return a
    }

    val ECONOMIES = arrayOf(
        EconomyType(
            "HighTech", "HIGH TECH",
            table("Microprocessors" to 0.72, "RoboticsParts" to 0.8, "MedicalSupplies" to 0.84),
            table(
                "Titanium" to 1.3, "Copper" to 1.26, "Grain" to 1.28, "SyntheticMeat" to 1.24, "Water" to 1.32,
                "ConsumerGoods" to 1.18, "RareIsotopes" to 1.22, "PreciousOre" to 1.3, "Helium3" to 1.28,
            ),
        ),
        EconomyType(
            "Industrial", "INDUSTRIAL",
            table("ConstructionAlloys" to 0.74, "ConsumerGoods" to 0.78, "RoboticsParts" to 0.88),
            table(
                "Titanium" to 1.24, "Copper" to 1.3, "HydrogenFuel" to 1.28, "Microprocessors" to 1.27, "Water" to 1.2,
                "Grain" to 1.18, "MetalOre" to 1.3, "MineralOre" to 1.3, "PreciousOre" to 1.18,
            ),
        ),
        EconomyType(
            "Extraction", "EXTRACTION",
            table(
                "Titanium" to 0.72, "Copper" to 0.74, "RareIsotopes" to 0.8, "Water" to 0.78, "MetalOre" to 0.7,
                "MineralOre" to 0.72, "WaterIce" to 0.72, "PreciousOre" to 0.82,
            ),
            table(
                "ConstructionAlloys" to 1.3, "Grain" to 1.32, "SyntheticMeat" to 1.3, "MedicalSupplies" to 1.26,
                "ConsumerGoods" to 1.22, "RoboticsParts" to 1.24,
            ),
        ),
        EconomyType(
            "Refinery", "REFINERY",
            table("HydrogenFuel" to 0.7, "Copper" to 0.86, "Titanium" to 0.88, "Helium3" to 0.78),
            table(
                "RoboticsParts" to 1.28, "Grain" to 1.26, "SyntheticMeat" to 1.28, "MedicalSupplies" to 1.22,
                "ConstructionAlloys" to 1.26, "ConsumerGoods" to 1.2, "MetalOre" to 1.32, "MineralOre" to 1.24,
                "WaterIce" to 1.28, "PreciousOre" to 1.22,
            ),
        ),
        EconomyType(
            "Agricultural", "AGRICULTURAL",
            table("Grain" to 0.7, "SyntheticMeat" to 0.74, "Water" to 0.82),
            table(
                "MedicalSupplies" to 1.28, "RoboticsParts" to 1.25, "ConsumerGoods" to 1.3, "HydrogenFuel" to 1.24,
                "Microprocessors" to 1.22, "ConstructionAlloys" to 1.2, "WaterIce" to 1.3, "MineralOre" to 1.15,
            ),
        ),
        EconomyType(
            "Service", "TOURISM",
            table("ConsumerGoods" to 0.86),
            table(
                "Grain" to 1.3, "SyntheticMeat" to 1.32, "Water" to 1.25, "MedicalSupplies" to 1.24,
                "RareIsotopes" to 1.18, "Helium3" to 1.2,
            ),
        ),
    )

    /** The economy [id] ("HighTech" ..), or null. */
    fun economy(id: String?): EconomyType? {
        if (id == null) return null
        for (e in ECONOMIES) if (e.id == id) return e
        return null
    }

    /** As getPrice does: an unknown economy prices like Industrial. */
    fun economyOrIndustrial(id: String?): EconomyType = economy(id) ?: ECONOMIES[1]

    /** `upper(Economies[id].Name)` ("HIGH TECH", "TOURISM" for Service); an unknown id reads as Industrial. */
    fun economyName(id: String?): String = economyOrIndustrial(id).name

    // ---------------------------------------------------------------- prices (:129)
    private const val HASH_MOD = 4294967291L
    private const val HASH_RANGE = 100003L

    /** `hash01(text)`: a fixed number in [0, 1) from a text (djb2 mod 4294967291, then mod 100003). */
    fun hash01(text: CharSequence): Double = finish01(Lua.hash(5381L, 33L, HASH_MOD, text))

    private fun finish01(h: Long): Double = (h % HASH_RANGE).toDouble() / HASH_RANGE.toDouble()

    /** hash01(station .. "|" .. item .. "|" .. step), streamed. */
    private fun hashStep(stationId: CharSequence, commodityId: CharSequence, step: Long): Double {
        var h = Lua.hash(5381L, 33L, HASH_MOD, stationId)
        h = Lua.hashByte(h, 33L, HASH_MOD, '|'.code)
        h = Lua.hash(h, 33L, HASH_MOD, commodityId)
        h = Lua.hashByte(h, 33L, HASH_MOD, '|'.code)
        h = Lua.hashInt(h, 33L, HASH_MOD, step)
        return finish01(h)
    }

    /**
     * `drift(station, item, now)`: the slow price swing, up to ±[DRIFT_AMOUNT], a new target every
     * [DRIFT_PERIOD] s blended with smoothstep. [now] in epoch seconds.
     */
    fun drift(stationId: CharSequence, commodityId: CharSequence, now: Double): Double {
        val t = now / DRIFT_PERIOD
        val step = floor(t)
        val f = t - step
        val s = step.toLong()
        val a = hashStep(stationId, commodityId, s) * 2 - 1
        val b = hashStep(stationId, commodityId, s + 1) * 2 - 1
        val sm = f * f * (3 - 2 * f)
        return (a + (b - a) * sm) * DRIFT_AMOUNT
    }

    /**
     * `getPrice(station, economy, item, now)` into [out]: buy = max(1, round(mid·1.04)), sell =
     * max(1, round(mid·0.96)), mid = base · factor · (1 + drift), rounding half away from zero.
     */
    fun price(stationId: CharSequence, economyId: String?, commodity: Commodity, now: Double, out: Price): Price {
        val economy = economyOrIndustrial(economyId)
        val i = commodity.index
        val mid = commodity.base * economy.factor(i) * (1 + drift(stationId, commodity.id, now))
        out.buy = max(1.0, Lua.round(mid * (1 + SPREAD))).toInt()
        out.sell = max(1.0, Lua.round(mid * (1 - SPREAD))).toInt()
        when {
            !economy.produces[i].isNaN() -> {
                out.supply = HIGH; out.demand = LOW
            }
            !economy.consumes[i].isNaN() -> {
                out.supply = LOW; out.demand = HIGH
            }
            else -> {
                out.supply = MED; out.demand = MED
            }
        }
        return out
    }

    /** [price] by commodity id; false (and [out] untouched) for an unknown commodity, as getPrice returns nil. */
    fun price(stationId: CharSequence, economyId: String?, commodityId: String?, now: Double, out: Price): Boolean {
        val c = commodity(commodityId) ?: return false
        price(stationId, economyId, c, now, out)
        return true
    }

    /** `getMarket(station, economy, now)`: every row of [out] in market order (holds and cargo untouched). */
    fun market(stationId: String, economyId: String?, now: Long, out: Market): Market {
        val p = scratchPrice
        for (row in out.rows) {
            price(stationId, economyId, row.commodity, now.toDouble(), p)
            row.buy = p.buy
            row.sell = p.sell
            row.supply = p.supply
            row.demand = p.demand
        }
        out.stationId = stationId
        out.economyId = economyId
        out.now = now
        out.valid = true
        return out
    }

    private val scratchPrice = Price()

    // ---------------------------------------------------------------- survey values (:192)
    val SURVEY_VALUES: Map<String, Int> = linkedMapOf(
        "Terrestrial World" to 12000,
        "Ocean World" to 7500,
        "Greenhouse World" to 3200,
        "Gas Giant" to 2600,
        "Ice Giant" to 2300,
        "Volcanic Body" to 1400,
        "Ice World" to 1100,
        "Rocky Body" to 900,
        "Moon" to 500,
        "Star" to 1800,
    )
    const val SURVEY_DEFAULT = 800
    val SURVEY_STAR_FACTOR: Map<String, Double> = linkedMapOf(
        "M" to 1.0, "K" to 1.1, "G" to 1.25, "F" to 1.45, "A" to 1.8, "B" to 2.6, "O" to 4.0,
    )
    const val SURVEY_RING_BONUS = 1.35
    const val SURVEY_LANDABLE_BONUS = 1.2
    const val SURVEY_HOME_FACTOR = 0.3
    const val SURVEY_FIRST_BONUS = 1.5

    /**
     * `surveyValue(body)`: class base (or 800), stars × spectral factor (class G when unknown), ×1.35
     * ringed, ×1.2 landable, ×0.3 home (Sol); `max(10, round(base / 10) · 10)`.
     */
    fun surveyValue(cls: String?, starClass: String?, ring: Boolean, landable: Boolean, home: Boolean): Long {
        var base: Double = (SURVEY_VALUES[cls] ?: SURVEY_DEFAULT).toDouble()
        if (cls == "Star") base *= SURVEY_STAR_FACTOR[starClass ?: "G"] ?: 1.0
        if (ring) base *= SURVEY_RING_BONUS
        if (landable) base *= SURVEY_LANDABLE_BONUS
        if (home) base *= SURVEY_HOME_FACTOR
        return max(10.0, Lua.round(base / 10) * 10).toLong()
    }

    /** `math.round(value · factor / 10) · 10`: a perk or the first-survey bonus on a survey value (ShipCommerce :438, :448). */
    fun scaleValue(value: Long, factor: Double): Long = (Lua.round(value * factor / 10) * 10).toLong()

    // ---------------------------------------------------------------- ships (:236, ShipConfig.Ships)
    const val PERK_SURVEY_VALUE = 0
    const val PERK_SCAN_RANGE = 1
    const val PERK_FUEL_BURN = 2
    const val PERK_SCOOP_RATE = 3
    const val PERK_MINE_SPEED = 4

    /** ShipConfig perk names by index ("SurveyValue" ..). */
    val PERK_NAMES = arrayOf("SurveyValue", "ScanRange", "FuelBurn", "ScoopRate", "MineSpeed")

    private fun perks(surveyValue: Double = 1.0, scanRange: Double = 1.0, fuelBurn: Double = 1.0, scoopRate: Double = 1.0, mineSpeed: Double = 1.0) =
        doubleArrayOf(surveyValue, scanRange, fuelBurn, scoopRate, mineSpeed)

    /** The shipyard, in ShipEconomy order (= ShipCatalog.all order). */
    val SHIPS = arrayOf(
        ShipType(
            "Varrow", ShipCatalog.all[0],
            "QUICK, FORGIVING AND CHEAP TO RUN. THE VARROW CARRIES A LITTLE CARGO, SURVEYS ANYTHING AND GETS NEW PILOTS HOME.",
            cargoCapacity = 16, designMass = 370, fuelMass = 32, topSpeed = 260, boostSpeed = 340, pitchRate = 38, yawRate = 20,
        ),
        ShipType(
            "Torrin", ShipCatalog.all[1],
            "THE FASTEST SMALL SHIP IN THE YARD: TWIN OUTRIGGER ENGINES, A LOW CANOPY AND A 4 T HOLD. A PILOT'S SHIP FOR URGENT RUNS.",
            cargoCapacity = 4, designMass = 275, fuelMass = 28, topSpeed = 300, boostSpeed = 400, pitchRate = 40, yawRate = 21,
            perks = perks(fuelBurn = 0.85),
        ),
        ShipType(
            "Calder", ShipCatalog.all[2],
            "QUICKER THAN A VARROW AND SHARPER IN THE TURN: THREE ENGINES, A 12 T HOLD AND A WIDE BRIDGE. NO SPECIALITY, NO WEAKNESS.",
            cargoCapacity = 12, designMass = 363, fuelMass = 40, topSpeed = 270, boostSpeed = 352, pitchRate = 36, yawRate = 19,
        ),
        ShipType(
            "Lochran", ShipCatalog.all[3],
            "A LONG-RANGE SURVEYOR FROM MERE & CAIRN: A WRAPAROUND CANOPY, A SENSOR DOME, AND A BUNK AND GALLEY ON BOARD.",
            cargoCapacity = 8, designMass = 356, fuelMass = 48, topSpeed = 280, boostSpeed = 365, pitchRate = 34, yawRate = 18,
            perks = perks(surveyValue = 1.5, scanRange = 1.5, fuelBurn = 0.6, scoopRate = 1.5),
        ),
        ShipType(
            "Brannock", ShipCatalog.all[4],
            "FOUR TIMES THE HOLD OF A VARROW ON A HEAVY ARMOURED SPINE. SLOWER TO TURN, BUT EVERY TRADE RUN PAYS FOUR TIMES OVER.",
            cargoCapacity = 64, designMass = 760, fuelMass = 64, topSpeed = 205, boostSpeed = 285, pitchRate = 24, yawRate = 13,
        ),
        ShipType(
            "Kessock", ShipCatalog.all[5],
            "A WORKING PROSPECTOR FROM TALLAN DEEPWORKS: TWIN CUTTING LASERS, A ROOFTOP REFINERY AND SIX ORE BUNKERS.",
            cargoCapacity = 96, designMass = 930, fuelMass = 80, topSpeed = 190, boostSpeed = 255, pitchRate = 20, yawRate = 12,
            perks = perks(mineSpeed = 2.0),
        ),
        ShipType(
            "Dornoch", ShipCatalog.all[6],
            "AN ARMOURED WORKHORSE FROM ARDLAW HEAVY YARDS: 64 T ON A RACKED SPINE, EIGHT NOZZLES. HAULS, MINES AND SURVEYS.",
            cargoCapacity = 64, designMass = 1086, fuelMass = 96, topSpeed = 225, boostSpeed = 295, pitchRate = 22, yawRate = 13,
            perks = perks(mineSpeed = 1.4, scanRange = 1.25),
        ),
        ShipType(
            "Morven", ShipCatalog.all[7],
            "THE BIGGEST HOLD IN THE YARDS: 224 T BELOW, A CREW DECK WITH QUARTERS, MESS AND SICKBAY ABOVE. BUILT TO LAST.",
            cargoCapacity = 224, designMass = 1640, fuelMass = 128, topSpeed = 170, boostSpeed = 230, pitchRate = 15, yawRate = 9,
        ),
    )

    /** The starter ship (the profile template's ActiveShip). */
    const val DEFAULT_SHIP = "Varrow"

    /** Place of ship [id] in [SHIPS] (= the ShipCatalog index the renderer uses), or -1. */
    fun shipIndex(id: String?): Int {
        if (id == null) return -1
        for (i in SHIPS.indices) if (SHIPS[i].id == id) return i
        return -1
    }

    fun ship(id: String?): ShipType? {
        val i = shipIndex(id)
        return if (i >= 0) SHIPS[i] else null
    }

    /** `ShipConfig.perk(ship, name)`: the perk factor, 1 when the ship has none (or is unknown). */
    fun perk(shipId: String?, perk: Int): Double = ship(shipId)?.perks?.get(perk) ?: 1.0

    /** Shipyard perk line value: `round((perk - 1) · 100)` percent (0 = not shown). */
    fun perkPercent(shipId: String?, perk: Int): Int = Lua.round((perk(shipId, perk) - 1) * 100).toInt()

    // ---------------------------------------------------------------- refuel preview
    /**
     * What the hub tile and the overview button show (ShipStationUi :546, :702); [Commerce.refuel]
     * decides. Port fix (DECISIONS 35) as in [Commerce.refuel]: a tank missing less than one unit
     * (but at least half of one) can be topped up; Roblox showed "NO CREDITS" there whatever the
     * credits.
     */
    class RefuelQuote {
        /** Units missing from a full tank. */
        var missing = 0.0

        /** True when less than half a unit is missing ("TANK FULL"). */
        var full = false

        /** Units the credits pay for, up to [missing]. */
        var units = 0.0

        /** `ceil(units · 4)` CR when [affordable], else 0. */
        var cost = 0L

        /** The credits pay for min(1, [missing]) units: the button is enabled (else "NO CREDITS"). */
        val affordable: Boolean get() = !full && (units >= 1.0 || units >= missing)
    }

    fun refuelQuote(fuel: Double, credits: Long, out: RefuelQuote): RefuelQuote {
        out.missing = (1 - fuel) * FUEL_CAPACITY
        out.full = out.missing < 0.5
        if (out.full) {
            out.units = 0.0
            out.cost = 0L
            return out
        }
        out.units = min(out.missing, floor(credits.toDouble() / FUEL_PRICE_PER_UNIT))
        out.cost = if (out.affordable) ceil(out.units * FUEL_PRICE_PER_UNIT).toLong() else 0L
        return out
    }

    // ---------------------------------------------------------------- display (:306)
    /**
     * `groupDigits(v)`: `floor(|v| + 0.5)` with "," every three digits; negatives keep their "-".
     * Port fix (DECISIONS 35): a value that rounds to 0 prints "0", not Roblox's "-0".
     */
    fun groupDigits(v: Double): String = appendGroupDigits(TextBuf(32), v).toString()

    /** `formatCredits(v)` = groupDigits + " CR". */
    fun formatCredits(v: Double): String = appendCredits(TextBuf(36), v).toString()

    fun groupDigits(v: Long): String = groupDigits(v.toDouble())
    fun formatCredits(v: Long): String = formatCredits(v.toDouble())

    /** [groupDigits] into [out] (no allocation). */
    fun appendGroupDigits(out: TextBuf, v: Double): TextBuf {
        val n = floor(abs(v) + 0.5).toLong()
        if (v < 0 && n > 0) out.append('-')
        var div = 1L
        var digits = 1
        while (n / div >= 10L) {
            div *= 10L
            digits++
        }
        while (div > 0L) {
            out.append('0' + ((n / div) % 10L).toInt())
            digits--
            if (digits > 0 && digits % 3 == 0) out.append(',')
            div /= 10L
        }
        return out
    }

    /** [formatCredits] into [out] (no allocation). */
    fun appendCredits(out: TextBuf, v: Double): TextBuf = appendGroupDigits(out, v).append(" CR")
}
