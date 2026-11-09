package com.starwake.core.ui

import com.starwake.core.Haptics
import com.starwake.core.gfx.UiRenderer
import com.starwake.core.math.clamp01
import com.starwake.core.math.withAlpha

object Theme {
    const val ACCENT = 0xFF4CD6FF.toInt()
    const val ACCENT_DIM = 0xFF1C6F86.toInt()
    const val TEXT = 0xFFE8F4FF.toInt()
    const val TEXT_DIM = 0xFF8FA6B8.toInt()
    const val PANEL = 0xD0081420.toInt()
    const val PANEL_LIGHT = 0xE0102233.toInt()
    const val DARK = 0xFF04111A.toInt()
    const val WARN = 0xFFFFB13B.toInt()
    const val DANGER = 0xFFFF5A4D.toInt()
    const val OK = 0xFF59F0A0.toInt()

    /** Target-lock amber (lock brackets, locked distance). */
    const val LOCK = 0xFFFFB347.toInt()
    const val BLACK = 0xFF000000.toInt()
}

/** Things widgets need from the game. */
interface UiHost {
    fun haptic(type: Int)
}

/** Mutable allocation-free text for HUD numbers. */
class TextBuf(capacity: Int = 64) : CharSequence {
    private val chars = CharArray(capacity)
    private var len = 0
    override val length: Int get() = len
    override fun get(index: Int): Char = chars[index]
    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = String(chars, startIndex, endIndex - startIndex)
    override fun toString(): String = String(chars, 0, len)

    fun clear(): TextBuf {
        len = 0; return this
    }

    /** Replaces the character at [index] (< [length]). */
    fun set(index: Int, c: Char) {
        if (index in 0 until len) chars[index] = c
    }

    /** True when the text equals [s] (no allocation, unlike toString()). */
    fun contentEquals(s: CharSequence): Boolean {
        if (s.length != len) return false
        for (i in 0 until len) if (chars[i] != s[i]) return false
        return true
    }

    fun append(s: CharSequence): TextBuf {
        for (i in 0 until s.length) if (len < chars.size) chars[len++] = s[i]
        return this
    }

    fun append(c: Char): TextBuf {
        if (len < chars.size) chars[len++] = c
        return this
    }

    fun append(v: Int): TextBuf = append(v.toLong())

    fun append(v: Long): TextBuf {
        var n = v
        if (n < 0) {
            append('-')
            // Long.MIN_VALUE has no positive counterpart: print its digits from the negative side
            if (n == Long.MIN_VALUE) return append("9223372036854775808")
            n = -n
        }
        if (n == 0L) return append('0')
        val start = len
        while (n > 0 && len < chars.size) {
            chars[len++] = '0' + (n % 10).toInt()
            n /= 10
        }
        // reverse the digits
        var i = start
        var j = len - 1
        while (i < j) {
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
            i++; j--
        }
        return this
    }

    /**
     * Fixed-point formatting of a double with [decimals] digits, rounded half up in double precision
     * (`floor(v·10^d + 0.5)`), like Lua's `%.Nf` for every value that is not an exact binary tie.
     * Computing in Float instead would print 6.845 km as "6.84" vs "6.85" at random (6.8449998).
     */
    fun append(v: Double, decimals: Int): TextBuf {
        var x = v
        if (x < 0.0) {
            append('-'); x = -x
        }
        var mul = 1L
        repeat(decimals) { mul *= 10 }
        val scaled = kotlin.math.floor(x * mul + 0.5).toLong()
        append(scaled / mul)
        if (decimals > 0) {
            append('.')
            val frac = scaled % mul
            var div = mul / 10
            while (div > 0) {
                append('0' + ((frac / div) % 10).toInt())
                div /= 10
            }
        }
        return this
    }

    /** Fixed-point formatting with [decimals] digits (rounded). */
    fun append(v: Float, decimals: Int): TextBuf {
        var x = v
        if (x < 0f) {
            append('-'); x = -x
        }
        var mul = 1
        repeat(decimals) { mul *= 10 }
        val scaled = (x * mul + 0.5f).toInt()
        append(scaled / mul)
        if (decimals > 0) {
            append('.')
            val frac = scaled % mul
            var div = mul / 10
            while (div > 0) {
                append('0' + (frac / div) % 10)
                div /= 10
            }
        }
        return this
    }

    /** Integer padded with leading zeros to [width] digits. */
    fun appendPadded(v: Int, width: Int): TextBuf {
        var digits = 1
        var t = v
        while (t >= 10) {
            t /= 10; digits++
        }
        repeat(width - digits) { append('0') }
        return append(v)
    }
}

class Rect(var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f) {
    fun set(x: Float, y: Float, w: Float, h: Float): Rect {
        this.x = x; this.y = y; this.w = w; this.h = h; return this
    }

    fun contains(px: Float, py: Float, slop: Float = 0f): Boolean =
        px >= x - slop && px <= x + w + slop && py >= y - slop && py <= y + h + slop

