package com.koupper.providers.runtime.router

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.resolveCorsAllowOrigin
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuntimeRouterProviderTest : AnnotationSpec() {

    @Before
    fun clearRegistry() {
        GlobalRouteRegistry.routes.clear()
        GlobalRouteRegistry.corsConfig = null
        GlobalRouteRegistry.afterRequestHook = null
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String, origin: String? = null): HttpResponse<String> {
        val client = HttpClient.newHttpClient()
        val builder = HttpRequest.newBuilder(URI(url)).GET()
        if (origin != null) builder.header("Origin", origin)
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(url: String, body: String): HttpResponse<String> {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI(url))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .header("Content-Type", "application/json")
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `resolveCorsAllowOrigin returns star for wildcard or empty`() {
        assertEquals("*", resolveCorsAllowOrigin(listOf("*"), "https://igly.mx"))
        assertEquals("*", resolveCorsAllowOrigin(emptyList(), "https://igly.mx"))
        assertEquals("*", resolveCorsAllowOrigin(null, null))
    }

    @Test
    fun `resolveCorsAllowOrigin echoes matching origin and rejects others`() {
        val allowed = listOf("http://localhost:3000", "https://igly.mx")
        assertEquals("https://igly.mx", resolveCorsAllowOrigin(allowed, "https://igly.mx"))
        assertNull(resolveCorsAllowOrigin(allowed, "https://evil.example"))
        assertNull(resolveCorsAllowOrigin(allowed, null))
    }

    @Test
    fun `CORS allow-list echoes single Origin header`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        provider.registerRouter {
            cors {
                allowedOrigins = listOf("http://localhost:3000", "https://igly.mx")
            }
            get<String> {
                path { "/cors-ok" }
                script { { "ok" } }
            }
        }
        provider.start(port)
        try {
            val allowed = get("http://127.0.0.1:$port/cors-ok", origin = "https://igly.mx")
            assertEquals(200, allowed.statusCode())
            assertEquals("https://igly.mx", allowed.headers().firstValue("Access-Control-Allow-Origin").orElse(null))

            val denied = get("http://127.0.0.1:$port/cors-ok", origin = "https://evil.example")
            assertEquals(200, denied.statusCode())
            assertTrue(denied.headers().firstValue("Access-Control-Allow-Origin").isEmpty)
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `registerRouter returns correct route list without starting server`() {
        val provider = GrizzlyRuntimeRouterProvider()
        val info = provider.registerRouter {
            path { "/api" }
            get<String> {
                path { "/ping" }
                script { { "pong" } }
            }
        }
        assertTrue(info.routes.contains("GET /api/ping"))
    }

    @Test
    fun `GET route responds with 200 and handler output`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        provider.registerRouter {
            get<String> {
                path { "/hello" }
                script { { "world" } }
            }
        }
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/hello")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("world"))
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `unknown route returns 404`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/not-found")
            assertEquals(404, response.statusCode())
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `middleware blocks request and returns configured status code`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        provider.registerMiddleware("auth") { _ ->
            MiddlewareResult(allowed = false, statusCode = 401, message = "No token")
        }
        provider.registerRouter {
            get<String> {
                path { "/secure" }
                middlewares { listOf("auth") }
                script { { "secret data" } }
            }
        }
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/secure")
            assertEquals(401, response.statusCode())
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `middleware allows request when allowed is true`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        provider.registerMiddleware("permissive") { _ ->
            MiddlewareResult(allowed = true)
        }
        provider.registerRouter {
            get<String> {
                path { "/guarded" }
                middlewares { listOf("permissive") }
                script { { "ok" } }
            }
        }
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/guarded")
            assertEquals(200, response.statusCode())
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `POST route with String body receives and returns body`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        var received = ""
        provider.registerRouter {
            post<String> {
                path { "/echo" }
                script { { body: String -> received = body; body } }
            }
        }
        provider.start(port)
        try {
            post("http://127.0.0.1:$port/echo", "hello")
            assertEquals("hello", received)
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `stop can be called when server is not running`() {
        val provider = GrizzlyRuntimeRouterProvider()
        provider.stop() // should not throw
    }

    @Test
    fun `afterRequestHook is called with status and positive durationMs on successful request`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        var capturedStatus = -1
        var capturedDuration = -1L

        GlobalRouteRegistry.afterRequestHook = { status, durationMs ->
            capturedStatus = status
            capturedDuration = durationMs
        }

        provider.registerRouter {
            get<String> {
                path { "/hook-test" }
                script { { "ok" } }
            }
        }
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/hook-test")
            assertEquals(200, response.statusCode())
            assertEquals(200, capturedStatus)
            assertTrue(capturedDuration >= 0, "Expected durationMs >= 0, got $capturedDuration")
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `afterRequestHook receives 404 status for unknown route`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        var capturedStatus = -1

        GlobalRouteRegistry.afterRequestHook = { status, _ -> capturedStatus = status }

        provider.start(port)
        try {
            get("http://127.0.0.1:$port/does-not-exist")
            assertEquals(404, capturedStatus)
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `afterRequestHook is not called when hook is null`() {
        val port = freePort()
        val provider = GrizzlyRuntimeRouterProvider()
        GlobalRouteRegistry.afterRequestHook = null

        provider.registerRouter {
            get<String> {
                path { "/no-hook" }
                script { { "fine" } }
            }
        }
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/no-hook")
            assertEquals(200, response.statusCode()) // must not throw
        } finally {
            provider.stop()
        }
    }
}
