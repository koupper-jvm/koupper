package com.koupper.providers.runtime.router

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

enum class RouteMethod {
    GET,
    POST,
    PUT,
    DELETE
}

data class MiddlewareResult(
    val allowed: Boolean,
    val statusCode: Int = 401,
    val message: String = "Unauthorized"
)

data class RequestContext(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, List<String>>
)

@PublishedApi
internal data class RegisteredRuntimeRoute(
    val method: RouteMethod,
    val fullPath: String,
    val middlewares: List<String>,
    val inputType: KClass<*>,
    val handler: (Any?) -> Any?
)

class RuntimeRouteBuilder<I : Any, O : Any>(
    private val method: RouteMethod,
    private val basePath: String,
    private val inputType: KClass<I>
) {
    private var subPath: String = "/"
    private var middlewareNames: List<String> = emptyList()
    private var scriptHandler: ((I) -> O)? = null

    fun path(block: () -> String) {
        subPath = block().trim()
    }

    fun middlewares(block: () -> List<String>) {
        middlewareNames = block()
    }

    fun script(block: () -> (I) -> O) {
        scriptHandler = block()
    }

    @PublishedApi
    internal fun build(): RegisteredRuntimeRoute {
        val fullPath = normalizePath(basePath, subPath)
        val handler = scriptHandler ?: error("script handler is required for $method $fullPath")
        return RegisteredRuntimeRoute(
            method = method,
            fullPath = fullPath,
            middlewares = middlewareNames,
            inputType = inputType,
            handler = { input -> handler(input as I) }
        )
    }
}

class RuntimeRouterDsl {
    private val routes = mutableListOf<RegisteredRuntimeRoute>()
    private var basePath: String = "/"

    fun path(block: () -> String) {
        basePath = block().trim()
    }

    fun <I : Any, O : Any> post(inputType: KClass<I>, block: RuntimeRouteBuilder<I, O>.() -> Unit) {
        routes += RuntimeRouteBuilder<I, O>(RouteMethod.POST, basePath, inputType).apply(block).build()
    }

    fun <I : Any, O : Any> put(inputType: KClass<I>, block: RuntimeRouteBuilder<I, O>.() -> Unit) {
        routes += RuntimeRouteBuilder<I, O>(RouteMethod.PUT, basePath, inputType).apply(block).build()
    }

    fun <O : Any> get(block: RuntimeRouteBuilder<Unit, O>.() -> Unit) {
        routes += RuntimeRouteBuilder<Unit, O>(RouteMethod.GET, basePath, Unit::class).apply(block).build()
    }

    fun <O : Any> delete(block: RuntimeRouteBuilder<Unit, O>.() -> Unit) {
        routes += RuntimeRouteBuilder<Unit, O>(RouteMethod.DELETE, basePath, Unit::class).apply(block).build()
    }

    internal fun build(): List<RegisteredRuntimeRoute> = routes.toList()
}

data class RuntimeServerInfo(
    val host: String,
    val port: Int,
    val routes: List<String>
)

interface RuntimeRouterProvider {
    fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult)
    fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo
    fun start(port: Int = 8080, host: String = "127.0.0.1"): RuntimeServerInfo
    fun stop()
}

class JdkRuntimeRouterProvider : RuntimeRouterProvider {
    private val mapper = jacksonObjectMapper()
    private val routes = CopyOnWriteArrayList<RegisteredRuntimeRoute>()
    private val middlewares = ConcurrentHashMap<String, (RequestContext) -> MiddlewareResult>()
    private var server: HttpServer? = null
    private var host: String = "127.0.0.1"
    private var port: Int = 8080

    override fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult) {
        middlewares[name] = middleware
    }

    override fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo {
        routes += RuntimeRouterDsl().apply(block).build()
        return RuntimeServerInfo(host = host, port = port, routes = routes.map { "${it.method} ${it.fullPath}" })
    }

    override fun start(port: Int, host: String): RuntimeServerInfo {
        stop()
        this.host = host
        this.port = port

        val runningServer = HttpServer.create(InetSocketAddress(host, port), 0)
        runningServer.createContext("/") { exchange ->
            handleExchange(exchange)
        }
        runningServer.start()
        server = runningServer

        return RuntimeServerInfo(host = host, port = port, routes = routes.map { "${it.method} ${it.fullPath}" })
    }

    override fun stop() {
        server?.stop(0)
        server = null
    }

    private fun handleExchange(exchange: HttpExchange) {
        val method = exchange.requestMethod.uppercase()
        val path = exchange.requestURI.path
        val route = routes.firstOrNull { it.method.name == method && it.fullPath == path }
        if (route == null) {
            respond(exchange, 404, mapOf("error" to "Route not found", "path" to path, "method" to method))
            return
        }

        val body = exchange.requestBody.bufferedReader().readText()
        val context = RequestContext(
            method = method,
            path = path,
            body = body,
            headers = exchange.requestHeaders.toMap()
        )

        for (name in route.middlewares) {
            val middleware = middlewares[name]
                ?: run {
                    respond(exchange, 500, mapOf("error" to "Middleware '$name' is not registered"))
                    return
                }
            val decision = middleware(context)
            if (!decision.allowed) {
                respond(exchange, decision.statusCode, mapOf("error" to decision.message, "middleware" to name))
                return
            }
        }

        try {
            val input = when (route.inputType) {
                Unit::class -> Unit
                String::class -> body
                else -> mapper.readValue(body, route.inputType.java)
            }
            val output = route.handler(input)
            respond(exchange, 200, output ?: mapOf("ok" to true))
        } catch (error: Throwable) {
            respond(exchange, 500, mapOf("error" to (error.message ?: "Unknown runtime route error")))
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, payload: Any) {
        val response = mapper.writeValueAsBytes(payload)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, response.size.toLong())
        exchange.responseBody.use { it.write(response) }
    }
}

private fun normalizePath(basePath: String, subPath: String): String {
    val base = if (basePath.startsWith('/')) basePath else "/$basePath"
    val sub = if (subPath.startsWith('/')) subPath else "/$subPath"
    val raw = (base.trimEnd('/') + "/" + sub.trimStart('/')).replace("//", "/")
    return if (raw.isBlank()) "/" else raw
}
