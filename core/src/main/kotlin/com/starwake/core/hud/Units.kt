package com.starwake.core.hud

import com.starwake.core.ui.TextBuf
import kotlin.math.floor

/**
 * Units for every display (Roblox ShipCore/ShipUnits.lua, 01-core-loop.md 2.11): metres, km, Mm
 * and m/s, km/s, Mm/s. Every threshold is the rounding boundary of the next format, so nothing ever
 * shows "1000 m", "10.00 km", "100.0 km", "1000 km", "10.00 Mm" or "100.0 Mm". From 100 Mm the
 * number is grouped ("1,240 Mm"). Upper-case texts keep "Mm" ("MM" would read as millimetres).
 *
 * Everything writes into a [TextBuf] and returns constant unit strings: no allocation per call.
 * Numbers are rounded in Double (`floor(x·100 + 0.5)`), as Lua's `%.2f` does for every value that
 * is not an exact binary tie; NaN and +∞ print "--", negative values print as 0 (−∞ too, as in Lua).
 */
object Units {
    /** Upper-case unit captions, as the HUD prints them next to a number. */
    const val M = "M"
    const val KM = "KM"
    const val MM = "Mm"
    const val M_S = "M/S"
    const val KM_S = "KM/S"
    const val MM_S = "Mm/s"

    /** The text for a value that cannot be shown (NaN, +∞). */
    const val NONE = "--"

    private fun invalid(v: Double) = v != v || v == Double.POSITIVE_INFINITY

    /**
     * Appends the number of a distance in metres to [num] and returns its upper-case unit
     * ([M], [KM] or [MM]); "--" and an empty unit for NaN and +∞.
     */
    fun distanceParts(num: TextBuf, metres: Double): String {
        if (invalid(metres)) {
            num.append(NONE)
            return ""
        }
        val m = if (metres > 0.0) metres else 0.0
        return when {
            m < 999.5 -> { num.append(floor(m + 0.5).toLong()); M }
            m < 9_995.0 -> { num.append(m / 1000.0, 2); KM }
            m < 99_950.0 -> { num.append(m / 1000.0, 1); KM }
            m < 999_500.0 -> { num.append(floor(m / 1000.0 + 0.5).toLong()); KM }
            m < 9_995_000.0 -> { num.append(m / 1e6, 2); MM }
            m < 99_950_000.0 -> { num.append(m / 1e6, 1); MM }
            else -> { grouped(num, m / 1e6); MM }
        }
    }

    /**
     * Distance with its unit: "316 m", "3.17 km", "6.84 Mm", "1,240 Mm" (Roblox `distance`), or
     * with [upper] "316 M", "3.17 KM", "6.84 Mm" (Roblox `upper(distance(...))`).
     */
    fun distance(buf: TextBuf, metres: Double, upper: Boolean = false): TextBuf {
        val unit = distanceParts(buf, metres)
        if (unit.isEmpty()) return buf
        buf.append(' ')
        return when {
            unit === MM || upper -> buf.append(unit)
            unit === KM -> buf.append("km")
            else -> buf.append("m")
        }
    }

    /**
     * Appends the number of a speed in m/s to [num] and returns its upper-case unit ([M_S], [KM_S]
     * or [MM_S]); "--" with [M_S] for NaN and +∞ (Roblox `speedParts`). Speeds are never grouped:
     * the 0.5 c cap is "150.0 Mm/s".
     */
    fun speedParts(num: TextBuf, mps: Double): String {
        if (invalid(mps)) {
            num.append(NONE)
            return M_S
        }
        val v = if (mps > 0.0) mps else 0.0
        return when {
            v < 999.5 -> { num.append(floor(v + 0.5).toLong()); M_S }
            v < 9_995.0 -> { num.append(v / 1000.0, 2); KM_S }
            v < 99_950.0 -> { num.append(v / 1000.0, 1); KM_S }
            v < 999_500.0 -> { num.append(floor(v / 1000.0 + 0.5).toLong()); KM_S }
            v < 9_995_000.0 -> { num.append(v / 1e6, 2); MM_S }
            else -> { num.append(v / 1e6, 1); MM_S }
        }
    }

    /**
     * Speed with its unit: "260 m/s", "53.0 km/s", "1.06 Mm/s" (Roblox `speed`: "Mm/s" stays, the
     * others lower case), or with [upper] "260 M/S", "53.0 KM/S", "1.06 Mm/s".
     */
    fun speed(buf: TextBuf, mps: Double, upper: Boolean = false): TextBuf {
        val unit = speedParts(buf, mps)
        buf.append(' ')
        return when {
            unit === MM_S || upper -> buf.append(unit)
            unit === KM_S -> buf.append("km/s")
            else -> buf.append("m/s")
        }
    }

    /** "35.0 m/s²" (Roblox `acceleration`). */
    fun acceleration(buf: TextBuf, mps2: Double): TextBuf = buf.append(mps2, 1).append(" m/s²")

    /** Whole number with thousands separators: 1240567 → "1,240,567"; negative values print 0. */
    fun grouped(buf: TextBuf, value: Double): TextBuf {
        val n = floor((if (value > 0.0) value else 0.0) + 0.5).toLong()
        // largest power of 1000 not above n, then three digits per group
        var div = 1L
        while (n / div >= 1000L) div *= 1000L
        buf.append(n / div)
        while (div > 1L) {
            div /= 1000L
            val group = (n / div) % 1000L
            buf.append(',')
            if (group < 100L) buf.append('0')
            if (group < 10L) buf.append('0')
            buf.append(group)
        }
        return buf
    }

    /**
     * Upper-cases [text] into [out] while keeping megametres readable (Roblox `upper`): "(digit) MM/S"
     * becomes "Mm/s" and "(digit) MM" at a word end becomes "Mm".
     */
    fun upper(text: CharSequence, out: TextBuf): TextBuf {
        val start = out.length
        for (i in 0 until text.length) out.append(text[i].uppercaseChar())
        // fix "d MM" / "d MM/S" in place: the length does not change
        var i = start + 2
        while (i + 1 < out.length) {
            if (out[i] == 'M' && out[i + 1] == 'M' && out[i - 1] == ' ' && out[i - 2].isDigit()) {
                val end = i + 2
                val slashS = end + 1 < out.length && out[end] == '/' && out[end + 1] == 'S'
                val wordEnd = end >= out.length || !out[end].isLetterOrDigit()
                if (slashS) {
                    out.set(i + 1, 'm')
                    out.set(end + 1, 's')
                } else if (wordEnd) {
                    out.set(i + 1, 'm')
                }
            }
            i++
        }
        return out
    }
}
