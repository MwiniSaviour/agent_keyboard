package com.agentkeyboard.core.json

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JsonTest {
    @Test fun parsesAllValueKinds() {
        val v = Json.parse("""{"a":1,"b":-2.5e1,"c":"x","d":true,"e":false,"f":null,"g":[1,[2]],"h":{}}""") as JsonObj
        assertEquals(1L, v["a"].long())
        assertEquals(-25.0, v["b"].num())
        assertEquals("x", v["c"].str())
        assertEquals(true, v["d"].bool())
        assertEquals(JsonNull, v["f"])
        assertEquals(2, v["g"].arrOrNull()!!.items.size)
    }

    @Test fun roundTripsEscapesAndAstralCharacters() {
        val s = "q\"b\\s/n\nt\tc\u0001 👋🏽 é"
        val encoded = Json.write(JsonStr(s))
        assertEquals("\"q\\\"b\\\\s/n\\nt\\tc\\u0001 👋🏽 é\"", encoded)
        assertEquals(s, Json.parse(encoded).str())
        assertEquals("😀", Json.parse("\"\\ud83d\\ude00\"").str())
    }

    @Test fun loneSurrogatesAreEscapedLikeJsonStringify() {
        assertEquals("\"a\\ud83d\"", Json.write(JsonStr("a\uD83D")))
    }

    @Test fun integersEncodeWithoutDecimalPoint() {
        assertEquals("""{"n":1764201600000,"f":0.25,"z":0}""", Json.write(obj("n" to 1764201600000L, "f" to 0.25, "z" to 0)))
    }

    @Test fun objOmitsNullFieldsLikeOptionalTsProperties() {
        assertEquals("""{"a":1}""", Json.write(obj("a" to 1, "b" to null)))
    }

    @Test fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "[1,]", "{\"a\":}", "01", "1.", "-", "\"\\x\"", "tru", "{\"a\":1}x", "\"a\nb\"", "NaN", "{'a':1}")) {
            assertFailsWith<JsonException>(bad) { Json.parse(bad) }
        }
    }

    @Test fun enforcesDepthAndSizeLimits() {
        assertFailsWith<JsonException> { Json.parse("[".repeat(MAX_DEPTH + 2) + "]".repeat(MAX_DEPTH + 2)) }
        Json.parse("[".repeat(MAX_DEPTH) + "]".repeat(MAX_DEPTH))
        assertFailsWith<JsonException> { Json.parse("\"" + "a".repeat(MAX_INPUT_CHARS) + "\"") }
    }

    @Test fun fractionalAndHugeNumbersAreNotIntegers() {
        assertEquals(null, Json.parse("1.5").long())
        assertEquals(null, Json.parse("1e300").long())
        assertEquals(9_007_199_254_740_991L, Json.parse("9007199254740991").long())
    }

    @Test fun parseIsFastEnoughForTheKeystrokePath() {
        val env = """{"v":1,"id":"s:1234","type":"text.commit","ts":1764201600000,"payload":{"text":"a"}}"""
        repeat(20_000) { Json.parse(env) } // warm-up
        val n = 100_000
        val t0 = System.nanoTime()
        repeat(n) { Json.parse(env) }
        val perOpUs = (System.nanoTime() - t0) / 1000.0 / n
        // Generous bound (CI noise); a keystroke budget is 35 ms, so even 50 µs would be < 0.2 %.
        assertTrue(perOpUs < 50.0, "parse took $perOpUs µs/op")
        println("    json parse: ${"%.2f".format(perOpUs)} µs/envelope")
    }
}
