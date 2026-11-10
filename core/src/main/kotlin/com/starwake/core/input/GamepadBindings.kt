package com.starwake.core.input

import com.starwake.core.Haptics
import com.starwake.core.Settings
import com.starwake.core.math.clamp
import com.starwake.core.ui.UiHost
import kotlin.math.abs

/**
 * The one gamepad map of 01-core-loop.md 8.2 (reconciled from 02, 06, 07, 08, 09) for flight:
 *
 * | Input | Tap | Hold 0.5 s |
 * |---|---|---|
 * | left stick | aim (pitch, roll/yaw) | |
 * | right stick | X key axis, Y vertical thrust; with L3 held: look around (up to 180°/s) | |
 * | LT / RT | lever down / up at 25 %/s × trigger (the W/S ramp) | |
 * | LB / RB | strafe left / right | |
 * | L3 | | look modifier |
 * | R3 | | full stop |
 * | A | boost | |
 * | X | Flight Assist | |
 * | Y | view | leave the seat |
 * | D-pad up | next target | clear target |
 * | D-pad down | ACTION (land, dock, cancel, launch: what the pill shows) | |
 * | D-pad left | cancel a plotted jump | survey / mine |
 * | D-pad right | autopilot or auto-dock | |
 * | Select | map | station panel |
 *
 * Start (pause) and B (back) belong to the game's system navigation and are not handled here.
 * A button without a hold action fires on the press; one with a hold action fires its tap on the
 * release (before 0.5 s) and its hold once at 0.5 s. GL thread; no allocation.
 */
class GamepadBindings(private val pad: GamepadState, private val settings: Settings) {
    private val tapAction = arrayOfNulls<Action>(GamepadState.BUTTONS)
    private val holdAction = arrayOfNulls<Action>(GamepadState.BUTTONS)
    private val active = BooleanArray(GamepadState.BUTTONS)
    private val heldTime = FloatArray(GamepadState.BUTTONS)
    private val holdFired = BooleanArray(GamepadState.BUTTONS)

    init {
        bind(GamepadState.A, Action.BOOST)
        bind(GamepadState.X, Action.TOGGLE_FLIGHT_ASSIST)
        bind(GamepadState.Y, Action.EXTERNAL_VIEW, Action.LEAVE_SEAT)
        bind(GamepadState.DPAD_UP, Action.CYCLE_TARGET, Action.CLEAR_TARGET)
        bind(GamepadState.DPAD_LEFT, Action.CANCEL_JUMP, Action.SURVEY)
        bind(GamepadState.DPAD_RIGHT, Action.AUTOPILOT)
        bind(GamepadState.SELECT, Action.SYSTEM_MAP, Action.STATION_PANEL)
        // D-pad down: the contextual ACTION, resolved from InputFrame.contextAction each frame
    }

    private fun bind(button: Int, tap: Action, hold: Action? = null) {
        tapAction[button] = tap
        holdAction[button] = hold
    }

    /** Tap action of [button] (tests, labels). */
    fun tapOf(button: Int): Action? = tapAction[button]

    /** Hold action of [button] (tests, labels). */
    fun holdOf(button: Int): Action? = holdAction[button]

    /**
     * Writes the pad's flight input into [f]: the triggers are Roblox's W/S ([InputFrame.throttleAxis],
     * RT − LT: the thrust ramps the lever by it at 25 %/s, 02 9.2). [host] gets the hold ticks.
     */
    fun write(f: InputFrame, dt: Float, host: UiHost?) {
        if (!pad.connected) return
        var used = false
        val sens = settings.sensitivity
        val pitchSign = if (settings.pitchUpClimb) 1f else -1f
        // the platform passes the left stick past its noise band only (the aim: the flight model
        // applies Roblox's dead zone 0.20 and curve 1.7, 02 9.2) and dead-zones the right one
        val lx = pad.leftX
        val ly = pad.leftY
        val rx = pad.rightX
        val ry = pad.rightY
        if (lx != 0f || ly != 0f) {
            f.addAim(lx * sens, ly * sens * pitchSign)
            used = true
        }
        if (pad.isHeld(GamepadState.L3)) {
            // L3 + right stick looks around; the right stick stops yawing meanwhile (8.2)
            f.lookHeld = true
            f.hold(Action.LOOK)
            f.lookX += rx * LOOK_RATE * dt
            f.lookY += -ry * LOOK_RATE * dt * pitchSign
            used = true
        } else {
            if (rx != 0f) f.keyAxis = clamp(f.keyAxis - rx * sens, -1f, 1f)
            if (ry != 0f) f.lateralY = clamp(f.lateralY - ry, -1f, 1f)
            if (rx != 0f || ry != 0f) used = true
        }
        if (pad.isHeld(GamepadState.LB)) {
            f.lateralX = clamp(f.lateralX - 1f, -1f, 1f)
            f.hold(Action.STRAFE_LEFT)
            used = true
        }
        if (pad.isHeld(GamepadState.RB)) {
            f.lateralX = clamp(f.lateralX + 1f, -1f, 1f)
            f.hold(Action.STRAFE_RIGHT)
            used = true
        }
        if (pad.isHeld(GamepadState.R3)) {
            f.hold(Action.FULL_STOP)
            used = true
        }
        // the triggers are W and S: the thrust ramps the lever by their difference (ThrottleRampTime 4 s)
        val thr = pad.rightTrigger - pad.leftTrigger
        if (abs(thr) > TRIGGER_DEAD) {
            f.throttleAxis = clamp(f.throttleAxis + thr, -1f, 1f)
            f.hold(if (thr > 0f) Action.THROTTLE_UP else Action.THROTTLE_DOWN)
            used = true
        }
        // buttons
        for (b in 0 until GamepadState.BUTTONS) {
            val tap = if (b == GamepadState.DPAD_DOWN) f.contextAction else tapAction[b]
            val hold = holdAction[b]
            if (b != GamepadState.DPAD_DOWN && tap == null) continue
            val pressed = pad.consumePress(b)
            val held = pad.isHeld(b)
            if (pressed) {
                used = true
                active[b] = true
                heldTime[b] = 0f
                holdFired[b] = false
                if (hold == null) {
                    // single meaning: act on the press
                    if (tap != null) f.press(tap)
                    if (!held) active[b] = false
                    continue
                }
            }
            if (!active[b]) continue
            if (held) {
                heldTime[b] += dt
                if (hold != null && !holdFired[b] && heldTime[b] >= HOLD_TIME) {
                    holdFired[b] = true
                    f.press(hold)
                    host?.haptic(Haptics.TICK)
                }
                if (hold != null && holdFired[b] && hold.hold) f.hold(hold)
            } else {
                // released: a short press is a tap
                if (hold != null && !holdFired[b] && tap != null) f.press(tap)
                active[b] = false
            }
        }
        if (used) f.device = InputFrame.GAMEPAD
    }

    /** Forgets held buttons (the screen changed; presses are discarded by the game). */
    fun reset() {
        active.fill(false)
        holdFired.fill(false)
    }

    companion object {
        /** Tap or hold: a press held this long (s) is a hold (06 9.1, 01 8.2). */
        const val HOLD_TIME = 0.5f

        /** Look rate at full right-stick deflection with L3 held, degrees per second (01 8.1.1). */
        const val LOOK_RATE = 180f

        private const val TRIGGER_DEAD = 0.05f
    }
}
