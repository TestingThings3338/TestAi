package com.starwake.core.input

/**
 * Thread-safe queue of pointer events. The platform pushes from its UI thread (coordinates already
 * in render-surface pixels, origin top-left); the game drains it on the GL thread each frame.
 * Fixed-size arrays: no allocation per event.
 */
class InputQueue(private val capacity: Int = 512) {
    private val type = IntArray(capacity)
    private val id = IntArray(capacity)
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = Any()

    // drain buffers (GL thread only)
    private val dType = IntArray(capacity)
    private val dId = IntArray(capacity)
    private val dX = FloatArray(capacity)
    private val dY = FloatArray(capacity)

    fun push(eventType: Int, pointerId: Int, x: Float, y: Float) {
        synchronized(lock) {
            if (count == capacity) {
                // drop the oldest event rather than block the UI thread
                head = (head + 1) % capacity
                count--
            }
            val i = (head + count) % capacity
            type[i] = eventType
            id[i] = pointerId
            xs[i] = x
            ys[i] = y
            count++
        }
    }

    fun drain(handler: PointerHandler) {
        var n: Int
        synchronized(lock) {
            n = count
            for (k in 0 until n) {
                val i = (head + k) % capacity
                dType[k] = type[i]; dId[k] = id[i]; dX[k] = xs[i]; dY[k] = ys[i]
            }
            head = (head + n) % capacity
            count = 0
        }
        for (k in 0 until n) {
            when (dType[k]) {
                DOWN -> handler.onPointerDown(dId[k], dX[k], dY[k])
                MOVE -> handler.onPointerMove(dId[k], dX[k], dY[k])
                UP -> handler.onPointerUp(dId[k], dX[k], dY[k], false)
                CANCEL -> handler.onPointerUp(dId[k], dX[k], dY[k], true)
            }
        }
    }

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
    }
}

interface PointerHandler {
    fun onPointerDown(id: Int, x: Float, y: Float)
    fun onPointerMove(id: Int, x: Float, y: Float)
    fun onPointerUp(id: Int, x: Float, y: Float, cancelled: Boolean)
}

/**
 * Latest state of a connected game controller, written by the platform's UI thread and read by
 * the GL thread. Button presses are counted so a quick tap between two frames is never lost.
 */
class GamepadState {
    @Volatile var connected = false
    @Volatile var leftX = 0f
    @Volatile var leftY = 0f
    @Volatile var rightX = 0f
    @Volatile var rightY = 0f
    @Volatile var leftTrigger = 0f
    @Volatile var rightTrigger = 0f
    @Volatile var held = 0
    private val pressCount = IntArray(BUTTONS)
    private val seen = IntArray(BUTTONS)

    fun setSticks(lx: Float, ly: Float, rx: Float, ry: Float, lt: Float, rt: Float) {
        leftX = lx; leftY = ly; rightX = rx; rightY = ry; leftTrigger = lt; rightTrigger = rt
        connected = true
    }

    fun buttonDown(button: Int) {
        if (button !in 0 until BUTTONS) return
        synchronized(this) {
            if (held and (1 shl button) == 0) pressCount[button]++
            held = held or (1 shl button)
        }
        connected = true
    }

    fun buttonUp(button: Int) {
        if (button !in 0 until BUTTONS) return
        synchronized(this) { held = held and (1 shl button).inv() }
    }

    fun isHeld(button: Int): Boolean = held and (1 shl button) != 0

    /** True once per physical press (GL thread). */
    fun consumePress(button: Int): Boolean {
        synchronized(this) {
            if (pressCount[button] != seen[button]) {
                seen[button] = pressCount[button]
                return true
            }
        }
        return false
    }

    companion object {
        const val A = 0
        const val B = 1
        const val X = 2
        const val Y = 3
        const val LB = 4
        const val RB = 5
        const val START = 6
        const val SELECT = 7
        const val DPAD_UP = 8
        const val DPAD_DOWN = 9
        const val DPAD_LEFT = 10
        const val DPAD_RIGHT = 11
        const val BUTTONS = 12
    }
}
