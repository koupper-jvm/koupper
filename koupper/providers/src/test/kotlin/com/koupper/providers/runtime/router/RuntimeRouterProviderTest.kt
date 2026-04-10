package com.koupper.providers.runtime.router

import io.kotest.core.spec.style.AnnotationSpec
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RuntimeRouterProviderTest : AnnotationSpec() {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String): HttpResponse<String> {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
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
    fun `registerRouter returns correct route list without starting server`() {
        val provider = JdkRuntimeRouterProvider()
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
        val provider = JdkRuntimeRouterProvider()
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
        val provider = JdkRuntimeRouterProvider()
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
        val provider = JdkRuntimeRouterProvider()
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
        val provider = JdkRuntimeRouterProvider()
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
        val provider = JdkRuntimeRouterProvider()
        var received = ""
        provider.registerRouter {
            post(String::class) {
                path { "/echo" }
                script { { body -> received = body; body } }
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
        val provider = JdkRuntimeRouterProvider()
        provider.stop() // should not throw
    }
}
