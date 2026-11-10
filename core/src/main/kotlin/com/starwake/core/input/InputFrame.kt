package com.starwake.core.input

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One frame of pilot input, built in one place from touch, gamepad (and later a keyboard) before
 * the session frame runs (01-core-loop.md 6, the `InputFrame` contract): the port of what Roblox's
 * ShipInput produces (`cursorPixels`, `keyAxis`, `look`) plus every key handler's edge. Reused every
 * frame: [begin] clears it, the devices write into it, [InputGate] removes what the current mode
 * does not allow, the session reads it. No allocation.
 *
 * Conventions follow Roblox: the aim is the pilot's cursor inside the unit circle with X right and
 * Y **down** (ShipInput.getNormalizedCursor), sensitivity ([stickResponse]) and INVERT PITCH
 * applied, no dead zone (the flight model applies its own); the key axis is +1 for YawLeft (Q).
 */
class InputFrame {
    /** Device the pilot used last ([TOUCH], [GAMEPAD], [KEYBOARD]); texts with button names follow it. */
    var device = TOUCH

    /** Pilot cursor, X right, Y down, inside the unit circle. */
    var aimX = 0f
    var aimY = 0f

    /** Key axis: +1 = YawLeft (Q), -1 = YawRight (E); analog on touch (lever sideways) and pad (right stick X). */
    var keyAxis = 0f

    /** True when the touch lever was moved (or reset) this frame: [lever] is its new absolute position. */
    var leverSet = false

    /** Absolute lever position (`MinThrottle` −0.25 .. 1, the session clamps it) while [leverSet]. */
    var lever = 0f

    /** Lever ramp from the pad triggers, -1..1 (RT - LT); the thrust ramps the throttle by it. */
    var throttleAxis = 0f

    /** Lateral thrusters: X +1 = right, Y +1 = up. */
    var lateralX = 0f
    var lateralY = 0f

    /** Look-around to add this frame, degrees: X right, Y up (touch strip drag, pad rate × dt). */
    var lookX = 0f
    var lookY = 0f

    /** The pilot holds the look control (a finger in the look strip, L3 on the pad): the view does not return. */
    var lookHeld = false

    /**
     * What the contextual ACTION control means this frame ([Action.LAND] or [Action.DOCK], null when
     * the pill is hidden): set by the game from the session before the devices write, so the touch
     * pill and the pad's D-pad down send the action their label shows.
     */
    var contextAction: Action? = null

    private val presses = IntArray(Action.COUNT)
    private var heldBits = 0L

    /** Clears everything for a new frame (the device and the context action stay). */
    fun begin() {
        aimX = 0f; aimY = 0f; keyAxis = 0f
        leverSet = false; lever = 0f; throttleAxis = 0f
        lateralX = 0f; lateralY = 0f
        lookX = 0f; lookY = 0f; lookHeld = false
        presses.fill(0)
        heldBits = 0L
    }

    /** Records one press (edge) of [a]. */
    fun press(a: Action) {
        presses[a.ordinal]++
    }

    /** Marks [a] as held this frame. */
    fun hold(a: Action) {
        heldBits = heldBits or (1L shl a.ordinal)
    }

    fun pressed(a: Action): Boolean = presses[a.ordinal] > 0

    fun presses(a: Action): Int = presses[a.ordinal]

    fun held(a: Action): Boolean = heldBits and (1L shl a.ordinal) != 0L

    /** True once if [a] was pressed (the press is used up). */
    fun consume(a: Action): Boolean {
        val i = a.ordinal
        if (presses[i] == 0) return false
        presses[i] = 0
        return true
    }

    /** Removes [a] completely (its presses and its held state), e.g. when the gate refuses it. */
    fun drop(a: Action) {
        presses[a.ordinal] = 0
        heldBits = heldBits and (1L shl a.ordinal).inv()
    }

    /** Magnitude of the aim (the Roblox "steering" test compares it with 0.5). */
    val aimMagnitude: Float get() = sqrt(aimX * aimX + aimY * aimY)

    /** Adds an aim contribution and keeps the sum inside the unit circle. */
    fun addAim(x: Float, y: Float) {
        aimX += x
        aimY += y
        val m = aimMagnitude
        if (m > 1f) {
            aimX /= m
            aimY /= m
        }
    }

    /**
     * Adds a stick's deflection to the aim (the touch stick and the pad's left stick): [x] right,
     * [y] down, its length 0..1 (a square pad's corners are clamped to the rim), shaped by the
     * SENSITIVITY setting ([stickResponse], the direction kept), then [pitchSign] (INVERT PITCH).
     */
    fun addStick(x: Float, y: Float, sensitivity: Float, pitchSign: Float) {
        val d = sqrt(x * x + y * y)
        if (d <= 0f) return
        val k = stickResponse(d, sensitivity) / d
        addAim(x * k, y * k * pitchSign)
    }

    /** Zeroes the flight axes (aim, key axis, lever, ramp, lateral thrust); actions stay. */
    fun clearFlightAxes() {
        aimX = 0f; aimY = 0f; keyAxis = 0f
        leverSet = false; throttleAxis = 0f
        lateralX = 0f; lateralY = 0f
    }

    companion object {
        const val TOUCH = 0
        const val GAMEPAD = 1
        const val KEYBOARD = 2

        /**
         * The SENSITIVITY setting (0.5..1.5, 1 the default; 02-flight.md 9.1) as a response shape on
         * a stick deflection's length [d] (clamped to 0..1): at 1 and above a gain, `min(1, d · s)`,
         * that reaches the rim sooner; below 1 a curve, `d^(1/s)`, that turns less for the same
         * partial deflection. A full deflection is 1 at every setting. Roblox's MOUSE SENSITIVITY is
         * a gain on the mouse deltas and its cursor still reaches the full radius
         * (`ShipInput.lua:193`); a plain gain on an absolute stick would not: at 0.5 a full
         * deflection was 0.5, 19 % of every turn rate through the 0.20 dead zone and curve 1.7, and
         * never past the 0.5 that takes the ship back from the autopilot or the docking computer
         * (`Autopilot.OverrideDeflection`).
         */
        fun stickResponse(d: Float, sensitivity: Float): Float {
            val m = if (d > 1f) 1f else d
            if (m <= 0f) return 0f
            return if (sensitivity >= 1f) minOf(1f, m * sensitivity) else m.pow(1f / maxOf(sensitivity, MIN_SENSITIVITY))
        }

        /** Below this the response curve is not defined (the setting's own range starts at 0.5). */
        private const val MIN_SENSITIVITY = 0.05f
    }
}
