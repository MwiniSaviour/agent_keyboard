package com.agentkeyboard.core.json

/**
 * Minimal strict JSON (RFC 8259) for the bridge hot path.
 *
 * Why hand-rolled (ADR-0009): every keystroke crosses the bridge as one envelope, inside an IME
 * process budgeted at ≤ 90 MB with a ≤ 35 ms p95 touch→glyph target. This codec has no reflection,
 * no code generation, no dependency in the IME DEX, and parses a typical envelope in about a
 * microsecond on the JVM. It is not a general-purpose library. Inputs are capped ([MAX_INPUT_CHARS],
 * [MAX_DEPTH]) because the shell, the companion and notification text are all untrusted.
 */
sealed interface JsonValue

data object JsonNull : JsonValue

@JvmInline value class JsonBool(val value: Boolean) : JsonValue

/** Numbers keep the parsed [Double]; integral reads go through [JsonNum.asLong]. */
@JvmInline value class JsonNum(val value: Double) : JsonValue {
    fun asLong(): Long? = if (value == Math.rint(value) && !value.isInfinite() && kotlin.math.abs(value) <= MAX_SAFE_INT) value.toLong() else null
}

@JvmInline value class JsonStr(val value: String) : JsonValue

class JsonArr(val items: List<JsonValue>) : JsonValue {
    override fun equals(other: Any?) = other is JsonArr && other.items == items
    override fun hashCode() = items.hashCode()
    override fun toString() = Json.write(this)
}

/** Insertion-ordered so encoded output is deterministic (fixtures compare byte-for-byte). */
class JsonObj(val fields: Map<String, JsonValue>) : JsonValue {
    operator fun get(key: String): JsonValue? = fields[key]
    override fun equals(other: Any?) = other is JsonObj && other.fields == fields
    override fun hashCode() = fields.hashCode()
    override fun toString() = Json.write(this)
}

class JsonException(message: String) : RuntimeException(message)

const val MAX_INPUT_CHARS = 512 * 1024
const val MAX_DEPTH = 32
private const val MAX_SAFE_INT = 9_007_199_254_740_991.0 // Number.MAX_SAFE_INTEGER

