package com.otakustream.core.common

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// intOrNull parses responses from third-party add-ons, so the input is whatever they felt like
// sending. The failure that matters is not "this field is wrong" but "one wrong field threw, the
// whole response was discarded, and the user saw an empty catalog".
class JsonExtTest {

    @Test
    fun `a numeric field is read`() {
        assertEquals(2024, JSONObject("""{"year":2024}""").intOrNull("year"))
    }

    @Test
    fun `a numeric string is still coerced`() {
        // getInt accepts this and real add-ons send it, so the fix must not tighten the behaviour.
        assertEquals(5, JSONObject("""{"season":"5"}""").intOrNull("season"))
    }

    @Test
    fun `a missing or null field is null`() {
        assertNull(JSONObject("""{}""").intOrNull("year"))
        assertNull(JSONObject("""{"year":null}""").intOrNull("year"))
    }

    @Test
    fun `an uncoercible value is null rather than an exception`() {
        // The bug itself: each of these made getInt throw, and the throw escaped the whole parse.
        assertNull(JSONObject("""{"year":"N/A"}""").intOrNull("year"))
        assertNull(JSONObject("""{"year":""}""").intOrNull("year"))
        assertNull(JSONObject("""{"year":{}}""").intOrNull("year"))
        assertNull(JSONObject("""{"year":[]}""").intOrNull("year"))
    }

    @Test
    fun `one bad field does not stop the others being read`() {
        // The consequence as the caller experiences it: a meta with a junk year still yields its
        // season and episode instead of the whole item disappearing.
        val json = JSONObject("""{"year":"unknown","season":2,"episode":7}""")

        assertNull(json.intOrNull("year"))
        assertEquals(2, json.intOrNull("season"))
        assertEquals(7, json.intOrNull("episode"))
    }
}
