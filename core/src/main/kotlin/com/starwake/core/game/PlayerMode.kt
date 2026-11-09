package com.starwake.core.game

/** Which of Roblox's three per-frame branches runs (`ShipClient.lua:2327-2344`, 01-core-loop.md 2.2.1). */
enum class Branch {
    /** Branch C: the flight frame. */
    FLIGHT,

    /** Branch A: on a station deck; the flight rests (`09`). */
    DECK,

    /** Branch B: the crash cinematic; the flight rests (`05`). */
    DESTROYED,
}

/**
 * The single overlay slot of the game (Roblox's exclusive screens). Only [PAUSE] and [SETTINGS]
 * freeze the simulation (DECISIONS 21): the maps and the station services are overlays the flight
 * keeps running under. [fullScreen] overlays take the touches from the flight controls (01 8.1.2).
 */
enum class Overlay(val pauses: Boolean, val fullScreen: Boolean) {
    NONE(false, false),
    MAP(false, true),
    SERVICES(false, true),
    PAUSE(true, true),
    SETTINGS(true, true),
}

/** Camera views: the Roblox cockpit (default) and external views, and the optional chase view (DECISIONS 21a). */
enum class ShipView {
    COCKPIT,
    EXTERNAL,
    CHASE,
}

/** The player states of 01-core-loop.md 4.3 (sub-states such as the autopilot live in their modules). */
enum class PlayerState {
    MENU,
    LOADING,
    FLYING,
    WARP_TRAVEL,
    DOCKING,
    DOCKED,
    LAUNCHING,
    ON_DECK,
    LANDING,
    LANDED,
    ABOARD,
    CRASHING,
    DESTROYED,
}

/**
 * The player's mode, derived once per frame (Roblox's `Runtime.Deck`/`Runtime.Aboard` flags and the
 * flags of the flight frame, C1-C7: `onFoot`, `travelling`, `controlled`, `busy`, `docked`,
 * `shipLanded`). The session writes its flags, the game writes [overlay] and [curtain]; the input
 * gate, the HUD and the camera read it. One instance per session, no allocation.
 */
class PlayerMode {
    var branch = Branch.FLIGHT

    /** LOADING: the black curtain is up (01 4.1); input waits for the reveal. */
    var curtain = false

    /** The cockpit came alive (Roblox `afterReveal`: notices enabled, services shown if docked). */
    var revealed = false

    /** Walking aboard the own ship (`09`). */
    var onFoot = false

    /** Warp TUNNEL or ARRIVE (`07`): the pilot has no controls. */
    var travelling = false

    /** A jump is plotted (warp ALIGN or COUNTDOWN). */
    var plotted = false

    /** The docking computer or a guidance flies the ship (C3): no thrust input, aim zeroed. */
    var controlled = false

    /** The docking computer is flying the ship to the pad or into the slot. */
    var docking = false

    /** Parked at a station. */
    var docked = false

    /** Leaving a station under the docking computer. */
    var launching = false

    /** The landing mode (the later landing assist, D14) is on. */
    var landing = false

    /** Resting on the ground (Roblox `shipLanded`). */
    var landed = false

    /** The crash fade runs (Roblox `warnings:isBusy()`). */
    var crashing = false

    var overlay = Overlay.NONE
    var view = ShipView.COCKPIT

    /** The pilot looks around (the flight cursor hides, C35). */
    var looking = false

    /** The state of the 4.3 table these flags describe (MENU is the game's, without a session). */
    val state: PlayerState
        get() = when {
            curtain -> PlayerState.LOADING
            branch == Branch.DESTROYED -> PlayerState.DESTROYED
            branch == Branch.DECK -> PlayerState.ON_DECK
            crashing -> PlayerState.CRASHING
            onFoot -> PlayerState.ABOARD
            travelling -> PlayerState.WARP_TRAVEL
            docked -> PlayerState.DOCKED
            launching -> PlayerState.LAUNCHING
            docking -> PlayerState.DOCKING
            landed -> PlayerState.LANDED
            landing -> PlayerState.LANDING
            else -> PlayerState.FLYING
        }

    /** The simulation runs this frame: no pausing overlay (PAUSE, SETTINGS). */
    val simulating: Boolean get() = !overlay.pauses

    /** Resets every flag for a new session (the view is the session's to set). */
    fun reset() {
        branch = Branch.FLIGHT
        curtain = false; revealed = false
        onFoot = false; travelling = false; plotted = false
        controlled = false; docking = false; docked = false; launching = false
        landing = false; landed = false; crashing = false
        overlay = Overlay.NONE
        looking = false
    }
}
