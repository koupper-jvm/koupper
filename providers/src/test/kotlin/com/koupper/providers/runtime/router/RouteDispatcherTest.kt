package com.koupper.providers.runtime.router

import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.RegisteredRuntimeRoute
import com.koupper.shared.runtime.RouteMethod
import com.koupper.shared.runtime.WebResponse
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RouteDispatcherTest : AnnotationSpec() {

    private val dispatcher = RouteDispatcher()

    data class TestBody(val name: String, val age: Int)

    @Before
    fun clearRegistry() {
        GlobalRouteRegistry.routes.clear()
        GlobalRouteRegistry.middlewares.clear()
        GlobalRouteRegistry.exceptionHandler = null
    }

    private fun registerRoute(
        method: RouteMethod,
        path: String,
        handler: Any,
        middlewares: List<String> = emptyList(),
        inputType: java.lang.reflect.Type? = null
    ) {
        GlobalRouteRegistry.routes.add(RegisteredRuntimeRoute(
            method = method,
            fullPath = path,
            middlewares = middlewares,
            handler = handler,
            inputType = inputType
        ))
    }

    @Test
    fun `returns 404 for unknown route`() {
        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/nope"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(404, completed.status)
    }

    @Test
    fun `invokes zero-arg handler and returns its output`() {
        registerRoute(RouteMethod.GET, "/hello", { "world" })

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/hello"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(200, completed.status)
        assertEquals("world", completed.payload)
    }

    @Test
    fun `extracts path parameter for String input`() {
        val handler: (String) -> Map<String, Any> = { id -> mapOf("receivedId" to id) }
        registerRoute(RouteMethod.GET, "/api/test/{id}", handler, inputType = String::class.java)

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/api/test/ninja-123"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(mapOf("receivedId" to "ninja-123"), completed.payload)
    }

    @Test
    fun `deserializes POST JSON body into data class`() {
        val handler: (TestBody) -> Map<String, Any> = { body -> mapOf("name" to body.name, "age" to body.age) }
        registerRoute(RouteMethod.POST, "/api/people", handler, inputType = TestBody::class.java)

        val outcome = dispatcher.dispatch(DispatchRequest(
            method = "POST",
            path = "/api/people",
            contentType = "application/json",
            bodyProvider = { """{"name":"Hanzo","age":30}""".toByteArray() }
        ))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(mapOf("name" to "Hanzo", "age" to 30), completed.payload)
    }

    @Test
    fun `maps query parameters into data class`() {
        val handler: (TestBody) -> Map<String, Any> = { body -> mapOf("name" to body.name, "age" to body.age) }
        registerRoute(RouteMethod.GET, "/api/people", handler, inputType = TestBody::class.java)

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/api/people", queryString = "name=Hanzo&age=30"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(mapOf("name" to "Hanzo", "age" to 30), completed.payload)
    }

    @Test
    fun `returns 400 when argument mapping fails`() {
        val handler: (TestBody) -> Map<String, Any> = { body -> mapOf("name" to body.name) }
        registerRoute(RouteMethod.GET, "/api/people", handler, inputType = TestBody::class.java)

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/api/people", queryString = "name=Hanzo&age=not-a-number"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(400, completed.status)
    }

    @Test
    fun `blocking middleware short-circuits with its status code`() {
        GlobalRouteRegistry.middlewares["auth"] = { MiddlewareResult(allowed = false, statusCode = 401, message = "No token") }
        registerRoute(RouteMethod.GET, "/secure", { "secret" }, middlewares = listOf("auth"))

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/secure"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(401, completed.status)
    }

    @Test
    fun `allowing middleware lets the handler run`() {
        GlobalRouteRegistry.middlewares["permissive"] = { MiddlewareResult(allowed = true) }
        registerRoute(RouteMethod.GET, "/guarded", { "ok" }, middlewares = listOf("permissive"))

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/guarded"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(200, completed.status)
        assertEquals("ok", completed.payload)
    }

    @Test
    fun `unwraps WebResponse into status, body, contentType and headers`() {
        registerRoute(RouteMethod.GET, "/custom", {
            WebResponse(mapOf("x" to 1), 201, "application/json", mapOf("X-Custom" to "yes"))
        })

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/custom"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(201, completed.status)
        assertEquals(mapOf("x" to 1), completed.payload)
        assertEquals("application/json", completed.contentType)
        assertEquals("yes", completed.headers["X-Custom"])
    }

    @Test
    fun `returns Stream outcome when handler produces a StreamResponse`() {
        val emitter = SseEmitter()
        registerRoute(RouteMethod.GET, "/events", { emitter })

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/events"))

        val stream = assertIs<DispatchOutcome.Stream>(outcome)
        assertEquals(emitter, stream.stream)
    }

    @Test
    fun `handler exception without exceptionHandler returns 500`() {
        registerRoute(RouteMethod.GET, "/boom", { throw IllegalStateException("kaput") })

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/boom"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(500, completed.status)
    }

    @Test
    fun `handler exception is routed through registered exceptionHandler`() {
        GlobalRouteRegistry.exceptionHandler = { t -> WebResponse(mapOf("handled" to (t.message ?: "")), 418) }
        registerRoute(RouteMethod.GET, "/boom", { throw IllegalStateException("kaput") })

        val outcome = dispatcher.dispatch(DispatchRequest("GET", "/boom"))

        val completed = assertIs<DispatchOutcome.Completed>(outcome)
        assertEquals(418, completed.status)
        assertEquals(mapOf("handled" to "kaput"), completed.payload)
    }

    @Test
    fun `clears currentRequest after dispatch`() {
        registerRoute(RouteMethod.GET, "/hello", { "world" })

        dispatcher.dispatch(DispatchRequest("GET", "/hello"))

        assertEquals(null, GlobalRouteRegistry.currentRequest.get())
    }

    @Test
    fun `serializePayload infers json for non-string payloads`() {
        val (ct, bytes) = dispatcher.serializePayload(mapOf("a" to 1))
        assertEquals("application/json", ct)
        assertTrue(String(bytes).contains("\"a\":1"))
    }

    @Test
    fun `serializePayload keeps plain strings as text`() {
        val (ct, bytes) = dispatcher.serializePayload("hola")
        assertEquals("text/plain; charset=UTF-8", ct)
        assertEquals("hola", String(bytes))
    }
}
