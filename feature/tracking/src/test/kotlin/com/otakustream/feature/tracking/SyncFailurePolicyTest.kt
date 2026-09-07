package com.otakustream.feature.tracking

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

// Both halves of "should this failure sign the user out?", which is the question the previous code
// never asked — it logged everything and kept going.
//
// The two directions cost different things and both are silent. Missing a dead token leaves sync
// broken forever while Settings claims to be signed in; treating an outage as a dead token signs
// the user out of AniList and makes them redo OAuth for nothing.
class SyncFailurePolicyTest {

    @Test
    fun `a rejected token signs the user out`() {
        assertEquals(SyncFailureAction.SignOut, syncFailureAction(AniListUnauthorizedException()))
    }

    @Test
    fun `cancellation is rethrown, not swallowed`() {
        // The bug class this repo keeps rediscovering: a catch broad enough to eat cancellation
        // leaves the coroutine running inside a scope that has already been cancelled.
        assertEquals(SyncFailureAction.Rethrow, syncFailureAction(CancellationException("navigated away")))
    }

    @Test
    fun `an ordinary network failure is logged and dropped`() {
        assertEquals(SyncFailureAction.LogAndIgnore, syncFailureAction(IOException("timeout")))
        assertEquals(SyncFailureAction.LogAndIgnore, syncFailureAction(IllegalStateException("AniList request failed: HTTP 503")))
    }

    @Test
    fun `a 401 on an authenticated request is a token rejection`() {
        assertTrue(isTokenRejection(token = "abc", code = 401, message = null))
    }

    @Test
    fun `AniList's 400 Invalid token is a token rejection`() {
        // What AniList actually answers a dead token with — a 400, not a 401. Matching only on the
        // status code would miss every real expiry.
        assertTrue(isTokenRejection(token = "abc", code = 400, message = "Invalid token"))
        assertTrue(isTokenRejection(token = "abc", code = 400, message = "Unauthorized"))
    }

    @Test
    fun `a message-based rejection only counts on the status AniList uses for it`() {
        // The first version matched the message on any status, which is far too wide: a 200 GraphQL
        // response carrying an "unauthorized" error for one field of a query, or a 5xx whose HTML
        // body happens to contain the word, would have signed the user out of an account nothing
        // had rejected.
        assertFalse(isTokenRejection(token = "abc", code = 200, message = "Unauthorized"))
        assertFalse(isTokenRejection(token = "abc", code = 500, message = "Invalid token"))
        assertFalse(isTokenRejection(token = "abc", code = 403, message = "unauthenticated"))
        // 400 is what AniList actually answers a dead token with, and 401 stands on its own.
        assertTrue(isTokenRejection(token = "abc", code = 400, message = "Invalid token"))
        assertTrue(isTokenRejection(token = "abc", code = 401, message = null))
    }

    @Test
    fun `an outage is not a token rejection`() {
        assertFalse(isTokenRejection(token = "abc", code = 500, message = "Internal Server Error"))
        assertFalse(isTokenRejection(token = "abc", code = 503, message = null))
        assertFalse(isTokenRejection(token = "abc", code = 429, message = "Too Many Requests"))
        assertFalse(isTokenRejection(token = "abc", code = 400, message = "Variable 'mediaId' was not provided"))
    }

    @Test
    fun `an unauthenticated request can never be a token rejection`() {
        // Browse and search share this code path with no token at all. Nothing they return should
        // be able to sign the user out of an account the request never mentioned.
        assertFalse(isTokenRejection(token = null, code = 401, message = "Invalid token"))
        assertFalse(isTokenRejection(token = null, code = 400, message = "Unauthorized"))
    }
}
