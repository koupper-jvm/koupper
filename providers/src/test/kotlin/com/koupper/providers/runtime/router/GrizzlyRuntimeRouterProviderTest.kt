package com.koupper.providers.runtime.router

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.shared.annotations.WebRoute
import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.RouteMethod
import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.RegisteredRuntimeRoute
import com.koupper.shared.runtime.RouteMethod as RuntimeRouteMethod
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

class GrizzlyRuntimeRouterProviderTest : AnnotationSpec() {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun get(url: String): HttpResponse<String> {
        val client = HttpClient.newHttpClient()
        val request = HttpRequest.newBuilder(URI(url)).GET().build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Before
    fun clearRegistry() {
        GlobalRouteRegistry.routes.clear()
    }

    // Mock handlers
    object MockHandlers {
        @Export
        @WebRoute(path = "/api/test/{id}", method = RouteMethod.GET)
        val getWithId: (String) -> Map<String, Any> = { id ->
            mapOf("receivedId" to id)
        }

        @Export
        @WebRoute(path = "/api/complex", method = RouteMethod.GET)
        val getComplex: (TestRequest) -> Map<String, Any> = { req ->
            mapOf("name" to req.name, "age" to req.age)
        }

        @Export
        @WebRoute(path = "/api/list", method = RouteMethod.GET)
        val getList: (List<String>) -> Map<String, Any> = { items ->
            mapOf("items" to items)
        }
    }

    data class TestRequest(val name: String, val age: Int)

    @Test
    fun `should extract path parameters correctly`() {
        val provider = GrizzlyRuntimeRouterProvider()
        GlobalRouteRegistry.routes.add(RegisteredRuntimeRoute(
            method = RuntimeRouteMethod.GET,
            fullPath = "/api/test/{id}",
            middlewares = emptyList(),
            handler = MockHandlers.getWithId,
            inputType = String::class.java
        ))

        val port = freePort()
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/api/test/ninja-123")
            assertEquals(200, response.statusCode(), "Expected 200 but got ${response.statusCode()} with body: ${response.body()}")
            assertTrue(response.body().contains("ninja-123"), "Body was: ${response.body()}")
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `should map query parameters to data class`() {
        val provider = GrizzlyRuntimeRouterProvider()
        GlobalRouteRegistry.routes.add(RegisteredRuntimeRoute(
            method = RuntimeRouteMethod.GET,
            fullPath = "/api/complex",
            middlewares = emptyList(),
            handler = MockHandlers.getComplex,
            inputType = TestRequest::class.java
        ))

        val port = freePort()
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/api/complex?name=Hanzo&age=30")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("\"name\":\"Hanzo\""))
            assertTrue(response.body().contains("\"age\":30"))
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `should map multiple query parameters to a list`() {
        val provider = GrizzlyRuntimeRouterProvider()
        val listType = object : Any() {
            fun getList(items: List<String>) {}
        }.javaClass.methods.first { it.name == "getList" }.genericParameterTypes[0]

        GlobalRouteRegistry.routes.add(RegisteredRuntimeRoute(
            method = RuntimeRouteMethod.GET,
            fullPath = "/api/list",
            middlewares = emptyList(),
            handler = MockHandlers.getList,
            inputType = listType
        ))

        val port = freePort()
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/api/list?items=ninja1&items=ninja2")
            assertEquals(200, response.statusCode())
            assertTrue(response.body().contains("[\"ninja1\",\"ninja2\"]"), "Body was: ${response.body()}")
        } finally {
            provider.stop()
        }
    }

    @Test
    fun `should return 400 when query parameter mapping fails`() {
        val provider = GrizzlyRuntimeRouterProvider()
        GlobalRouteRegistry.routes.add(RegisteredRuntimeRoute(
            method = RuntimeRouteMethod.GET,
            fullPath = "/api/complex",
            middlewares = emptyList(),
            handler = MockHandlers.getComplex,
            inputType = TestRequest::class.java
        ))

        val port = freePort()
        provider.start(port)
        try {
            val response = get("http://127.0.0.1:$port/api/complex?name=Hanzo&age=not-a-number")
            assertEquals(400, response.statusCode())
            assertTrue(response.body().contains("Invalid input format"))
        } finally {
            provider.stop()
        }
    }
}
