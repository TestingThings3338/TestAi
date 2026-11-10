package com.starwake.android

import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.starwake.core.input.GamepadState
import kotlin.math.sqrt

/**
 * Translates Android controller events into [GamepadState]. UI thread only; allocation-free per
 * event (per-device axis data is cached and refreshed only when a different controller is used).
 *
 * - Sticks: AXIS_X/Y (left), AXIS_Z/RZ (right; falls back to RX/RY on devices without Z/RZ), with a
 *   radial dead-zone that is rescaled so the magnitude runs 0..1 from its edge. The right stick
 *   gets max(device "flat", [STICK_DEAD_ZONE]): core applies no dead zone of its own to it. The
 *   left stick is the flight's aim (the Roblox cursor): it gets only the device's own noise band
 *   ("flat"), since the flight model applies Roblox's radial dead zone (0.20, curve 1.7) to the aim
 *   itself (02-flight.md 9.2: the platform dead zone is off for this stick).
 * - Triggers: max(LTRIGGER, BRAKE) / max(RTRIGGER, GAS), merged with digital L2/R2 keys.
 * - HAT_X/HAT_Y and DPAD keys -> DPAD buttons.
 * - Face/shoulder/start/select keys and the stick clicks (L3, R3) -> buttons. KEYCODE_BACK /
 *   KEYCODE_MENU coming *from a controller* map to B / START, so a controller's B never acts as
 *   the system back.
 * All other controller buttons are consumed so Android does not synthesise fallback keys
 * (BUTTON_B -> BACK, BUTTON_A -> DPAD_CENTER, ...) for them.
 */
internal class GamepadInput(private val pad: GamepadState) : InputManager.InputDeviceListener {
    // Latest analog values (kept so digital trigger keys can be merged in).
    private var lx = 0f
    private var ly = 0f
    private var rx = 0f
    private var ry = 0f
    private var analogL = 0f
    private var analogR = 0f
    private var digitalL2 = false
    private var digitalR2 = false
    private var hatMask = 0

    // Per-device cache.
    private var cachedDeviceId = NO_DEVICE
    private var rightXAxis = MotionEvent.AXIS_Z
    private var rightYAxis = MotionEvent.AXIS_RZ
    private var leftDeadZone = 0f
    private var rightDeadZone = STICK_DEAD_ZONE
    private var leftTriggerDeadZone = TRIGGER_DEAD_ZONE
    private var rightTriggerDeadZone = TRIGGER_DEAD_ZONE

