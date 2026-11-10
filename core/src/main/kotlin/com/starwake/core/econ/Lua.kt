package com.starwake.core.econ

import kotlin.math.abs
import kotlin.math.floor

/**
 * The few Luau number and string semantics the Roblox economy rules depend on, so the Kotlin port
 * gives the same numbers bit for bit (08-stations-economy.md 10.2 "Determinism"):
 *
 * - `math.round` rounds half AWAY from zero (Kotlin's `round` rounds half to even);
 * - the string hashes (`hash01`, `seedOf`, the lore and model hashes) run over the UTF-8 bytes of
 *   the text, and integers inside such texts print without ".0" (`"SOL-MERIDIAN|Water|1500000"`).
 *
 * The hashes stream the bytes, so building the hashed text is never needed (no allocation).
 */
object Lua {
    /** Luau `math.round(x)`: half away from zero, exactly as C `round()`. */
    fun round(x: Double): Double {
        val a = abs(x)
        var r = floor(a)
        // a - r is exact for every double (Sterbenz), so the tie test is exact
        if (a - r >= 0.5) r += 1.0
        return if (x < 0.0) -r else r
    }

    /** [round] as a Long (values far inside the Long range). */
    fun roundToLong(x: Double): Long = round(x).toLong()

    /** One step of the byte hashes: `h = (h * mult + byte) % mod`. */
    fun hashByte(h: Long, mult: Long, mod: Long, byte: Int): Long = (h * mult + byte) % mod

    /** [hashByte] over the UTF-8 bytes of [text] (an unpaired surrogate counts as "?", as Java encodes it). */
    fun hash(h0: Long, mult: Long, mod: Long, text: CharSequence): Long {
        var h = h0
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i].code
            when {
                c < 0x80 -> h = hashByte(h, mult, mod, c)
                c < 0x800 -> {
                    h = hashByte(h, mult, mod, 0xC0 or (c shr 6))
                    h = hashByte(h, mult, mod, 0x80 or (c and 0x3F))
                }
                Character.isHighSurrogate(c.toChar()) && i + 1 < n && Character.isLowSurrogate(text[i + 1]) -> {
                    val cp = Character.toCodePoint(c.toChar(), text[i + 1])
                    h = hashByte(h, mult, mod, 0xF0 or (cp shr 18))
                    h = hashByte(h, mult, mod, 0x80 or ((cp shr 12) and 0x3F))
                    h = hashByte(h, mult, mod, 0x80 or ((cp shr 6) and 0x3F))
                    h = hashByte(h, mult, mod, 0x80 or (cp and 0x3F))
                    i++
                }
                Character.isSurrogate(c.toChar()) -> h = hashByte(h, mult, mod, '?'.code)
                else -> {
                    h = hashByte(h, mult, mod, 0xE0 or (c shr 12))
                    h = hashByte(h, mult, mod, 0x80 or ((c shr 6) and 0x3F))
                    h = hashByte(h, mult, mod, 0x80 or (c and 0x3F))
                }
            }
            i++
        }
        return h
    }

    /** [hashByte] over the decimal digits of [value] as Luau prints a whole number ("-12", "1500000"). */
    fun hashInt(h0: Long, mult: Long, mod: Long, value: Long): Long {
        var h = h0
        if (value < 0) h = hashByte(h, mult, mod, '-'.code)
        val v = if (value < 0) -value else value
        var div = 1L
        while (v / div >= 10L) div *= 10L
        while (div > 0L) {
            h = hashByte(h, mult, mod, '0'.code + ((v / div) % 10L).toInt())
            div /= 10L
        }
        return h
    }
}
