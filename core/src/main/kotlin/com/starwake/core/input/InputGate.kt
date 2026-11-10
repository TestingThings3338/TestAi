package com.starwake.core.input

import com.starwake.core.game.Branch
import com.starwake.core.game.Overlay
import com.starwake.core.game.PlayerMode

/**
 * The input gating matrix of 01-core-loop.md 4.5 in its port version: one function of the
 * [PlayerMode] instead of Roblox's scattered `Runtime.Deck`/`Runtime.Aboard`, `processed` and
 * text-box checks, with the input owner of 8.1.2 in place of mouse capture.
 *
 * Columns of the Roblox table and how they read here: free flight; warp travel ([PlayerMode.travelling]);
 * controlled ([PlayerMode.controlled]: docking computer, guidance); map open ([Overlay.MAP]);
 * services open ([Overlay.SERVICES]); deck ([Branch.DECK]); aboard ([PlayerMode.onFoot]);
 * cinematic ([Branch.DESTROYED]). The game adds the pausing overlays and the LOADING curtain, under
 * which only [Action.PAUSE], [Action.BACK] and [Action.TOGGLE_MUSIC] apply.
 *
 * The handlers still check their own conditions (an autopilot needs a lock, a jump can only be
 * cancelled while plotted): the gate only answers "may this input reach its handler now".
 */
object InputGate {
    /** Nobody takes touches (LOADING curtain, crash cinematic, warp tunnel): only PAUSE works. */
    const val OWNER_NONE = 0

    /** The flight controls: lever, stick, buttons, look strip. */
    const val OWNER_FLIGHT = 1

    /** A full-screen overlay (map, services, pause, settings): the flight controls are hidden and released. */
    const val OWNER_OVERLAY = 2

    /** The walker on a deck or aboard (`09`). */
    const val OWNER_WALKER = 3

    /** Who owns the touches this frame (01 8.1.2). */
    fun owner(m: PlayerMode): Int = when {
        m.overlay.fullScreen -> OWNER_OVERLAY
        m.curtain || m.branch == Branch.DESTROYED || m.travelling -> OWNER_NONE
        m.branch == Branch.DECK || m.onFoot -> OWNER_WALKER
        else -> OWNER_FLIGHT
    }

    /** The thrust reads its inputs (C4): not in warp travel, not controlled, not on foot, flying. */
    fun thrustEnabled(m: PlayerMode): Boolean =
        m.branch == Branch.FLIGHT && !m.travelling && !m.controlled && !m.onFoot && !m.curtain && !m.overlay.pauses

    /** The pilot's aim and key axis reach the ship (C7: zeroed while travelling, controlled or on foot). */
    fun steeringEnabled(m: PlayerMode): Boolean = thrustEnabled(m)

    /** Whether [a] may reach its handler in mode [m]. */
    fun allows(a: Action, m: PlayerMode): Boolean {
        // always: the system keys and the music switch (works on decks, aboard and in the cinematic)
        if (a == Action.PAUSE || a == Action.BACK || a == Action.TOGGLE_MUSIC) return true
        if (m.curtain || m.overlay.pauses) return false
        val cinematic = m.branch == Branch.DESTROYED
        val deck = m.branch == Branch.DECK
        val foot = m.onFoot
        val flying = !deck && !foot && !cinematic
        val services = m.overlay == Overlay.SERVICES
        return when (a) {
            // thrust keys: only with thrust input enabled (a map leaves the keyboard flying)
            Action.THROTTLE_UP, Action.THROTTLE_DOWN, Action.STRAFE_LEFT, Action.STRAFE_RIGHT,
            Action.STRAFE_UP, Action.STRAFE_DOWN, Action.FULL_STOP,
            -> flying && !m.travelling && !m.controlled
            // Q/E: zeroed while travelling or controlled (they still count as steering, see the session)
            Action.YAW_LEFT, Action.YAW_RIGHT -> flying && !m.travelling && !m.controlled
            // Z works in travel and under the docking computer; B is cleared each controlled frame (C5)
            Action.TOGGLE_FLIGHT_ASSIST -> flying
            Action.BOOST -> flying && !m.travelling && !m.controlled
            Action.CYCLE_TARGET, Action.CLEAR_TARGET, Action.SYSTEM_MAP, Action.STAR_MAP,
            Action.EXTERNAL_VIEW, Action.DOCK, Action.AUTOPILOT, Action.LAND,
            -> flying
            Action.CONFIRM_JUMP -> flying && m.overlay == Overlay.MAP
            Action.CANCEL_JUMP -> flying && !m.travelling
            Action.SURVEY, Action.MINE -> flying && !m.travelling && !m.controlled && !services
            // the station panel works on decks too, never aboard, only after the reveal and not in a jump
            Action.STATION_PANEL -> !foot && !cinematic && !m.travelling && m.revealed
            // leaving the seat: also in a jump; on a deck only in the hangar (the deck module decides)
            Action.LEAVE_SEAT -> !foot && !cinematic && !m.controlled && !services
            Action.CENTER_CURSOR, Action.RELEASE_MOUSE -> !foot && !cinematic
            // looking around: not under a map (Roblox frees the mouse); aboard it drives the walker's camera
            Action.LOOK -> !cinematic && m.overlay != Overlay.MAP
            Action.INTERACT -> foot || deck
            Action.PAUSE, Action.BACK, Action.TOGGLE_MUSIC -> true
        }
    }

    /**
     * Applies the matrix to [f]: [filterActions] and [gateAxes]. The session runs the two halves at
     * their own steps: the key handlers at C1 with the mode as it stands, the axes at C7 with this
     * frame's flags, after its "steering" test (the pilot's deflection takes over the docking
     * computer even though it does not steer).
     */
    fun apply(f: InputFrame, m: PlayerMode) {
        filterActions(f, m)
        gateAxes(f, m)
    }

    /** Drops every press and hold [m] does not allow (and the look when looking is not allowed). */
    fun filterActions(f: InputFrame, m: PlayerMode) {
        for (i in 0 until Action.COUNT) {
            val a = Action.of(i)
            if (!allows(a, m)) f.drop(a)
        }
        if (!allows(Action.LOOK, m)) {
            f.lookX = 0f
            f.lookY = 0f
            f.lookHeld = false
        }
    }

    /** Zeroes the flight axes when thrust and steering input are disabled (C4, C7). */
    fun gateAxes(f: InputFrame, m: PlayerMode) {
        if (!thrustEnabled(m)) f.clearFlightAxes()
    }
}
