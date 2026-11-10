package com.starwake.core.input

import kotlin.math.sqrt

/**
 * One frame of pilot input, built in one place from touch, gamepad (and later a keyboard) before
 * the session frame runs (01-core-loop.md 6, the `InputFrame` contract): the port of what Roblox's
 * ShipInput produces (`cursorPixels`, `keyAxis`, `look`) plus every key handler's edge. Reused every
 * frame: [begin] clears it, the devices write into it, [InputGate] removes what the current mode
 * does not allow, the session reads it. No allocation.
 *
 * Conventions follow Roblox: the aim is the pilot's cursor inside the unit circle with X right and
 * Y **down** (ShipInput.getNormalizedCursor), sensitivity and INVERT PITCH applied, no dead zone (the
 * flight model applies its own); the key axis is +1 for YawLeft (Q).
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
    }
}
