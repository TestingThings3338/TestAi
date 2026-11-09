package com.starwake.core.game

/**
 * The steps of Roblox's per-frame order (`ShipUpdate`, `ShipClient.lua:2321-3196`) that the port
 * keeps, with the ids of 01-core-loop.md 2.2: F0 the dt cap, A the deck branch, B the destruction
 * branch, C the flight frame. Dropped Roblox-only steps (landing scene, floating origin, character
 * rig, multiplayer pilots, impostors, voxel terrain) have no entry. Declared in frame order: within a
 * branch a step's ordinal only ever grows, which is what [Session] and its trace test rely on.
 *
 * [owner] names the area doc that plugs its module into the step; "01" steps are the session's own.
 */
enum class FrameStep(val owner: String, val label: String) {
    F0("01", "dt = min(dt, 0.1)"),

    A2("02", "attitude hidden at once"),
    A3("09", "decks:update"),
    A4("09", "deck session ended: return"),
    A5("09", "parked ship pose, walking flag"),
    A7("01", "seated: input (look-around)"),
    A9("06", "parked cockpit state"),
    A10("06", "gear down, hull, head motion settles"),
    A11("01", "camera (seated)"),
    A12("05", "entry glow fades"),
    A13("01", "input owner"),
    A14("01", "notices, services, construction, voice"),

    B1("02", "attitude hidden"),
    B2("05", "destruction:update (camera, debris, plate)"),
    B3("01", "flight UI hidden"),
    B4("09", "traffic leaves"),
    B6("05", "entry glow fades"),
    B9("05", "warnings: blackout timeline"),
    B10("09", "voice"),

    C1("01", "input frame: look-around, key handlers"),
    C2("07", "travelling = warp travel"),
    C3("08", "controlled = docking computer or guidance"),
    C4("02", "thrust input enabled"),
    C5("02", "boost request cleared while controlled"),
    C6("01", "cursor recentred on travel start/end"),
    C7("02", "gated aim, key axis, steering"),
    C8("02", "handling"),
    C9("02", "autopilot: atmosphere disengage"),
    C10("02", "autopilot: controlled disengage"),
    C11("02", "autopilot compute"),
    C12("09", "nobody at the helm"),
    C13("02", "rotation"),
    C14("03", "orbits"),
    C15("03", "frame carry"),
    C16("02", "gravity state"),
    C19("02", "station, approach and edge limits"),
    C20("02", "zone label"),
    C21("02", "speed limit"),
    C22("02", "gravity"),
    C23("02", "thrust and move (sub-steps, collisions)"),
    C24("02", "boost heat"),
    C25("02", "field-edge clamp"),
    C26("05", "landing assist"),
    C27("02", "systems: fuel, heat, G"),
    C28("04", "heat death, ground contact"),
    C30("08", "stations: docking, hull collisions"),
    C31("09", "traffic"),
    C34("01", "speed, pitch angle, local up"),
    C35("02", "flight cursor visibility"),
    C36("06", "cockpit state"),
    C37("06", "hull and gear"),
    C38("02", "gear command"),
    C39("06", "cockpit motion"),
    C40("01", "camera"),
    C41("05", "entry effects"),
    C43("09", "audio"),
    C44("09", "Flight Assist voice"),
    C45("05", "warnings"),
    C46("09", "callouts"),
    C47("06", "markers"),
    C48("06", "dust"),
    C52("01", "input owner"),
    C53("08", "services looking"),
    C54("07", "system map"),
    C56("03", "texture streaming"),
    C58("07", "star map"),
    C59("07", "warp atmosphere guard"),
    C60("07", "warp"),
    C62("05", "atmosphere"),
    C63("05", "arrival card"),
    C64("09", "voice"),
    C65("03", "ring debris"),
    C66("06", "dust mood"),
    C67("06", "HUD state"),
    C68("02", "flight arrow"),
    C69("02", "attitude"),
    C70("08", "survey"),
    C71("08", "mining"),
    C72("08", "construction"),
    C73("08", "galaxy refresh"),
    C74("06", "notices"),
    C75("08", "station services"),
    C76("08", "autosave"),
    ;

    /** The branch the step belongs to: 'F', 'A', 'B' or 'C'. */
    val branch: Char get() = name[0]
}

/**
 * Records the steps one frame runs, in order (debug command "trace", the frame-order harness
 * scenario and SessionOrderTest). Preallocated; recording stores enum references only, so a
 * traced frame does not allocate either.
 */
class FrameTrace(capacity: Int = 256) {
    private val steps = arrayOfNulls<FrameStep>(capacity)

    var count = 0
        private set

    fun clear() {
        count = 0
    }

    fun add(s: FrameStep) {
        if (count < steps.size) steps[count++] = s
    }

    operator fun get(i: Int): FrameStep = steps[i]!!

    /** The recorded steps (allocates; tests and debug only). */
    fun toList(): List<FrameStep> = List(count) { steps[it]!! }

    /** "F0 C1 C2 ..." (allocates; debug only). */
    override fun toString(): String = toList().joinToString(" ") { it.name }
}
