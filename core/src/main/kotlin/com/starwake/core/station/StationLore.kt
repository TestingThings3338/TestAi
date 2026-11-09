package com.starwake.core.station

import com.starwake.core.econ.Lua
import com.starwake.core.math.LuaRandom

/**
 * Port of ShipCore/ShipStationLore.lua (the station-services part): accent colours and operators
 * per station, the news ticker lines, and the name and blurb lists of generated stations
 * (ShipStations.lua :88-:112, Appendix B.2, B.3, B.7, B.8). Decks lore (titles, moods, bar, shops)
 * belongs to the decks area.
 *
 * Colours are packed 0xRRGGBB (sRGB bytes; StationInstance.setAccent takes the three bytes).
 * Nothing here allocates.
 */
object StationLore {
    /** Hand-made Sol stations: id, accent, operator. */
    private val SOL_IDS = arrayOf("SOL-MERIDIAN", "SOL-TRANQUILITY", "SOL-ARES", "SOL-GALILEAN", "SOL-TITAN", "SOL-HELIOS")
    private val SOL_ACCENTS = intArrayOf(0x50AAFF, 0xFFAA46, 0xFF6046, 0xFAD25A, 0x6EE68C, 0xFF8228)
    private val SOL_OPERATORS = arrayOf(
        "HALDEN-MERIDIAN TRUST", "TRANQUIL YARDS COLLECTIVE", "ARES DEEP MINING CO.",
        "GALILEAN FUEL COMBINE", "TITAN GREENWORKS", "HELIOS FOUNDRY GROUP",
    )

    /** Accents of generated (and player) stations: (80,170,255), (255,170,70), (110,230,140), (190,120,255), (70,220,220), (255,110,150). */
    val ACCENTS = intArrayOf(0x50AAFF, 0xFFAA46, 0x6EE68C, 0xBE78FF, 0x46DCDC, 0xFF6E96)

    /**
     * Operators of generated (and player) stations (Appendix B.8). The sixth is renamed from Roblox's
     * "OUTER RIM SURVEY TRUST" (DECISIONS 30: "Outer Rim" is another franchise's term).
     */
    val OPERATORS = arrayOf(
        "FRONTIER HAULERS UNION", "DEEP RANGE CHARTER", "KESSLER LANE AUTHORITY", "BRIGHTWATER COOPERATIVE",
        "NORTHSTAR LOGISTICS", "FAR REACH SURVEY TRUST",
    )

    /** `hash(text)`: h = 7, h = (h·31 + byte) % 1000003 over the UTF-8 bytes ("?" for a missing id). */
    fun hash(stationId: String?): Long = Lua.hash(7L, 31L, 1_000_003L, stationId ?: "?")

    private fun solIndex(stationId: String?): Int {
        if (stationId == null) return -1
        for (i in SOL_IDS.indices) if (SOL_IDS[i] == stationId) return i
        return -1
    }

    /** `get(id).Accent`: a Sol station's own colour, else `ACCENTS[h % 6 + 1]` (0xRRGGBB). */
    fun accent(stationId: String?): Int {
        val s = solIndex(stationId)
        if (s >= 0) return SOL_ACCENTS[s]
        return ACCENTS[(hash(stationId) % ACCENTS.size).toInt()]
    }

    /** `get(id).Operator`: a Sol station's operator, else `OPERATORS[(h // 7) % 6 + 1]`. */
    fun operator(stationId: String?): String {
        val s = solIndex(stationId)
        if (s >= 0) return SOL_OPERATORS[s]
        return OPERATORS[((hash(stationId) / 7) % OPERATORS.size).toInt()]
    }

    fun red(rgb: Int): Int = (rgb shr 16) and 0xFF
    fun green(rgb: Int): Int = (rgb shr 8) and 0xFF
    fun blue(rgb: Int): Int = rgb and 0xFF

    // ---------------------------------------------------------------- news ticker (:104)
    /** General news; the hub ticker shows four of them, shuffled, after the market tip (Appendix B.7). */
    val NEWS = arrayOf(
        "SOL TRANSIT AUTHORITY REMINDS PILOTS: SLOW DOWN INSIDE THE DOCKING ZONE.",
        "HELIX CARTOGRAPHIC GUILD: UNCHARTED SYSTEMS PAY UP TO THREE TIMES MORE THAN SOL.",
        "THE LONG ORBIT HOSTS OPEN MIC NIGHT EVERY TENTH CYCLE.",
        "OKORO-BRANDT HEAVY INDUSTRIES ANNOUNCES LONGER WARRANTIES ON THE BRANNOCK FREIGHTER.",
        "HALDEN DRIVE WORKS: THE VARROW REMAINS THE MOST FLOWN STARTER SHIP IN SOL.",
        "PILOTS ARE ASKED TO KEEP HANGAR LIFTS CLEAR DURING DESCENT.",
        "STATION CLOCKS RUN ON SOL STANDARD TIME.",
    )

