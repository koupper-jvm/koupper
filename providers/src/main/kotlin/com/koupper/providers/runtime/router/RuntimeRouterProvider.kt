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
import com.koupper.shared.runtime.WebResponse
import com.koupper.shared.runtime.TemplateResponse
import com.koupper.shared.runtime.MultipartForm
import com.koupper.shared.runtime.CorsConfig
import com.koupper.providers.templates.TemplateProvider
import com.koupper.shared.validators.core.Schema
import org.glassfish.grizzly.http.server.HttpHandler
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.Request
import org.glassfish.grizzly.http.server.Response
import java.io.File
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

class SseEmitter : StreamResponse {
    private var dataCallback: ((String) -> Unit)? = null
    private var closeCallback: (() -> Unit)? = null

    override fun onData(callback: (String) -> Unit) {
        this.dataCallback = callback
    }

    override fun onClose(callback: () -> Unit) {
        this.closeCallback = callback
    }

    fun emit(data: String) {
        dataCallback?.invoke(data)
    }

    fun complete() {
        closeCallback?.invoke()
    }
}

data class RequestContext(
    val method: String, 
    val path: String, 
    val body: String, 
    val headers: Map<String, List<String>>,
    val queryParams: Map<String, List<String>>,
    val attributes: MutableMap<String, Any> = mutableMapOf()
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

    inline fun <reified I> put(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PUT, I::class.java, block)
    }

    inline fun <reified I> patch(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PATCH, I::class.java, block)
    }

    inline fun <reified I> delete(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.DELETE, I::class.java, block)
    }

    // ── Global router config (v7.2) ───────────────────────────────────────

    fun cors(block: CorsConfig.() -> Unit) {
        GlobalRouteRegistry.corsConfig = CorsConfig().apply(block)
    }

    fun exceptionHandler(block: (Throwable) -> WebResponse) {
        GlobalRouteRegistry.exceptionHandler = block
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
            inputType = if (inputClass != Any::class.java) inputClass else builder.inputType,
            validationSchema = builder.validationSchema
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
    var validationSchema: Schema<I>? = null

    fun path(block: () -> String) { path = block() }
    fun middlewares(block: () -> List<String>) { middlewares = block() }
    fun schema(block: () -> Schema<I>) { validationSchema = block() }
    fun cors(block: CorsConfig.() -> Unit) { GlobalRouteRegistry.corsConfig = CorsConfig().apply(block) }
    fun exceptionHandler(block: (Throwable) -> WebResponse) { GlobalRouteRegistry.exceptionHandler = block }
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
        val discoveredRoutes = mutableListOf<String>()
        val container = app as com.koupper.container.KoupperContainer
        
        try {
            val classes = container.getClasses(Thread.currentThread().contextClassLoader, packageName)
            classes?.forEach { kClass ->
                processClass(kClass.java.name, discoveredRoutes)
            }
        } catch (e: Exception) {
            // Fallback to manual scanning if getClasses fails (e.g. in some environments)
            val path = packageName.replace('.', '/')
            val resources = Thread.currentThread().contextClassLoader.getResources(path)
            while (resources.hasMoreElements()) {
                val resource = resources.nextElement()
                if (resource.protocol == "file") {
                    scanDirectory(File(resource.toURI()), packageName, discoveredRoutes)
                }
            }
        }

        return RuntimeServerInfo("0.0.0.0", 3000, discoveredRoutes)
    }

    private fun scanDirectory(directory: File, packageName: String, discoveredRoutes: MutableList<String>) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, packageName + "." + file.name, discoveredRoutes)
            } else if (file.name.endsWith(".class")) {
                val className = packageName + "." + file.name.removeSuffix(".class")
                processClass(className, discoveredRoutes)
            }
        }
    }

    private fun scanJar(resource: java.net.URL, path: String, discoveredRoutes: MutableList<String>) {
        val connection = resource.openConnection() as java.net.JarURLConnection
        val jarFile = connection.jarFile
        val entries = jarFile.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (name.startsWith(path) && name.endsWith(".class") && !name.contains("$")) {
                val className = name.replace('/', '.').removeSuffix(".class")
                processClass(className, discoveredRoutes)
            }
        }
    }

    private fun processClass(className: String, discoveredRoutes: MutableList<String>) {
        try {
            val clazz = Class.forName(className)
            
            // Check fields (properties)
            clazz.declaredFields.forEach { field ->
                val webRoute = field.getAnnotation(WebRoute::class.java)
                val export = field.getAnnotation(Export::class.java)
                if (webRoute != null && export != null) {
                    field.isAccessible = true
                    val handler = field.get(null)
                    if (handler != null) {
                        val isAuthRequired = field.isAnnotationPresent(Auth::class.java) || clazz.isAnnotationPresent(Auth::class.java)
                        registerDiscoveredRoute(webRoute, handler, discoveredRoutes, isAuthRequired)
                    }
                }
            }

            // Check methods (including getters)
            clazz.declaredMethods.forEach { method ->
                val webRoute = method.getAnnotation(WebRoute::class.java)
                val export = method.getAnnotation(Export::class.java)
                if (webRoute != null && export != null) {
                    method.isAccessible = true
                    if (method.parameterCount == 0) { // Likely a getter for a top-level property
                        val handler = method.invoke(null)
                        if (handler != null) {
                            val isAuthRequired = method.isAnnotationPresent(Auth::class.java) || clazz.isAnnotationPresent(Auth::class.java)
                            registerDiscoveredRoute(webRoute, handler, discoveredRoutes, isAuthRequired)
                        }
                    }
                }
            }
        } catch (e: Exception) { }
    }

    private fun registerDiscoveredRoute(webRoute: WebRoute, handler: Any, discoveredRoutes: MutableList<String>, isAuthRequired: Boolean = false) {
        val method = when (webRoute.method) {
            com.koupper.shared.annotations.RouteMethod.GET -> com.koupper.shared.runtime.RouteMethod.GET
            com.koupper.shared.annotations.RouteMethod.POST -> com.koupper.shared.runtime.RouteMethod.POST
            com.koupper.shared.annotations.RouteMethod.PUT -> com.koupper.shared.runtime.RouteMethod.PUT
            com.koupper.shared.annotations.RouteMethod.DELETE -> com.koupper.shared.runtime.RouteMethod.DELETE
        }

        // Detect input type from the handler's invoke method
        val inputType = handler.javaClass.methods
            .filter { it.name == "invoke" && !it.isBridge && it.parameterCount == 1 }
            .firstOrNull()?.genericParameterTypes?.firstOrNull()

        val middlewares = mutableListOf<String>()
        if (isAuthRequired) {
            middlewares.add("auth") // Match the name used in Setup.kt templates
        }

        val route = RegisteredRuntimeRoute(
            method = method,
            fullPath = webRoute.path,
            middlewares = middlewares,
            handler = handler,
            inputType = inputType
        )
        GlobalRouteRegistry.routes.add(route)
        discoveredRoutes.add("${route.method} ${route.fullPath}${if (isAuthRequired) " [AUTH]" else ""}")
    }

    override fun start(port: Int, host: String): RuntimeServerInfo {
        stop()
        val httpServer = HttpServer.createSimpleServer(null, host, port)
        httpServer.serverConfiguration.addHttpHandler(object : HttpHandler() {
            override fun service(request: Request, response: Response) {
                val cors = GlobalRouteRegistry.corsConfig
                response.setHeader("Access-Control-Allow-Origin", cors?.allowedOrigins?.joinToString(",") ?: "*")
                response.setHeader("Access-Control-Allow-Methods", cors?.allowedMethods?.joinToString(",") ?: "GET, POST, PUT, DELETE, OPTIONS, PATCH")
                response.setHeader("Access-Control-Allow-Headers", cors?.allowedHeaders?.joinToString(",") ?: "Content-Type, Authorization")
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
        val headersMap = mutableMapOf<String, List<String>>()
        request.headerNames.forEach { name ->
            headersMap[name] = request.getHeaders(name).toList()
        }
        val reqCtx = RequestContext(
            method = method,
            path = path,
            body = "",
            headers = headersMap,
            queryParams = queryParams
        )
        GlobalRouteRegistry.currentRequest.set(reqCtx)
        try {
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

                if (route.validationSchema != null && arg != null) {
                    val schema = route.validationSchema as Schema<Any>
                    val vResult = com.koupper.shared.validators.core.validate(arg, schema)
                    if (!vResult.ok) {
                        respond(response, 400, mapOf("error" to "Validation failed", "details" to vResult.errors))
                        return
                    }
                }

                invokeMethod.invoke(handler, arg)
            } else {
                invokeMethod.invoke(handler)
            }

            if (output is StreamResponse) {
                handleStream(response, output)
                return
            }

            var status = 200
            var finalOutput = output ?: mapOf("ok" to true)
            var explicitContentType: String? = null
            var explicitHeaders = mapOf<String, String>()

            if (output is WebResponse) {
                status = output.statusCode
                finalOutput = output.body
                explicitContentType = output.contentType
                explicitHeaders = output.headers
            } else if (output is TemplateResponse) {
                val templateProvider = (app as com.koupper.container.KoupperContainer).getInstance(TemplateProvider::class)
                status = output.statusCode
                explicitHeaders = output.headers
                explicitContentType = "text/html; charset=UTF-8"
                finalOutput = templateProvider.load(output.templatePath, output.context, fromFile = false)
            } else if (output != null) {
                val clazz = output.javaClass
                try {
                    val statusCodeGetter = clazz.methods.find { it.name == "getStatusCode" && it.parameterCount == 0 }
                    val bodyGetter = clazz.methods.find { it.name == "getBody" && it.parameterCount == 0 }
                    if (statusCodeGetter != null && bodyGetter != null) {
                        status = statusCodeGetter.invoke(output) as? Int ?: 200
                        finalOutput = bodyGetter.invoke(output) ?: mapOf("ok" to true)
                    }
                    val contentTypeGetter = clazz.methods.find { it.name == "getContentType" && it.parameterCount == 0 }
                    if (contentTypeGetter != null) {
                        explicitContentType = contentTypeGetter.invoke(output) as? String
                    }
                    val headersGetter = clazz.methods.find { it.name == "getHeaders" && it.parameterCount == 0 }
                    if (headersGetter != null) {
                        explicitHeaders = headersGetter.invoke(output) as? Map<String, String> ?: emptyMap()
                    }
                } catch (e: Exception) {
                    // Ignore reflection errors and treat as normal payload
                }
            }

            respond(response, status, finalOutput, explicitContentType, explicitHeaders)
        } catch (e: IllegalArgumentException) {
            respond(response, 400, mapOf("error" to "Invalid input format", "detail" to (e.message ?: "")))
        } catch (e: Throwable) {
            val root = e.cause ?: e
            val handler = GlobalRouteRegistry.exceptionHandler
            if (handler != null) {
                try {
                    val res = handler(root)
                    respond(response, res.statusCode, res.body, res.contentType, res.headers)
                } catch (e2: Throwable) {
                    respond(response, 500, mapOf("error" to "Error in exception handler", "type" to e2.javaClass.name))
                }
            } else {
                respond(response, 500, mapOf("error" to root.message, "type" to root.javaClass.name))
            }
        }
        } finally {
            GlobalRouteRegistry.currentRequest.remove()
        }
    }

    private fun buildArgument(request: Request, route: RegisteredRuntimeRoute): Any? {
        val inputType = route.inputType ?: return null
        val httpMethod = request.method.methodString.uppercase()

        if (inputType == MultipartForm::class.java) {
            val contentType = request.contentType ?: ""
            if (contentType.startsWith("multipart/form-data")) {
                return parseMultipart(contentType, readRequestBodyBytes(request))
            }
            return MultipartForm()
        }

        if (inputType == String::class.java) {
            if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
                return String(readRequestBodyBytes(request), Charsets.UTF_8)
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
            if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
                val bodyBytes = readRequestBodyBytes(request)
                if (bodyBytes.isNotEmpty()) {
                    try {
                        return mapper.readValue(bodyBytes, inputType)
                    } catch (e: Exception) {
                        throw IllegalArgumentException("Cannot parse JSON to ${inputType.simpleName}: ${e.message}")
                    }
                }
            }
            val queryParams = parseQueryString(request.queryString ?: "").mapValues { it.value.firstOrNull() ?: "" }
            try {
                return mapper.convertValue(queryParams, inputType)
            } catch (e: Exception) {
                throw IllegalArgumentException("Cannot map params to ${inputType.simpleName}: ${e.message}")
            }
        }

        return null
    }

    private fun readRequestBodyBytes(request: Request): ByteArray {
        return try {
            val length = request.contentLength
            if (length > 0) {
                val buf = ByteArray(length)
                var read = 0
                while (read < length) {
                    val r = request.inputStream.read(buf, read, length - read)
                    if (r == -1) break
                    read += r
                }
                buf
            } else if (request.getHeader("Transfer-Encoding")?.contains("chunked") == true) {
                request.inputStream.readBytes()
            } else {
                ByteArray(0)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ByteArray(0)
        }
    }

    private fun parseMultipart(contentType: String, body: ByteArray): MultipartForm {
        val boundary = "boundary=(.+)".toRegex().find(contentType)?.groupValues?.get(1) ?: return MultipartForm()
        val boundaryBytes = "--$boundary".toByteArray()
        val fields = mutableMapOf<String, String>()
        val files = mutableMapOf<String, com.koupper.shared.runtime.UploadedFile>()
        
        var pos = 0
        while (pos < body.size) {
            val nextBoundary = indexOf(body, boundaryBytes, pos)
            if (nextBoundary == -1) break
            if (pos > 0) {
                val partEnd = nextBoundary - 2
                if (partEnd > pos) {
                    val part = body.copyOfRange(pos, partEnd)
                    val headerEnd = indexOf(part, "\r\n\r\n".toByteArray(), 0)
                    if (headerEnd != -1) {
                        val headersStr = String(part, 0, headerEnd, Charsets.UTF_8)
                        val content = part.copyOfRange(headerEnd + 4, part.size)
                        
                        val nameMatch = "name=\"([^\"]+)\"".toRegex().find(headersStr)
                        val filenameMatch = "filename=\"([^\"]+)\"".toRegex().find(headersStr)
                        val ctMatch = "Content-Type: (.+)".toRegex().find(headersStr)
                        
                        val name = nameMatch?.groupValues?.get(1)
                        if (name != null) {
                            if (filenameMatch != null) {
                                files[name] = com.koupper.shared.runtime.UploadedFile(
                                    filename = filenameMatch.groupValues[1],
                                    contentType = ctMatch?.groupValues?.get(1)?.trim() ?: "application/octet-stream",
                                    content = content
                                )
                            } else {
                                fields[name] = String(content, Charsets.UTF_8)
                            }
                        }
                    }
                }
            }
            pos = nextBoundary + boundaryBytes.size + 2
        }
        return MultipartForm(fields, files)
    }

    private fun indexOf(array: ByteArray, target: ByteArray, start: Int): Int {
        if (target.isEmpty()) return 0
        outer@ for (i in start..array.size - target.size) {
            for (j in target.indices) {
                if (array[i + j] != target[j]) continue@outer
            }
            return i
        }
        return -1
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

    private fun respond(response: Response, status: Int, payload: Any, explicitContentType: String? = null, headers: Map<String, String> = emptyMap()) {
        response.status = status
        headers.forEach { (k, v) -> response.setHeader(k, v) }
        
        val (ct, bytes) = when {
            explicitContentType != null -> 
                explicitContentType to (if (payload is String) payload.toByteArray(Charsets.UTF_8) else mapper.writeValueAsBytes(payload))
            payload is String && payload.trimStart().let { it.startsWith("<!DOCTYPE") || it.startsWith("<html") } ->
                "text/html; charset=UTF-8" to payload.toByteArray(Charsets.UTF_8)
            payload is String && (payload.startsWith("{") || payload.startsWith("[")) ->
                "application/json" to payload.toByteArray(Charsets.UTF_8)
            payload is String ->
                "text/plain; charset=UTF-8" to payload.toByteArray(Charsets.UTF_8)
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
