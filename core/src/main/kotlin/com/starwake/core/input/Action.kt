package com.starwake.core.input

/**
 * Every input the game knows (01-core-loop.md 8.1): the 28 Roblox keys of `ShipConfig.Keys`
 * (`ShipCore/ShipConfig.lua:2763`, in that order) plus [INTERACT] (E on foot), [LOOK] (right mouse
 * held), [PAUSE] and [BACK]. Touch, gamepad and an optional keyboard all produce these into an
 * [InputFrame]; [InputGate] decides which of them apply in the current [com.starwake.core.game.PlayerMode].
 *
 * [key] is the Roblox key (for logs and the later InputLabels); [hold] marks actions that act while
 * held (polled in Roblox) rather than on the press.
 */
enum class Action(val key: String, val hold: Boolean = false) {
    THROTTLE_UP("W", hold = true),
    THROTTLE_DOWN("S", hold = true),
    STRAFE_LEFT("A", hold = true),
    STRAFE_RIGHT("D", hold = true),
    STRAFE_UP("R", hold = true),
    STRAFE_DOWN("F", hold = true),
    YAW_LEFT("Q", hold = true),
    YAW_RIGHT("E", hold = true),
    FULL_STOP("X", hold = true),
    TOGGLE_FLIGHT_ASSIST("Z"),
    RELEASE_MOUSE("LeftAlt"),
    CENTER_CURSOR("C"),
    TOGGLE_MUSIC("J"),
    CYCLE_TARGET("T"),
    CLEAR_TARGET("Y"),
    SYSTEM_MAP("N"),
    STAR_MAP("M"),
    CONFIRM_JUMP("Return"),
    AUTOPILOT("P"),
    LAND("L"),
    DOCK("K"),
    EXTERNAL_VIEW("V"),
    CANCEL_JUMP("Backspace"),
    BOOST("B"),
    SURVEY("G", hold = true),
    MINE("H", hold = true),
    STATION_PANEL("U"),
    LEAVE_SEAT("One"),
    INTERACT("E"),
    LOOK("MouseButton2", hold = true),
    PAUSE("Esc"),
    BACK("Back"),
    ;

    companion object {
        /** Number of actions; [InputFrame] keeps one counter per ordinal. */
        const val COUNT = 32

        /** Cached copy of the entries: `values()` allocates a new array on every call. */
        private val all = values()

        /** The action with this ordinal (no allocation). */
        fun of(ordinal: Int): Action = all[ordinal]

        /** Parses an action by its enum name or Roblox key, case-insensitive ("cycle_target", "T"); null if unknown. Allocates (debug only). */
        fun parse(name: String): Action? {
            val n = name.trim().replace('-', '_')
            return all.firstOrNull { it.name.equals(n, ignoreCase = true) }
                ?: all.firstOrNull { it.key.equals(n, ignoreCase = true) }
        }
    }
}