    /** How many news lines the ticker shows (ShipStationUi.buildTicker). */
    const val TICKER_NEWS = 4

    /**
     * ShipStationUi.buildTicker's selection: a Fisher-Yates shuffle of [NEWS] (`math.random(i)` =
     * `rng.nextInteger(1, i)`, from the end) into [out] (size ≥ NEWS.size); the ticker shows the
     * first [TICKER_NEWS]. Returns [out].
     */
    fun shuffleNews(rng: LuaRandom, out: Array<String?>): Array<String?> {
        for (i in NEWS.indices) out[i] = NEWS[i]
        for (i in NEWS.size downTo 2) {
            val j = rng.nextInteger(1, i)
            val t = out[i - 1]
            out[i - 1] = out[j - 1]
            out[j - 1] = t
        }
        return out
    }

    /**
     * Length of a news line's sender, the text before ":" that the ticker colours Accent (pattern
     * `^([^:]+):%s*(.+)$`), or 0 when the line has none. The rest starts at [newsBodyStart].
     */
    fun newsSenderLength(line: String): Int {
        val colon = line.indexOf(':')
        // "[^:]+" needs a sender, "(.+)" at least one character after the colon
        return if (colon <= 0 || colon + 1 >= line.length) 0 else colon
    }

    /** Index where a sender line's text starts: after ":" and its spaces, keeping one character as "(.+)" does. */
    fun newsBodyStart(line: String, senderLength: Int = newsSenderLength(line)): Int {
        if (senderLength <= 0) return 0
        var i = senderLength + 1
        while (i < line.length - 1 && line[i].isWhitespace()) i++
        return i
    }

    // ---------------------------------------------------------------- generated stations (ShipStations :88-:112)
    /** Family names for generated stations (Appendix B.3), drawn with NextInteger(1, 45). */
    val NAMES = arrayOf(
        "ARDEN", "BECKETT", "CALLOWAY", "EKLUND", "FARROW", "GALEN", "HOLLIS", "IVERS", "JANSEN", "KOVAC",
        "LINDQVIST", "MARLOW", "NAKAMURA", "ORSINI", "PETROV", "QUILLON", "RAVEL", "SATO", "THORNE", "ULRIC",
        "VANCE", "WELLER", "YATES", "ZELLER", "ADEYEMI", "BRANDT", "CASTELLAN", "DELACROIX", "ESPOSITO",
        "FAIRBANKS", "GRIEVE", "HARTMANN", "IMRE", "JUAREZ", "KESSLER", "LAINE", "MOREAU", "NOVAK", "ODUYA",
        "PALMQVIST", "RENNER", "SOLBERG", "TANAKA", "VOSS", "WINTER",
    )

    /** Name suffixes by economy, drawn with NextInteger(1, 3). */
    fun suffixes(economy: String): Array<String> = when (economy) {
        "HighTech" -> SUFFIX_HIGH_TECH
        "Industrial" -> SUFFIX_INDUSTRIAL
        "Extraction" -> SUFFIX_EXTRACTION
        "Refinery" -> SUFFIX_REFINERY
        "Agricultural" -> SUFFIX_AGRICULTURAL
        else -> SUFFIX_SERVICE
    }

    private val SUFFIX_HIGH_TECH = arrayOf("STATION", "HUB", "EXCHANGE")
    private val SUFFIX_INDUSTRIAL = arrayOf("YARDS", "WORKS", "STATION")
    private val SUFFIX_EXTRACTION = arrayOf("DEEP", "DIGS", "STATION")
    private val SUFFIX_REFINERY = arrayOf("REFINERY", "SKIMWORKS", "DEPOT")
    private val SUFFIX_AGRICULTURAL = arrayOf("HABITAT", "GARDENS", "HYDROPONICS")
    private val SUFFIX_SERVICE = arrayOf("PORT", "TERMINAL", "RETREAT")

    /** Description of a generated station by economy (Appendix B.2). */
    fun blurb(economy: String): String = when (economy) {
        "HighTech" -> "LABS, CLEAN ROOMS AND CHIP FOUNDRIES. IT SELLS TECHNOLOGY AND BUYS FOOD AND METAL."
        "Industrial" -> "FOUNDRIES AND ASSEMBLY HALLS. IT SELLS ALLOYS AND GOODS AND BUYS METAL, FUEL AND CHIPS."
        "Extraction" -> "A MINING OUTPOST. ORE AND ICE GO OUT; FOOD, MEDICINE AND MACHINERY COME IN."
        "Refinery" -> "A GAS REFINERY. FUEL GOES OUT; FOOD, PARTS AND MEDICINE COME IN."
        "Agricultural" -> "GREENHOUSE DECKS. FOOD GOES OUT; MEDICINE, MACHINERY AND GOODS COME IN."
        else -> "A STOPOVER FOR CREWS AND TRAVELLERS. IT PAYS WELL FOR FOOD AND COMFORT."
    }
}
