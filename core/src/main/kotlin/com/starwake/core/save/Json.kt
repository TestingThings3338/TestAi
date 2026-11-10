package com.starwake.core.save

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import kotlin.math.floor

/** A save file that is not valid JSON (or not the shape expected). Always caught by the save layer. */
class JsonException(message: String) : RuntimeException(message)

/**
 * Hand-written JSON for the save files (no third-party dependency). [parse] builds a plain tree:
 * objects are `LinkedHashMap<String, Any?>` (key order kept), arrays `ArrayList<Any?>`, numbers
 * `Long` (whole numbers that fit) or `Double`, plus `String`, `Boolean` and `null`. Any error,
 * including bad UTF-8, nesting deeper than [MAX_DEPTH] and trailing garbage, throws
 * [JsonException]; nothing else escapes. The typed readers ([obj], [num], ..) return null for a
 * wrong type, so decoders fall back to defaults instead of failing.
 */
object Json {
    /** Deepest nesting accepted (the save files use about 6). */
    const val MAX_DEPTH = 64

    /** Parses UTF-8 [bytes] (a leading byte-order mark is skipped). */
    fun parse(bytes: ByteArray): Any? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (e: CharacterCodingException) {
            throw JsonException("not UTF-8: ${e.message}")
        }
        return parse(text)
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        if (p.pos < text.length && text[p.pos] == '﻿') p.pos++
        val v = p.value(0)
        p.skipSpace()
        if (p.pos != text.length) throw p.error("trailing characters")
        return v
    }

    // ---------------------------------------------------------------- typed readers
    @Suppress("UNCHECKED_CAST")
    fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>

    fun arr(v: Any?): List<Any?>? = v as? List<Any?>

    fun str(v: Any?): String? = v as? String

    fun bool(v: Any?): Boolean? = v as? Boolean

    /** A finite number as a Double, else null. */
    fun num(v: Any?): Double? = when (v) {
        is Long -> v.toDouble()
        is Double -> if (v.isFinite()) v else null
        is Int -> v.toDouble()
        else -> null
    }

    /** A finite number as a Long, rounded down like Lua's math.floor and clamped to the Long range; else null. */
    fun long(v: Any?): Long? = when (v) {
        is Long -> v
        is Int -> v.toLong()
        is Double -> if (!v.isFinite()) null else floor(v).coerceIn(-9.0e18, 9.0e18).toLong()
        else -> null
    }

    /** [long] as an Int (clamped), else null. */
    fun int(v: Any?): Int? = long(v)?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt()

    // ---------------------------------------------------------------- parser
    private class Parser(val s: String) {
        var pos = 0

        fun error(what: String) = JsonException("$what at offset $pos")

        fun skipSpace() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw error("nested too deep")
            skipSpace()
            if (pos >= s.length) throw error("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw error("unexpected '$c'")
            }
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw error("bad literal")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): Map<String, Any?> {
            pos++ // {
            val map = LinkedHashMap<String, Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipSpace()
                if (pos >= s.length || s[pos] != '"') throw error("expected a key")
                val key = string()
                skipSpace()
                if (pos >= s.length || s[pos] != ':') throw error("expected ':'")
                pos++
                map[key] = value(depth + 1)
                skipSpace()
                if (pos >= s.length) throw error("unexpected end in object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw error("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): List<Any?> {
            pos++ // [
            val list = ArrayList<Any?>()
            skipSpace()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(value(depth + 1))
                skipSpace()
                if (pos >= s.length) throw error("unexpected end in array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw error("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                if (pos >= s.length) throw error("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (pos >= s.length) throw error("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw error("short \\u escape")
                                var code = 0
                                for (k in 0 until 4) {
                                    val d = Character.digit(s[pos + k], 16)
                                    if (d < 0) throw error("bad \\u escape")
                                    code = code * 16 + d
                                }
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> throw error("bad escape '\\$e'")
                        }
                    }
                    c < ' ' -> throw error("control character in string")
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) throw error("bad number")
            if (s[pos] == '0') {
                pos++
            } else if (s[pos] in '1'..'9') {
                while (pos < s.length && s[pos] in '0'..'9') pos++
            } else {
                throw error("bad number")
            }
            var integral = true
            if (pos < s.length && s[pos] == '.') {
                integral = false
                pos++
                if (pos >= s.length || s[pos] !in '0'..'9') throw error("bad fraction")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                integral = false
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (pos >= s.length || s[pos] !in '0'..'9') throw error("bad exponent")
                while (pos < s.length && s[pos] in '0'..'9') pos++
            }
            val text = s.substring(start, pos)
            if (integral) text.toLongOrNull()?.let { return it }
            return text.toDoubleOrNull() ?: throw error("bad number")
        }
    }
}