    val cx get() = x + w * 0.5f
    val cy get() = y + h * 0.5f
}

abstract class Widget {
    val r = Rect()
    var visible = true
    var pressed = false
        protected set
    lateinit var host: UiHost

    /** Pointer went down inside. */
    open fun press(x: Float, y: Float) {
        pressed = true
    }

    open fun drag(x: Float, y: Float) {}

    /** Pointer released; [inside] = released over the widget (a click). */
    open fun release(x: Float, y: Float, inside: Boolean) {
        pressed = false
    }

    abstract fun draw(ui: UiRenderer, u: Float, alpha: Float)
}

/** Routes pointers to widgets, capturing each pointer on the widget it went down on. */
class WidgetGroup(private val host: UiHost) {
    val items = ArrayList<Widget>()
    private val capture = arrayOfNulls<Widget>(MAX_POINTERS)
    var slop = 0f

    fun <T : Widget> add(w: T): T {
        w.host = host
        items.add(w)
        return w
    }

    fun down(id: Int, x: Float, y: Float): Boolean {
        if (id !in 0 until MAX_POINTERS) return false
        for (i in items.indices.reversed()) {
            val w = items[i]
            if (w.visible && w.r.contains(x, y, slop)) {
                capture[id] = w
                w.press(x, y)
                return true
            }
        }
        return false
    }

    fun move(id: Int, x: Float, y: Float): Boolean {
        if (id !in 0 until MAX_POINTERS) return false
        val w = capture[id] ?: return false
        w.drag(x, y)
        return true
    }

    fun up(id: Int, x: Float, y: Float, cancelled: Boolean): Boolean {
        if (id !in 0 until MAX_POINTERS) return false
        val w = capture[id] ?: return false
        capture[id] = null
        w.release(x, y, !cancelled && w.visible && w.r.contains(x, y, slop * 2f))
        return true
    }

    fun releaseAll() {
        for (i in capture.indices) {
            capture[i]?.release(0f, 0f, false)
            capture[i] = null
        }
    }

    fun draw(ui: UiRenderer, u: Float, alpha: Float) {
        for (i in items.indices) {
            val w = items[i]
            if (w.visible) w.draw(ui, u, alpha)
        }
    }

    companion object {
        const val MAX_POINTERS = 32
    }
}

class Button(var label: String, private val primary: Boolean = false, private val onClick: () -> Unit) : Widget() {
    private var glow = 0f

    override fun press(x: Float, y: Float) {
        super.press(x, y)
        host.haptic(Haptics.TAP)
    }

    override fun release(x: Float, y: Float, inside: Boolean) {
        super.release(x, y, inside)
        if (inside) onClick()
    }

    override fun draw(ui: UiRenderer, u: Float, alpha: Float) {
        glow += ((if (pressed) 1f else 0f) - glow) * 0.35f
        val chamfer = -9f * u
        if (primary) {
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, alpha * (0.88f + 0.12f * glow)), chamfer)
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, alpha * 0.35f), chamfer, 0f, 10f * u)
            ui.textCentered(label, r.cx, r.cy, 15f * u, withAlpha(Theme.DARK, alpha), tracking = 0.16f, weight = 0.03f)
        } else {
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.PANEL, alpha * (0.85f + 0.15f * glow)), chamfer)
            if (glow > 0.01f) ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, alpha * 0.25f * glow), chamfer)
            ui.rect(r.x, r.y, r.w, r.h, withAlpha(Theme.ACCENT, alpha * 0.55f), chamfer, 1.3f * u)
            ui.rect(r.x + 10f * u, r.cy - 7f * u, 3f * u, 14f * u, withAlpha(Theme.ACCENT, alpha * 0.9f))
            ui.textCentered(label, r.cx, r.cy, 15f * u, withAlpha(Theme.TEXT, alpha), tracking = 0.16f)
        }
    }
}