    /** Joystick motion (sticks, triggers, hat). Returns true when consumed. */
    fun onMotionEvent(e: MotionEvent): Boolean {
        if (!isController(e.source) || e.actionMasked != MotionEvent.ACTION_MOVE) return false
        if (e.deviceId != cachedDeviceId) cacheDevice(e)

        val x = e.getAxisValue(MotionEvent.AXIS_X)
        val y = e.getAxisValue(MotionEvent.AXIS_Y)
        val ls = radialScale(x, y, leftDeadZone)
        lx = x * ls
        ly = y * ls

        val x2 = e.getAxisValue(rightXAxis)
        val y2 = e.getAxisValue(rightYAxis)
        val rs = radialScale(x2, y2, rightDeadZone)
        rx = x2 * rs
        ry = y2 * rs

        analogL = trigger(
            maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE)),
            leftTriggerDeadZone,
        )
        analogR = trigger(
            maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS)),
            rightTriggerDeadZone,
        )
        publishAnalog()

        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        var mask = 0
        if (hx < -0.5f) mask = mask or bit(GamepadState.DPAD_LEFT)
        if (hx > 0.5f) mask = mask or bit(GamepadState.DPAD_RIGHT)
        if (hy < -0.5f) mask = mask or bit(GamepadState.DPAD_UP)
        if (hy > 0.5f) mask = mask or bit(GamepadState.DPAD_DOWN)
        if (mask != hatMask) {
            val changed = mask xor hatMask
            hatMask = mask
            for (b in DPAD_BUTTONS) {
                if (changed and bit(b) == 0) continue
                if (mask and bit(b) != 0) pad.buttonDown(b) else pad.buttonUp(b)
            }
        }
        return true
    }

    /** Key events (ACTION_DOWN / ACTION_UP). Returns true when consumed. */
    fun onKeyEvent(e: KeyEvent): Boolean {
        val action = e.action
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return false
        val down = action == KeyEvent.ACTION_DOWN
        val fromController = isController(e.source)
        when (val code = e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (code == KeyEvent.KEYCODE_BUTTON_L2) digitalL2 = down else digitalR2 = down
                publishAnalog()
                return true
            }
            else -> {
                val button = buttonFor(code, fromController)
                if (button >= 0) {
                    if (!down) pad.buttonUp(button) else if (e.repeatCount == 0) pad.buttonDown(button)
                    return true
                }
                // Swallow the remaining controller buttons (C/Z, 1..16) to suppress
                // fallback keys. BUTTON_MODE (guide/home) is left to the system.
                return KeyEvent.isGamepadButton(code) && code != KeyEvent.KEYCODE_BUTTON_MODE
            }
        }
    }

    /** Releases everything (app paused or controller removed) so nothing stays stuck "held". */
    fun reset() {
        lx = 0f; ly = 0f; rx = 0f; ry = 0f
        analogL = 0f; analogR = 0f
        digitalL2 = false; digitalR2 = false
        hatMask = 0
        pad.leftX = 0f; pad.leftY = 0f; pad.rightX = 0f; pad.rightY = 0f
        pad.leftTrigger = 0f; pad.rightTrigger = 0f
        for (b in 0 until GamepadState.BUTTONS) pad.buttonUp(b)
    }

    // ---------------------------------------------------------------- InputManager.InputDeviceListener

    override fun onInputDeviceAdded(deviceId: Int) {}

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (deviceId == cachedDeviceId) cachedDeviceId = NO_DEVICE
        reset()
        pad.connected = anyControllerConnected()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        if (deviceId == cachedDeviceId) cachedDeviceId = NO_DEVICE
    }

    // ---------------------------------------------------------------- internals

    /** Sticks are published dead-zoned and rescaled (the left one only past its noise band, see the class doc). */
    private fun publishAnalog() {
        val lt = if (digitalL2) 1f else analogL
        val rt = if (digitalR2) 1f else analogR
        pad.setSticks(lx, ly, rx, ry, lt, rt)
    }

    private fun cacheDevice(e: MotionEvent) {
        cachedDeviceId = e.deviceId
        val dev = e.device
        val hasZ = range(dev, MotionEvent.AXIS_Z) != null || range(dev, MotionEvent.AXIS_RZ) != null
        val hasRxRy = range(dev, MotionEvent.AXIS_RX) != null && range(dev, MotionEvent.AXIS_RY) != null
        if (!hasZ && hasRxRy) {
            rightXAxis = MotionEvent.AXIS_RX
            rightYAxis = MotionEvent.AXIS_RY
        } else {
            rightXAxis = MotionEvent.AXIS_Z
            rightYAxis = MotionEvent.AXIS_RZ
        }
        // the aim stick: only the device's noise band (the flight model has Roblox's dead zone)
        leftDeadZone = maxOf(flat(dev, MotionEvent.AXIS_X), flat(dev, MotionEvent.AXIS_Y))
        rightDeadZone = maxOf(STICK_DEAD_ZONE, flat(dev, rightXAxis), flat(dev, rightYAxis))
        leftTriggerDeadZone = maxOf(
            TRIGGER_DEAD_ZONE, flat(dev, MotionEvent.AXIS_LTRIGGER), flat(dev, MotionEvent.AXIS_BRAKE),
        )
        rightTriggerDeadZone = maxOf(
            TRIGGER_DEAD_ZONE, flat(dev, MotionEvent.AXIS_RTRIGGER), flat(dev, MotionEvent.AXIS_GAS),
        )
    }

    private fun range(dev: InputDevice?, axis: Int): InputDevice.MotionRange? =
        dev?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) ?: dev?.getMotionRange(axis)

    /**
     * Device-reported "flat" (centre noise band). Android normalises joystick axes (-1..1 sticks,
     * 0..1 triggers) and reports flat in the same units, so it is directly a dead-zone radius.
     */
    private fun flat(dev: InputDevice?, axis: Int): Float =
        range(dev, axis)?.flat?.coerceIn(0f, 0.5f) ?: 0f

    private fun anyControllerConnected(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(id) ?: continue
            if (!dev.isVirtual && isController(dev.sources)) return true
        }
        return false
    }

    private companion object {
        const val NO_DEVICE = Int.MIN_VALUE
        /** Radial stick dead zone (fraction of full deflection); core relies on it, see the class doc. */
        const val STICK_DEAD_ZONE = 0.12f
        const val TRIGGER_DEAD_ZONE = 0.03f
        val DPAD_BUTTONS = intArrayOf(
            GamepadState.DPAD_UP, GamepadState.DPAD_DOWN, GamepadState.DPAD_LEFT, GamepadState.DPAD_RIGHT,
        )

        fun bit(button: Int) = 1 shl button

        fun isController(source: Int): Boolean =
            source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK

        /** Maps a key code to a [GamepadState] button, or -1. */
        fun buttonFor(code: Int, fromController: Boolean): Int = when (code) {
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> GamepadState.A
            KeyEvent.KEYCODE_BUTTON_B -> GamepadState.B
            KeyEvent.KEYCODE_BUTTON_X -> GamepadState.X
            KeyEvent.KEYCODE_BUTTON_Y -> GamepadState.Y
            KeyEvent.KEYCODE_BUTTON_L1 -> GamepadState.LB
            KeyEvent.KEYCODE_BUTTON_R1 -> GamepadState.RB
            KeyEvent.KEYCODE_BUTTON_START -> GamepadState.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> GamepadState.SELECT
            // stick clicks: L3 = look modifier, R3 = full stop (01-core-loop.md 8.2)
            KeyEvent.KEYCODE_BUTTON_THUMBL -> GamepadState.L3
            KeyEvent.KEYCODE_BUTTON_THUMBR -> GamepadState.R3
            KeyEvent.KEYCODE_DPAD_UP -> GamepadState.DPAD_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> GamepadState.DPAD_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> GamepadState.DPAD_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> GamepadState.DPAD_RIGHT
            // Some controllers' key layouts emit BACK/MENU directly for B (or Select) / Start.
            KeyEvent.KEYCODE_BACK -> if (fromController) GamepadState.B else -1
            KeyEvent.KEYCODE_MENU -> if (fromController) GamepadState.START else -1
            else -> -1
        }

        /** Multiplier applying a radial dead-zone [dz] to the stick vector (x, y), output in 0..1. */
        fun radialScale(x: Float, y: Float, dz: Float): Float {
            val m = sqrt(x * x + y * y)
            if (m <= dz) return 0f
            val out = ((m - dz) / (1f - dz)).coerceAtMost(1f)
            return out / m
        }

        fun trigger(v: Float, dz: Float): Float =
            if (v <= dz) 0f else ((v - dz) / (1f - dz)).coerceAtMost(1f)
    }
}
