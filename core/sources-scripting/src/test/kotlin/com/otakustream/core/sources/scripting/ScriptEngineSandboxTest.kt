package com.otakustream.core.sources.scripting

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// A scripted source is a `.js` file the user pastes a URL to — third-party code, from a stranger,
// running inside the app process on every cold start. `docs/scripted-sources.md` promises it gets
// exactly one capability, `httpGet`, and specifically "no filesystem, no reflection, no Java
// interop". These are the tests that hold that promise to account.
//
// They are worth reading as an attack list rather than a checklist. Until this file existed the
// engine used Rhino's `initStandardObjects()`, which installs the Java interop bridge — so every
// escape below actually worked, and the documented sandbox did not exist.
class ScriptEngineSandboxTest {

    // Every request the bridge makes is answered here instead of going to the network, so the
    // positive control below can call httpGet for real — through the injected BaseFunction, into
    // HttpBridge, out through OkHttp's call chain — without a server or a network-dependent test.
    private val requestedUrls = mutableListOf<String>()
    private val requestedHeaders = mutableListOf<Map<String, String>>()

    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                requestedUrls += request.url.toString()
                requestedHeaders += request.headers.toMultimap().mapValues { it.value.first() }
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"items":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            },
        )
        .build()

    private val engine = ScriptEngine(HttpBridge(httpClient))

    private fun probe(expression: String): String {
        val scope = engine.load("function probe() { return String($expression); }", "probe.js")
        return engine.call(scope, "probe")
    }

    private fun assertScriptFails(source: String, what: String) {
        try {
            engine.load(source, "escape-attempt.js")
            fail("Sandbox escape succeeded: $what")
        } catch (expected: Exception) {
            // Rhino raises a ReferenceError (EcmaError) for the undefined interop globals, and the
            // ClassShutter refuses any class lookup that somehow gets past them. Either is a pass.
        }
    }

    @Test
    fun `rhino java interop globals are not reachable`() {
        // These are what initStandardObjects() adds and initSafeStandardObjects() does not. Any one
        // of them is a complete escape: they all lead to java.lang.Class and from there to anything.
        listOf("Packages", "java", "javax", "org", "com", "net", "edu", "JavaAdapter", "getClass")
            .forEach { global ->
                assertEquals("`$global` must not be visible to scripts", "undefined", probe("typeof $global"))
            }
    }

    @Test
    fun `a script cannot open a file`() {
        // The first step of reading the Room database or the encrypted prefs off disk.
        assertScriptFails("var f = new java.io.File('/');", "java.io.File was constructible")
        assertScriptFails("var f = new Packages.java.io.File('/');", "Packages.java.io.File was constructible")
    }

    @Test
    fun `a script cannot use reflection`() {
        // Reflection is the general form of the exfiltration path. On a device the specific route is
        // Class.forName("android.app.ActivityThread").getMethod("currentApplication") — an
        // Application hands you EncryptedSharedPreferences, which decrypts the AniList bearer token
        // for anyone already inside this process.
        //
        // Asserted against java.lang.System rather than that Android class on purpose: these tests
        // run on the JVM, where android.* is absent from the classpath, so a ClassNotFoundException
        // would look like a pass whether or not the sandbox worked. java.lang.System is present, so
        // the only thing that can stop it is the sandbox.
        assertScriptFails("java.lang.Class.forName('java.lang.System');", "Class.forName was reachable")
        assertScriptFails(
            "java.lang.System.getProperty('user.home');",
            "read a system property through Java interop",
        )
        assertScriptFails(
            "Packages.java.lang.Class.forName('java.lang.System').getMethods();",
            "enumerated methods reflectively",
        )
    }

    @Test
    fun `a script cannot execute a process`() {
        assertScriptFails("java.lang.Runtime.getRuntime().exec('id');", "Runtime.exec was reachable")
    }

    @Test
    fun `a script cannot reach java classes through an object's prototype`() {
        // getClass() on any value is the other classic route to the reflection graph, and it does
        // not depend on the interop globals being present.
        assertEquals("undefined", probe("typeof ({}).getClass"))
        assertEquals("undefined", probe("typeof [].getClass"))
        assertScriptFails("var c = ({}).getClass();", "getClass() was callable on a plain object")
    }

    // Positive controls. A sandbox that also breaks legitimate sources is not a fix, so these pin
    // the capabilities scripted sources are actually documented to have.

    @Test
    fun `ordinary javascript still works`() {
        assertEquals("6", probe("[1,2,3].reduce(function (a, b) { return a + b; }, 0)"))
        assertEquals("{\"a\":1}", probe("JSON.stringify({ a: 1 })"))
        assertEquals("ABC", probe("'abc'.toUpperCase()"))
        assertEquals("2", probe("Math.max(1, 2)"))
        assertEquals("true", probe("/^h/.test('hello')"))
    }

    @Test
    fun `the httpGet capability still works end to end`() {
        // Not just "the global is a function". The sandbox works by removing Rhino's Java bridge, and
        // httpGet is the one thing deliberately left reaching out of the sandbox — so the test that
        // matters is that a script can still call it and get the host's response back. Asserting the
        // type alone would stay green if the ClassShutter started refusing the BaseFunction's own
        // call path.
        assertEquals("function", probe("typeof httpGet"))

        val scope = engine.load(
            "function fetchIt() { return httpGet('https://example.test/api', '{\"Referer\":\"https://example.test/\"}'); }",
            "fetch.js",
        )

        assertEquals("""{"items":[]}""", engine.call(scope, "fetchIt"))
        assertEquals(listOf("https://example.test/api"), requestedUrls)
        // The headers argument is the other half of the documented contract — sources need it because
        // hosts reject requests without a referer.
        assertEquals("https://example.test/", requestedHeaders.single()["referer"])
    }

    @Test
    fun `httpGet failures surface to the script rather than killing the app`() {
        // A source pointed at a dead host is routine, and the documented behaviour is that the call
        // fails that one function rather than taking anything else down.
        val failing = ScriptEngine(
            HttpBridge(
                OkHttpClient.Builder()
                    .addInterceptor { throw java.io.IOException("host is down") }
                    .build(),
            ),
        )
        val scope = failing.load(
            "function tryFetch() { try { httpGet('https://down.test/'); return 'no-error'; } catch (e) { return 'caught'; } }",
            "failing.js",
        )

        assertEquals("caught", failing.call(scope, "tryFetch"))
    }

    @Test
    fun `httpGet does not hand the script a java object it can climb out of`() {
        // The bridge returns the body as a String. If it ever returned the Response — or anything
        // else Rhino would wrap as a Java object — the script would have a live Java reference and
        // the ClassShutter would be the only thing standing between it and the reflection graph.
        val scope = engine.load(
            "function typeOfResult() { var r = httpGet('https://example.test/'); return typeof r; }",
            "type.js",
        )
        assertEquals("string", engine.call(scope, "typeOfResult"))

        val probeScope = engine.load(
            "function getClassOfResult() { return String(httpGet('https://example.test/').getClass); }",
            "getclass.js",
        )
        assertTrue(
            "the response must not expose getClass",
            engine.call(probeScope, "getClassOfResult") == "undefined",
        )
    }

    @Test
    fun `script globals and functions are still readable`() {
        val scope = engine.load(
            """
            var sourceName = "Example";
            function search(query) { return "searched:" + query; }
            """.trimIndent(),
            "source.js",
        )
        assertEquals("Example", engine.readString(scope, "sourceName", "fallback"))
        assertEquals("fallback", engine.readString(scope, "missingGlobal", "fallback"))
        assertEquals("searched:naruto", engine.call(scope, "search", "naruto"))
    }

    @Test
    fun `each script gets an isolated scope`() {
        // Why the scope is deliberately not sealed: isolation already prevents one source from
        // poisoning another's prototypes, so sealing would only cost legitimate polyfills.
        val first = engine.load("var secret = 'first'; Array.prototype.injected = 'tampered';", "a.js")
        val second = engine.load("var secret = 'second';", "b.js")

        assertEquals("first", engine.readString(first, "secret", ""))
        assertEquals("second", engine.readString(second, "secret", ""))

        val leaked = engine.load("function probe() { return String([].injected); }", "c.js")
        assertEquals("undefined", engine.call(leaked, "probe"))
    }
}