/** Option row: "LABEL ........ ◀ VALUE ▶". Tap the left half of the value box for previous, right half for next. */
class Selector(
    private val label: String,
    private val options: Array<String>,
    private val get: () -> Int,
    private val set: (Int) -> Unit,
) : Widget() {
    private var pressX = 0f

    override fun press(x: Float, y: Float) {
        super.press(x, y)
        pressX = x
    }

    override fun release(x: Float, y: Float, inside: Boolean) {
        super.release(x, y, inside)
        if (!inside) return
        val n = options.size
        val boxX = valueBoxX()
        val delta = if (label.isEmpty() || x > boxX) {
            if (x < boxX + (r.x + r.w - boxX) * 0.5f) -1 else 1
        } else 1
        set(((get() + delta) % n + n) % n)
        host.haptic(Haptics.TICK)
    }

    private fun valueBoxX(): Float = if (label.isEmpty()) r.x else r.x + r.w * 0.46f

    override fun draw(ui: UiRenderer, u: Float, alpha: Float) {
        if (label.isNotEmpty()) {
            ui.rect(r.x, r.y + r.h - 1f * u, r.w, 1f * u, withAlpha(Theme.ACCENT, alpha * 0.12f))
            ui.textCentered(label, r.x, r.cy, 12f * u, withAlpha(Theme.TEXT_DIM, alpha), UiRenderer.LEFT, tracking = 0.12f)
        }
        val bx = valueBoxX()
        val bw = r.x + r.w - bx
        val by = r.y + r.h * 0.14f
        val bh = r.h * 0.72f
        ui.rect(bx, by, bw, bh, withAlpha(if (pressed) Theme.PANEL_LIGHT else Theme.PANEL, alpha), -6f * u)
        ui.rect(bx, by, bw, bh, withAlpha(Theme.ACCENT, alpha * 0.35f), -6f * u, 1f * u)
        val cy = by + bh * 0.5f
        ui.textCentered("◀", bx + 14f * u, cy, 10f * u, withAlpha(Theme.ACCENT, alpha))
        ui.textCentered("▶", bx + bw - 14f * u, cy, 10f * u, withAlpha(Theme.ACCENT, alpha))
        val idx = get().coerceIn(0, options.size - 1)
        ui.textCentered(options[idx], bx + bw * 0.5f, cy, 12.5f * u, withAlpha(Theme.TEXT, alpha), tracking = 0.1f)
    }
}

/** Primitive float getter/setter (no boxing, unlike () -> Float). */
fun interface FloatSource {
    fun get(): Float
}

fun interface FloatSink {
    fun set(v: Float)
}

/**
 * Horizontal slider row. Only presses on the track move it (not the label or value text). Values
 * snap to [step] from [min] with a haptic tick per step; the value prints as [format]
 * ([FORMAT_TIMES] "1.25×", [FORMAT_DEGREES] "80°", [FORMAT_PERCENT] "35 %", as the Roblox menu).
 */
class Slider(
    private val label: String,
    private val min: Float,
    private val max: Float,
    private val get: FloatSource,
    private val set: FloatSink,
    private val step: Float = 0.05f,
    private val format: Int = FORMAT_TIMES,
) : Widget() {
    private val buf = TextBuf(16)
    private var lastStep = -1
    private var tracking = false

    private fun trackX0() = r.x + r.w * 0.46f + 12f * r.h / 40f
    private fun trackX1() = r.x + r.w - 52f * r.h / 40f

    override fun press(x: Float, y: Float) {
        super.press(x, y)
        val slop = 16f * r.h / 40f
        tracking = x >= trackX0() - slop && x <= trackX1() + slop
        if (tracking) drag(x, y)
    }

    override fun drag(x: Float, y: Float) {
        if (!tracking) return
        val t = clamp01((x - trackX0()) / (trackX1() - trackX0()))
        val v = min + (max - min) * t
        val n = ((v - min) / step + 0.5f).toInt()
        // snap to the steps with a haptic tick per step
        set.set(min + n * step)
        if (n != lastStep) {
            if (lastStep >= 0) host.haptic(Haptics.TICK)
            lastStep = n
        }
    }

    override fun release(x: Float, y: Float, inside: Boolean) {
        super.release(x, y, inside)
        lastStep = -1
        tracking = false
    }

    override fun draw(ui: UiRenderer, u: Float, alpha: Float) {
        ui.rect(r.x, r.y + r.h - 1f * u, r.w, 1f * u, withAlpha(Theme.ACCENT, alpha * 0.12f))
        ui.textCentered(label, r.x, r.cy, 12f * u, withAlpha(Theme.TEXT_DIM, alpha), UiRenderer.LEFT, tracking = 0.12f)
        val x0 = trackX0()
        val x1 = trackX1()
        val value = get.get()
        val t = clamp01((value - min) / (max - min))
        ui.rect(x0, r.cy - 2f * u, x1 - x0, 4f * u, withAlpha(Theme.PANEL_LIGHT, alpha), 2f * u)
        ui.rect(x0, r.cy - 2f * u, (x1 - x0) * t, 4f * u, withAlpha(Theme.ACCENT, alpha), 2f * u)
        ui.circle(x0 + (x1 - x0) * t, r.cy, (if (pressed) 10f else 8f) * u, withAlpha(Theme.ACCENT, alpha))
        buf.clear()
        when (format) {
            FORMAT_DEGREES -> buf.append((value + 0.5f).toInt()).append('°')
            FORMAT_PERCENT -> buf.append((value * 100f + 0.5f).toInt()).append(" %")
            else -> buf.append(value, 2).append('×')
        }
        ui.textCentered(buf, r.x + r.w, r.cy, 12.5f * u, withAlpha(Theme.TEXT, alpha), UiRenderer.RIGHT)
    }

    companion object {
        /** "1.25×" (Roblox multiplier format). */
        const val FORMAT_TIMES = 0

        /** "80°". */
        const val FORMAT_DEGREES = 1

        /** "35 %". */
        const val FORMAT_PERCENT = 2
    }
}