/**
 * Hand-written JSON writer for the save files: objects and arrays with automatic commas, strings
 * escaped, whole doubles written without a fraction, non-finite numbers as null. [pretty] indents
 * with two spaces (the files are small and readable by hand).
 */
class JsonWriter(private val pretty: Boolean = true) {
    private val out = StringBuilder(4096)
    private val first = BooleanArray(Json.MAX_DEPTH + 2)
    private var depth = 0
    private var afterName = false

    private fun beforeValue() {
        if (afterName) {
            afterName = false
            return
        }
        if (depth > 0) {
            if (!first[depth]) out.append(',')
            first[depth] = false
            newline()
        }
    }

    private fun newline() {
        if (!pretty) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    private fun open(c: Char): JsonWriter {
        beforeValue()
        out.append(c)
        depth++
        if (depth >= first.size) throw JsonException("nested too deep")
        first[depth] = true
        return this
    }

    private fun close(c: Char): JsonWriter {
        val empty = first[depth]
        depth--
        if (!empty) newline()
        out.append(c)
        return this
    }

    fun beginObject(): JsonWriter = open('{')
    fun endObject(): JsonWriter = close('}')
    fun beginArray(): JsonWriter = open('[')
    fun endArray(): JsonWriter = close(']')

    fun name(name: String): JsonWriter {
        beforeValue()
        string(name)
        out.append(':')
        if (pretty) out.append(' ')
        afterName = true
        return this
    }

    fun value(v: String?): JsonWriter {
        if (v == null) return nullValue()
        beforeValue()
        string(v)
        return this
    }

    fun value(v: Long): JsonWriter {
        beforeValue()
        out.append(v)
        return this
    }

    fun value(v: Int): JsonWriter = value(v.toLong())

    fun value(v: Double): JsonWriter {
        if (!v.isFinite()) return nullValue()
        beforeValue()
        if (v == floor(v) && kotlin.math.abs(v) < 1e15) out.append(v.toLong()) else out.append(v.toString())
        return this
    }

    fun value(v: Boolean): JsonWriter {
        beforeValue()
        out.append(if (v) "true" else "false")
        return this
    }

    fun nullValue(): JsonWriter {
        beforeValue()
        out.append("null")
        return this
    }

    fun field(name: String, v: String?): JsonWriter = name(name).value(v)
    fun field(name: String, v: Long): JsonWriter = name(name).value(v)
    fun field(name: String, v: Int): JsonWriter = name(name).value(v)
    fun field(name: String, v: Double): JsonWriter = name(name).value(v)
    fun field(name: String, v: Boolean): JsonWriter = name(name).value(v)

    private fun string(s: String) {
        out.append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' || c == ' ' || c == ' ' -> {
                    out.append("\\u")
                    val hex = Integer.toHexString(c.code)
                    repeat(4 - hex.length) { out.append('0') }
                    out.append(hex)
                }
                else -> out.append(c)
            }
        }
        out.append('"')
    }

    override fun toString(): String = out.toString()

    fun toBytes(): ByteArray = out.toString().toByteArray(Charsets.UTF_8)
}
