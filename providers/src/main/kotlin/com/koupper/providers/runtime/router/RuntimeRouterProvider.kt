package com.koupper.providers.runtime.router

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.container.app
import com.koupper.shared.annotations.Auth
import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.WebRoute
import com.koupper.shared.annotations.RouteMethod
import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.RegisteredRuntimeRoute
import org.glassfish.grizzly.http.server.HttpHandler
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.Request
import org.glassfish.grizzly.http.server.Response
import java.net.JarURLConnection
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class MiddlewareResult(val allowed: Boolean, val statusCode: Int = 401, val message: String = "Unauthorized")

// DEFINICIÓN DE STREAMING
interface StreamResponse {
    fun onData(callback: (String) -> Unit)
    fun onClose(callback: () -> Unit)
}

data class RequestContext(
    val method: String, 
    val path: String, 
    val body: String, 
    val headers: Map<String, List<String>>,
    val queryParams: Map<String, List<String>>
)

interface RuntimeRouterProvider {
    fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult)
    fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo
    fun autoDiscover(packageName: String): RuntimeServerInfo
    fun start(port: Int = 8080, host: String = "0.0.0.0"): RuntimeServerInfo
    fun stop()
}

data class StaticMapping(val prefix: String, val dir: java.io.File)

class RuntimeRouterDsl {
    private val routes = mutableListOf<RegisteredRuntimeRoute>()
    private val statics = mutableListOf<StaticMapping>()
    private var currentPathPrefix = ""

    fun path(prefix: () -> String) {
        currentPathPrefix += prefix()
    }

    fun staticFiles(prefix: String, dir: String) {
        statics.add(StaticMapping(prefix.trimEnd('/'), java.io.File(dir)))
    }

    inline fun <reified I> get(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.GET, I::class.java, block)
    }

    inline fun <reified I> post(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.POST, I::class.java, block)
    }

    @PublishedApi
    internal fun <I> registerWithType(method: com.koupper.shared.runtime.RouteMethod, inputClass: Class<*>, block: RouteBuilder<I>.() -> Unit) {
        val builder = RouteBuilder<I>()
        builder.block()
        val fullPath = (currentPathPrefix + builder.path).replace("//", "/")
        routes.add(RegisteredRuntimeRoute(
            method = method,
            fullPath = fullPath,
            middlewares = builder.middlewares,
            handler = builder.handler ?: throw IllegalStateException("Handler not defined for $fullPath"),
            inputType = if (inputClass != Any::class.java) inputClass else builder.inputType
        ))
    }

    internal fun build(): List<RegisteredRuntimeRoute> = routes.toList()
    internal fun buildStatics(): List<StaticMapping> = statics.toList()
}

class RouteBuilder<I> {
    var path: String = ""
    var middlewares: List<String> = emptyList()
    var handler: Any? = null
    var inputType: java.lang.reflect.Type? = null

    fun path(block: () -> String) { path = block() }
    fun middlewares(block: () -> List<String>) { middlewares = block() }
    fun script(block: () -> Any) { 
        handler = block()
        inputType = handler?.javaClass?.methods
            ?.filter { it.name == "invoke" && !it.isBridge && it.parameterCount == 1 }
            ?.firstOrNull()?.genericParameterTypes?.firstOrNull()
    }
}

data class RuntimeServerInfo(val host: String, val port: Int, val routes: List<String>)

class GrizzlyRuntimeRouterProvider : RuntimeRouterProvider {
    private val mapper = jacksonObjectMapper()

    companion object {
        val staticMappings = CopyOnWriteArrayList<StaticMapping>()

        private fun mimeFor(name: String) = when (name.substringAfterLast('.').lowercase()) {
            "html"        -> "text/html; charset=UTF-8"
            "js", "mjs"   -> "application/javascript"
            "css"         -> "text/css"
            "png"         -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif"         -> "image/gif"
            "svg"         -> "image/svg+xml"
            "ico"         -> "image/x-icon"
            "mp3"         -> "audio/mpeg"
            "wav"         -> "audio/wav"
            "json"        -> "application/json"
            "woff"        -> "font/woff"
            "woff2"       -> "font/woff2"
            "ttf"         -> "font/ttf"
            "map"         -> "application/json"
            else          -> "application/octet-stream"
        }
    }

