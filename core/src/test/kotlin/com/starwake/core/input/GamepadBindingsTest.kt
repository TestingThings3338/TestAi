package com.starwake.core.input

import com.starwake.core.Haptics
import com.starwake.core.Settings
import com.starwake.core.TestAsserts.assertNear
import com.starwake.core.ui.UiHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reconciled gamepad map of 01-core-loop.md 8.2, with tap/hold detection. */
class GamepadBindingsTest {
    private val pad = GamepadState()
    private val settings = Settings()
    private val bindings = GamepadBindings(pad, settings)
    private val f = InputFrame()
    private val ticks = IntArray(4)
    private val host = object : UiHost {
        override fun haptic(type: Int) {
            ticks[type]++
        }
    }
    /** The lever as the thrust ramps it with the pad's W/S ([InputFrame.throttleAxis]): 25 %/s. */
    private var throttle = 0f

    private fun frame(dt: Float = 1f / 60f): InputFrame {
        f.begin()
        bindings.write(f, dt, host)
        throttle = (throttle + f.throttleAxis * dt / 4f).coerceIn(-0.25f, 1f)
        return f
    }

    /** Presses [b], runs frames for [seconds], releases, runs one frame; returns presses of [a] over all of it. */
    private fun pressFor(b: Int, seconds: Float, a: Action): Int {
        pad.buttonDown(b)
        var n = frame().presses(a)
        var t = 1f / 60f
        while (t < seconds) {
            n += frame().presses(a)
            t += 1f / 60f
        }
        pad.buttonUp(b)
        n += frame().presses(a)
        return n
    }