object Json {
    fun parse(text: String): JsonValue {
        if (text.length > MAX_INPUT_CHARS) throw JsonException("input exceeds $MAX_INPUT_CHARS chars")
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.pos != text.length) p.fail("trailing characters")
        return v
    }

    fun write(value: JsonValue): String = StringBuilder(64).also { writeTo(it, value) }.toString()

    fun writeTo(out: StringBuilder, value: JsonValue) {
        when (value) {
            JsonNull -> out.append("null")
            is JsonBool -> out.append(if (value.value) "true" else "false")
            is JsonNum -> writeNumber(out, value.value)
            is JsonStr -> writeString(out, value.value)
            is JsonArr -> {
                out.append('[')
                value.items.forEachIndexed { i, v -> if (i > 0) out.append(','); writeTo(out, v) }
                out.append(']')
            }
            is JsonObj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, k); out.append(':'); writeTo(out, v)
                }
                out.append('}')
            }
        }
    }

    private fun writeNumber(out: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) throw JsonException("non-finite number")
        val l = d.toLong()
        if (l.toDouble() == d && kotlin.math.abs(d) <= MAX_SAFE_INT) out.append(l) else out.append(d)
    }

    /** Escapes like JSON.stringify: quotes, backslash, C0 controls, and lone surrogates as \uXXXX. */
    fun writeString(out: StringBuilder, s: String) {
        out.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000C' -> out.append("\\f")
                c < ' ' -> hex(out, c)
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    out.append(c).append(s[i + 1]); i++
                }
                Character.isSurrogate(c) -> hex(out, c)
                else -> out.append(c)
            }
            i++
        }
        out.append('"')
    }

    private fun hex(out: StringBuilder, c: Char) {
        out.append("\\u")
        val h = c.code.toString(16)
        repeat(4 - h.length) { out.append('0') }
        out.append(h)
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun fail(msg: String): Nothing = throw JsonException("$msg at $pos")

        fun ws() {
            while (pos < s.length) {
                val c = s[pos]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++ else return
            }
        }

        fun value(depth: Int): JsonValue {
            if (depth > MAX_DEPTH) fail("nesting exceeds $MAX_DEPTH")
            if (pos >= s.length) fail("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> JsonStr(str())
                't' -> lit("true", JsonBool(true))
                'f' -> lit("false", JsonBool(false))
                'n' -> lit("null", JsonNull)
                else -> if (c == '-' || c in '0'..'9') num() else fail("unexpected '$c'")
            }
        }

        private fun lit(word: String, v: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) fail("invalid literal")
            pos += word.length
            return v
        }

        private fun obj(depth: Int): JsonObj {
            pos++ // {
            val map = LinkedHashMap<String, JsonValue>()
            ws()
            if (peek() == '}') { pos++; return JsonObj(map) }
            while (true) {
                ws()
                if (peek() != '"') fail("expected key")
                val k = str()
                ws()
                if (peek() != ':') fail("expected ':'")
                pos++
                ws()
                map[k] = value(depth + 1)
                ws()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonObj(map) }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun arr(depth: Int): JsonArr {
            pos++ // [
            val list = ArrayList<JsonValue>()
            ws()
            if (peek() == ']') { pos++; return JsonArr(list) }
            while (true) {
                ws()
                list.add(value(depth + 1))
                ws()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonArr(list) }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else fail("unexpected end")

        private fun str(): String {
            pos++ // opening quote
            val start = pos
            // Fast path: no escapes.
            while (pos < s.length) {
                val c = s[pos]
                if (c == '"') return s.substring(start, pos).also { pos++ }
                if (c == '\\' || c < ' ') break
                pos++
            }
            val sb = StringBuilder(pos - start + 16).append(s, start, pos)
            while (true) {
                if (pos >= s.length) fail("unterminated string")
                val c = s[pos++]
                when {
                    c == '"' -> return sb.toString()
                    c < ' ' -> fail("control character in string")
                    c != '\\' -> sb.append(c)
                    else -> {
                        if (pos >= s.length) fail("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) fail("bad \\u escape")
                                val code = s.substring(pos, pos + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                sb.append(code.toChar()); pos += 4
                            }
                            else -> fail("bad escape '\\$e'")
                        }
                    }
                }
            }
        }

        private fun num(): JsonNum {
            val start = pos
            if (s[pos] == '-') pos++
            if (pos >= s.length) fail("bad number")
            if (s[pos] == '0') pos++ else digits()
            if (pos < s.length && s[pos] == '.') { pos++; digits() }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                digits()
            }
            val d = s.substring(start, pos).toDoubleOrNull() ?: fail("bad number")
            if (d.isInfinite()) fail("number out of range")
            return JsonNum(d)
        }

        private fun digits() {
            val start = pos
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos == start) fail("expected digit")
        }
    }
}

// ───────────── ergonomic builders / readers (keep call sites terse and allocation-light) ─────────────

fun obj(vararg pairs: Pair<String, Any?>): JsonObj {
    val m = LinkedHashMap<String, JsonValue>(pairs.size * 2)
    for ((k, v) in pairs) if (v != null) m[k] = toJson(v)
    return JsonObj(m)
}

fun arr(items: Iterable<Any?>): JsonArr = JsonArr(items.map { toJson(it) })

/** Converts Kotlin primitives/collections; `null` map entries are omitted by [obj] (TS optional fields). */
fun toJson(v: Any?): JsonValue = when (v) {
    null -> JsonNull
    is JsonValue -> v
    is String -> JsonStr(v)
    is Boolean -> JsonBool(v)
    is Int -> JsonNum(v.toDouble())
    is Long -> JsonNum(v.toDouble())
    is Double -> JsonNum(v)
    is Float -> JsonNum(v.toDouble())
    is Map<*, *> -> JsonObj(v.entries.associate { (k, x) -> k.toString() to toJson(x) })
    is Iterable<*> -> arr(v)
    is JsonEncodable -> v.toJson()
    else -> throw JsonException("cannot encode ${v::class.java.simpleName}")
}

interface JsonEncodable {
    fun toJson(): JsonValue
}

fun JsonValue?.str(): String? = (this as? JsonStr)?.value
fun JsonValue?.bool(): Boolean? = (this as? JsonBool)?.value
fun JsonValue?.long(): Long? = (this as? JsonNum)?.asLong()
fun JsonValue?.int(): Int? = long()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
fun JsonValue?.num(): Double? = (this as? JsonNum)?.value
fun JsonValue?.objOrNull(): JsonObj? = this as? JsonObj
fun JsonValue?.arrOrNull(): JsonArr? = this as? JsonArr