    override fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult) {
        GlobalRouteRegistry.middlewares[name] = { ctx -> middleware(ctx as RequestContext) }
    }

    override fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo {
        val dsl = RuntimeRouterDsl()
        dsl.block()
        val newRoutes = dsl.build()
        GlobalRouteRegistry.routes.addAll(newRoutes)
        staticMappings.addAll(dsl.buildStatics())
        return RuntimeServerInfo("0.0.0.0", 8080, newRoutes.map { "${it.method} ${it.fullPath}" })
    }

    override fun autoDiscover(packageName: String): RuntimeServerInfo {
        return RuntimeServerInfo("0.0.0.0", 3000, emptyList())
    }

    override fun start(port: Int, host: String): RuntimeServerInfo {
        stop()
        val httpServer = HttpServer.createSimpleServer(null, host, port)
        httpServer.serverConfiguration.addHttpHandler(object : HttpHandler() {
            override fun service(request: Request, response: Response) {
                response.setHeader("Access-Control-Allow-Origin", "*")
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
                response.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization")
                if (request.method.methodString == "OPTIONS") { response.status = 204; return }
                handleInternal(request, response)
            }
        }, "/")
        httpServer.start()
        System.getProperties()["koupper.runtime.server"] = httpServer
        return RuntimeServerInfo(host, port, GlobalRouteRegistry.routes.map { it.fullPath })
    }

    private fun serveStatic(request: Request, response: Response): Boolean {
        if (request.method.methodString.uppercase() != "GET") return false
        val path = request.requestURI
        val mapping = staticMappings.firstOrNull { path.startsWith(it.prefix + "/") || path == it.prefix } ?: return false
        val relative = path.removePrefix(mapping.prefix).trimStart('/')
        val file = java.io.File(mapping.dir, relative).canonicalFile
        if (!file.absolutePath.startsWith(mapping.dir.canonicalPath)) return false  // path traversal guard
        if (!file.exists() || !file.isFile) return false
        response.status = 200
        response.setContentType(mimeFor(file.name))
        response.outputStream.write(file.readBytes())
        return true
    }

    private fun handleInternal(request: Request, response: Response) {
        val method = request.method.methodString.uppercase()
        val path = request.requestURI

        if (serveStatic(request, response)) return

        val routes = GlobalRouteRegistry.routes
        val route = routes.firstOrNull { it.method.name == method && matches(it.fullPath, path) }

        if (route == null) {
            respond(response, 404, mapOf("error" to "Route not found", "path" to path, "registered" to routes.map { "${it.method} ${it.fullPath}" }))
            return
        }

        val queryParams = parseQueryString(request.queryString ?: "")
        val reqCtx = RequestContext(
            method = method,
            path = path,
            body = "",
            headers = emptyMap(),
            queryParams = queryParams
        )
        for (middlewareName in route.middlewares) {
            val middleware = GlobalRouteRegistry.middlewares[middlewareName] ?: continue
            val result = middleware(reqCtx) as? MiddlewareResult ?: continue
            if (!result.allowed) {
                respond(response, result.statusCode, mapOf("error" to result.message))
                return
            }
        }

        try {
            val handler = route.handler
            val invokeMethod = handler.javaClass.methods
                .filter { it.name == "invoke" && !it.isBridge }
                .firstOrNull() ?: throw IllegalStateException("No invoke found")

            invokeMethod.isAccessible = true

            val output = if (invokeMethod.parameterCount == 1) {
                val arg = buildArgument(request, route)
                invokeMethod.invoke(handler, arg)
            } else {
                invokeMethod.invoke(handler)
            }

            if (output is StreamResponse) {
                handleStream(response, output)
                return
            }

            respond(response, 200, output ?: mapOf("ok" to true))
        } catch (e: IllegalArgumentException) {
            respond(response, 400, mapOf("error" to "Invalid input format", "detail" to (e.message ?: "")))
        } catch (e: Throwable) {
            val root = e.cause ?: e
            respond(response, 500, mapOf("error" to root.message, "type" to root.javaClass.name))
        }
    }

    private fun buildArgument(request: Request, route: RegisteredRuntimeRoute): Any? {
        val inputType = route.inputType ?: return null
        val httpMethod = request.method.methodString.uppercase()

        if (inputType == String::class.java) {
            if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
                return readRequestBody(request)
            }
            val pathParams = extractPathParams(route.fullPath, request.requestURI)
            return pathParams.values.firstOrNull()
                ?: URLDecoder.decode(request.requestURI.trim('/').split("/").lastOrNull() ?: "", "UTF-8")
        }

        if (inputType is java.lang.reflect.ParameterizedType) {
            val rawType = inputType.rawType
            if (rawType is Class<*> && Collection::class.java.isAssignableFrom(rawType)) {
                val queryParams = parseQueryString(request.queryString ?: "")
                return queryParams.values.flatten()
            }
        }

        if (inputType is Class<*>) {
            val queryParams = parseQueryString(request.queryString ?: "").mapValues { it.value.firstOrNull() ?: "" }
            try {
                return mapper.convertValue(queryParams, inputType)
            } catch (e: Exception) {
                throw IllegalArgumentException("Cannot map params to ${inputType.simpleName}: ${e.message}")
            }
        }

        return null
    }

    private fun readRequestBody(request: Request): String {
        val contentLength = request.contentLengthLong.toInt()
        if (contentLength <= 0) return ""
        val inputBuffer = request.inputBuffer
        try {
            inputBuffer.fillFully(contentLength)
        } catch (e: Exception) {
            return ""
        }
        val buf = ByteArray(contentLength)
        val n = inputBuffer.read(buf, 0, contentLength)
        return if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
    }

    private fun extractPathParams(routePath: String, requestPath: String): Map<String, String> {
        val routeParts = routePath.trim('/').split("/")
        val requestParts = requestPath.trim('/').split("/")
        val params = mutableMapOf<String, String>()
        routeParts.forEachIndexed { i, part ->
            if (part.startsWith("{") && part.endsWith("}") && i < requestParts.size) {
                params[part.substring(1, part.length - 1)] = URLDecoder.decode(requestParts[i], "UTF-8")
            }
        }
        return params
    }

    private fun parseQueryString(queryString: String): Map<String, List<String>> {
        if (queryString.isBlank()) return emptyMap()
        val result = mutableMapOf<String, MutableList<String>>()
        queryString.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                result.getOrPut(key) { mutableListOf() }.add(value)
            }
        }
        return result
    }

    private fun matches(routePath: String, requestPath: String): Boolean {
        val cleanRoute = routePath.trim('/')
        val cleanRequest = requestPath.trim('/')
        if (cleanRoute == cleanRequest) return true
        val regex = "^" + cleanRoute.replace(Regex("\\{[^/]+\\}"), "[^/]+") + "$"
        return cleanRequest.matches(Regex(regex))
    }

    private fun handleStream(response: Response, stream: StreamResponse) {
        response.status = 200
        response.setContentType("text/event-stream")
        response.setHeader("Cache-Control", "no-cache")
        response.setHeader("Connection", "keep-alive")
        response.suspend()
        val writer = response.outputStream
        stream.onData { data -> try { writer.write("data: $data\n\n".toByteArray()); response.flush() } catch (e: Exception) { } }
        stream.onClose { try { response.resume() } catch (e: Exception) {} }
    }

    private fun respond(response: Response, status: Int, payload: Any) {
        response.status = status
        val (ct, bytes) = when {
            payload is String && payload.trimStart().let { it.startsWith("<!DOCTYPE") || it.startsWith("<html") } ->
                "text/html; charset=UTF-8" to payload.toByteArray(Charsets.UTF_8)
            payload is String && (payload.startsWith("{") || payload.startsWith("[")) ->
                "application/json" to payload.toByteArray(Charsets.UTF_8)
            else ->
                "application/json" to mapper.writeValueAsBytes(payload)
        }
        response.setContentType(ct)
        response.outputStream.write(bytes)
    }

    override fun stop() {
        val server = System.getProperties()["koupper.runtime.server"] as? HttpServer
        server?.shutdownNow()
    }
}
