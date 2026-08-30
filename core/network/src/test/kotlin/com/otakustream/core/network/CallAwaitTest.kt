package com.otakustream.core.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CallAwaitTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun call(path: String = "/") =
        client.newCall(Request.Builder().url(server.url(path)).build())

    @Test
    fun `returns the response body`() = runTest {
        server.enqueue(MockResponse().setBody("hello").setResponseCode(200))
        val response = withContext(Dispatchers.IO) { call().await() }
        assertEquals(200, response.code)
        assertEquals("hello", response.body?.string())
    }

    @Test
    fun `a non-2xx response is returned rather than thrown`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))
        val response = withContext(Dispatchers.IO) { call().await() }
        assertEquals(403, response.code)
        response.close()
    }

    @Test
    fun `a connection failure surfaces as IOException`() = runTest {
        server.shutdown()
        val thrown = runCatching { withContext(Dispatchers.IO) { call().await() } }.exceptionOrNull()
        assertTrue("expected IOException, got $thrown", thrown is IOException)
    }

    // The point of the change. `withContext(IO) { execute() }` would leave the request running here:
    // cancellation cannot interrupt a blocking call. Awaiting an enqueued call can actually cancel
    // it, which is what this asserts — and it asserts it on the call itself, not merely that the
    // coroutine returned, because "the coroutine gave up" was already true of the old code.
    @Test
    fun `cancelling the coroutine cancels the underlying call`() = runTest {
        val requestReceived = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requestReceived.countDown()
                // Hold the response open so the call is unambiguously in flight when cancelled.
                release.await(5, TimeUnit.SECONDS)
                return MockResponse().setBody("too late")
            }
        }

        val call = call()
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.IO) {
            started.complete(Unit)
            call.await()
        }
        started.await()
        assertTrue("server never saw the request", requestReceived.await(5, TimeUnit.SECONDS))

        job.cancel()
        job.join()

        // The observable consequence: OkHttp knows the call is dead, so the connection is not being
        // held for a result nobody wants.
        assertTrue("the underlying call was abandoned but not cancelled", call.isCanceled())
        release.countDown()
    }

    // A response that arrives after cancellation has no owner — nothing will read or close it, and
    // an unclosed body keeps its connection out of the pool. This pins that await closes it.
    @Test
    fun `a response arriving after cancellation does not leak its connection`() = runTest {
        server.enqueue(MockResponse().setBody("late").setBodyDelay(300, TimeUnit.MILLISECONDS))
        val call = call()
        val job = async(Dispatchers.IO) { call.await() }
        // Cancel before the delayed body can arrive.
        job.cancel()
        job.join()
        // Give the dispatcher time to deliver onResponse/onFailure into the cancelled continuation.
        Thread.sleep(600)
        assertEquals("no connection should be left in use", 0, client.connectionPool.connectionCount() - client.connectionPool.idleConnectionCount())
    }

    @Test
    fun `sequential awaits reuse the client`() = runTest {
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        withContext(Dispatchers.IO) {
            assertEquals("one", call().await().body?.string())
            assertEquals("two", call().await().body?.string())
        }
        assertEquals(2, server.requestCount)
    }
}