    init {
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 0f) // connected
    }

    @Test
    fun singleMeaningButtonsActOnThePress() {
        pad.buttonDown(GamepadState.A)
        assertEquals(1, frame().presses(Action.BOOST), "A boosts at once")
        assertEquals(0, frame().presses(Action.BOOST), "once per press")
        pad.buttonUp(GamepadState.A)
        pad.buttonDown(GamepadState.X)
        assertEquals(1, frame().presses(Action.TOGGLE_FLIGHT_ASSIST))
        pad.buttonUp(GamepadState.X)
        pad.buttonDown(GamepadState.DPAD_RIGHT)
        assertEquals(1, frame().presses(Action.AUTOPILOT))
        pad.buttonUp(GamepadState.DPAD_RIGHT)
        // a press and release between two frames is not lost
        pad.buttonDown(GamepadState.A)
        pad.buttonUp(GamepadState.A)
        assertEquals(1, frame().presses(Action.BOOST))
    }

    @Test
    fun tapAndHoldAreTold() {
        assertEquals(1, pressFor(GamepadState.Y, 0.2f, Action.EXTERNAL_VIEW), "Y tap: view")
        assertEquals(0, pressFor(GamepadState.Y, 0.2f, Action.LEAVE_SEAT))
        assertEquals(1, pressFor(GamepadState.Y, 0.7f, Action.LEAVE_SEAT), "Y held: leave the seat")
        assertEquals(0, pressFor(GamepadState.Y, 0.7f, Action.EXTERNAL_VIEW), "a hold is not also a tap")
        assertEquals(1, pressFor(GamepadState.DPAD_UP, 0.1f, Action.CYCLE_TARGET))
        assertEquals(1, pressFor(GamepadState.DPAD_UP, 0.6f, Action.CLEAR_TARGET))
        assertEquals(1, pressFor(GamepadState.SELECT, 0.1f, Action.SYSTEM_MAP))
        assertEquals(1, pressFor(GamepadState.SELECT, 0.6f, Action.STATION_PANEL))
        assertEquals(1, pressFor(GamepadState.DPAD_LEFT, 0.1f, Action.CANCEL_JUMP))
        assertEquals(1, pressFor(GamepadState.DPAD_LEFT, 0.6f, Action.SURVEY))
        // the hold fires at 0.5 s, while still held, with a tick
        val before = ticks[Haptics.TICK]
        pad.buttonDown(GamepadState.DPAD_UP)
        var at = -1
        for (i in 1..60) if (frame().pressed(Action.CLEAR_TARGET)) at = i
        pad.buttonUp(GamepadState.DPAD_UP)
        frame()
        assertEquals(30, at, "fires on the frame 0.5 s is reached")
        assertEquals(before + 1, ticks[Haptics.TICK])
    }

    @Test
    fun dpadDownSendsTheContextAction() {
        f.contextAction = Action.DOCK
        pad.buttonDown(GamepadState.DPAD_DOWN)
        f.begin()
        bindings.write(f, 1f / 60f, host)
        assertEquals(1, f.presses(Action.DOCK))
        pad.buttonUp(GamepadState.DPAD_DOWN)
        // nothing to do: nothing sent
        f.contextAction = null
        pad.buttonDown(GamepadState.DPAD_DOWN)
        f.begin()
        bindings.write(f, 1f / 60f, host)
        for (i in 0 until Action.COUNT) assertEquals(0, f.presses(Action.of(i)))
        f.contextAction = null
    }

    @Test
    fun sticksAimSteerAndLook() {
        // left stick: the cursor (Y down), sensitivity and invert pitch applied
        pad.setSticks(0.5f, -0.4f, 0f, 0f, 0f, 0f)
        frame()
        assertNear(0.5f, f.aimX, 1e-6f)
        assertNear(-0.4f, f.aimY, 1e-6f, "stick up = cursor up")
        settings.pitchUpClimb = false
        frame()
        assertNear(0.4f, f.aimY, 1e-6f, "inverted")
        settings.pitchUpClimb = true
        // right stick: key axis (+1 = left) and vertical thrust
        pad.setSticks(0f, 0f, 0.6f, -1f, 0f, 0f)
        frame()
        assertNear(-0.6f, f.keyAxis, 1e-6f)
        assertNear(1f, f.lateralY, 1e-6f, "right stick up = thrust up")
        assertEquals(InputFrame.GAMEPAD, f.device)
        // with L3 held the right stick looks around (180°/s) and stops yawing
        pad.buttonDown(GamepadState.L3)
        pad.setSticks(0f, 0f, 1f, -0.5f, 0f, 0f)
        frame(0.1f)
        assertTrue(f.lookHeld && f.held(Action.LOOK))
        assertNear(18f, f.lookX, 1e-4f)
        assertNear(9f, f.lookY, 1e-4f, "stick up looks up")
        assertEquals(0f, f.keyAxis)
        assertEquals(0f, f.lateralY)
        pad.buttonUp(GamepadState.L3)
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 0f)
    }

    @Test
    fun aFullStickIsTheRimAndTheFullKeyAxisAtTheLowestSensitivity() {
        // SENSITIVITY 0.5 shapes the sticks through the touch stick's response (02 9.1): a full left
        // stick is still the cursor's rim (the full turn rate, the 0.5 override), a full twist of the
        // right stick still the full yaw
        settings.sensitivity = 0.5f
        pad.setSticks(1f, 0f, 0f, 0f, 0f, 0f)
        frame()
        assertNear(1f, f.aimX, 1e-6f, "full right")
        pad.setSticks(-1f, -1f, 0f, 0f, 0f, 0f) // a square pad's corner: clamped to the rim, direction kept
        frame()
        assertNear(1f, f.aimMagnitude, 1e-5f)
        assertNear(f.aimX, f.aimY, 1e-6f, "diagonal kept")
        assertTrue(f.aimX < 0f && f.aimY < 0f)
        pad.setSticks(0.5f, 0f, 0f, 0f, 0f, 0f)
        frame()
        assertNear(0.25f, f.aimX, 1e-6f, "half a deflection: 0.5^(1/0.5)")
        pad.setSticks(0f, 0f, 1f, 0f, 0f, 0f)
        frame()
        assertNear(-1f, f.keyAxis, 1e-6f, "full right twist = full yaw right")
        pad.setSticks(0f, 0f, -0.5f, 0f, 0f, 0f)
        frame()
        assertNear(0.25f, f.keyAxis, 1e-6f, "half a twist left")
        // and at 1.5 the gain: 0.4 -> 0.6, saturating at the rim
        settings.sensitivity = 1.5f
        pad.setSticks(0.4f, 0f, 0.8f, 0f, 0f, 0f)
        frame()
        assertNear(0.6f, f.aimX, 1e-6f)
        assertNear(-1f, f.keyAxis, 1e-6f)
        settings.sensitivity = 1f
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 0f)
    }

    @Test
    fun shouldersStrafeAndR3StopsFully() {
        pad.buttonDown(GamepadState.LB)
        frame()
        assertEquals(-1f, f.lateralX)
        pad.buttonDown(GamepadState.RB)
        frame()
        assertEquals(0f, f.lateralX, "both cancel")
        pad.buttonUp(GamepadState.LB)
        frame()
        assertEquals(1f, f.lateralX)
        pad.buttonUp(GamepadState.RB)
        pad.buttonDown(GamepadState.R3)
        assertTrue(frame().held(Action.FULL_STOP))
        pad.buttonUp(GamepadState.R3)
        assertFalse(frame().held(Action.FULL_STOP))
    }

    @Test
    fun triggersAreTheRobloxThrottleRamp() {
        // RT / LT are W / S (02 9.2): the axis is the trigger difference, the thrust ramps the
        // lever by it at 25 %/s (ThrottleRampTime 4 s), from -25 % to 100 %
        throttle = 0f
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 1f) // RT
        assertEquals(1f, frame().throttleAxis)
        assertTrue(f.held(Action.THROTTLE_UP))
        repeat(59) { frame() }
        assertNear(0.25f, throttle, 1e-3f, "25 % per second at full trigger")
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 0.5f) // half RT
        assertEquals(0.5f, frame().throttleAxis)
        pad.setSticks(0f, 0f, 0f, 0f, 1f, 0f) // LT
        assertEquals(-1f, frame().throttleAxis)
        assertTrue(f.held(Action.THROTTLE_DOWN))
        repeat(240) { frame() }
        assertEquals(-0.25f, throttle, "full reverse is -25 %")
        pad.setSticks(0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(0f, frame().throttleAxis)
        assertFalse(f.leverSet, "the pad never sets the lever directly")
    }

    @Test
    fun nothingWithoutAPad() {
        pad.connected = false
        pad.buttonDown(GamepadState.A)
        pad.connected = false
        f.begin()
        bindings.write(f, 1f / 60f, host)
        assertFalse(f.pressed(Action.BOOST))
        assertFalse(f.leverSet)
        assertEquals(0f, f.throttleAxis)
    }

    @Test
    fun bindingsCoverTheReconciledMap() {
        assertEquals(Action.BOOST, bindings.tapOf(GamepadState.A))
        assertEquals(Action.TOGGLE_FLIGHT_ASSIST, bindings.tapOf(GamepadState.X))
        assertEquals(Action.EXTERNAL_VIEW, bindings.tapOf(GamepadState.Y))
        assertEquals(Action.LEAVE_SEAT, bindings.holdOf(GamepadState.Y))
        assertEquals(Action.CYCLE_TARGET, bindings.tapOf(GamepadState.DPAD_UP))
        assertEquals(Action.CLEAR_TARGET, bindings.holdOf(GamepadState.DPAD_UP))
        assertEquals(Action.CANCEL_JUMP, bindings.tapOf(GamepadState.DPAD_LEFT))
        assertEquals(Action.SURVEY, bindings.holdOf(GamepadState.DPAD_LEFT))
        assertEquals(Action.AUTOPILOT, bindings.tapOf(GamepadState.DPAD_RIGHT))
        assertEquals(Action.SYSTEM_MAP, bindings.tapOf(GamepadState.SELECT))
        assertEquals(Action.STATION_PANEL, bindings.holdOf(GamepadState.SELECT))
        // Start and B are system navigation (the game's), not flight bindings
        assertEquals(null, bindings.tapOf(GamepadState.START))
        assertEquals(null, bindings.tapOf(GamepadState.B))
    }
}
