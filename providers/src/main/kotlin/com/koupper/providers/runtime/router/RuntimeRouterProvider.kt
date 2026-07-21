package com.koupper.providers.runtime.router

import com.koupper.container.app
import com.koupper.shared.annotations.Auth
import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.WebRoute
import com.koupper.shared.annotations.RouteMethod
import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.RegisteredRuntimeRoute
import com.koupper.shared.runtime.WebResponse
import com.koupper.shared.runtime.CorsConfig
import com.koupper.shared.runtime.resolveCorsAllowOrigin
import com.koupper.shared.validators.core.Schema
import org.glassfish.grizzly.http.server.HttpHandler
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.Request
import org.glassfish.grizzly.http.server.Response
import java.io.File
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
    // handleStream registers callbacks AFTER the route handler returns the emitter,
    // so anything emitted in that gap must be buffered and replayed on registration.
    private val pendingData = mutableListOf<String>()
    private var completed = false

    @Synchronized
    override fun onData(callback: (String) -> Unit) {
        this.dataCallback = callback
        pendingData.forEach { callback(it) }
        pendingData.clear()
    }

    @Synchronized
    override fun onClose(callback: () -> Unit) {
        this.closeCallback = callback
        if (completed) callback()
    }

    @Synchronized
    fun emit(data: String) {
        val callback = dataCallback
        if (callback != null) callback(data) else pendingData.add(data)
    }

    @Synchronized
    fun complete() {
        completed = true
        closeCallback?.invoke()
    }
}

data class RequestContext(
    val method: String,
    val path: String,
    val body: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val queryParams: Map<String, List<String>> = emptyMap(),
    val pathParams: Map<String, String> = emptyMap(),
    val attributes: MutableMap<String, Any> = mutableMapOf()
) {
    fun queryParam(name: String): String? = try {
        queryParams[name]?.firstOrNull()
    } catch (_: NullPointerException) {
        null
    }
}

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
        registerWithType(com.koupper.shared.runtime.RouteMethod.GET, I::class.java, block = block)
    }

    inline fun <reified I> post(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.POST, I::class.java, block = block)
    }

    inline fun <reified I> put(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PUT, I::class.java, block = block)
    }

    inline fun <reified I> patch(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PATCH, I::class.java, block = block)
    }

    inline fun <reified I> delete(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.DELETE, I::class.java, block = block)
    }

    @JvmName("getIO")
    inline fun <reified I, reified O> get(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.GET, I::class.java, O::class.java, block)
    }

    @JvmName("postIO")
    inline fun <reified I, reified O> post(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.POST, I::class.java, O::class.java, block)
    }

    @JvmName("putIO")
    inline fun <reified I, reified O> put(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PUT, I::class.java, O::class.java, block)
    }

    @JvmName("patchIO")
    inline fun <reified I, reified O> patch(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PATCH, I::class.java, O::class.java, block)
    }

    @JvmName("deleteIO")
    inline fun <reified I, reified O> delete(noinline block: RouteBuilder<I>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.DELETE, I::class.java, O::class.java, block)
    }

    @JvmName("getAny")
    fun get(block: RouteBuilder<Any>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.GET, Any::class.java, block = block)
    }

    @JvmName("postAny")
    fun post(block: RouteBuilder<Any>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.POST, Any::class.java, block = block)
    }

    @JvmName("putAny")
    fun put(block: RouteBuilder<Any>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PUT, Any::class.java, block = block)
    }

    @JvmName("patchAny")
    fun patch(block: RouteBuilder<Any>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.PATCH, Any::class.java, block = block)
    }

    @JvmName("deleteAny")
    fun delete(block: RouteBuilder<Any>.() -> Unit) {
        registerWithType(com.koupper.shared.runtime.RouteMethod.DELETE, Any::class.java, block = block)
    }

    // ── Global router config (v7.2) ───────────────────────────────────────

    fun cors(block: CorsConfig.() -> Unit) {
        GlobalRouteRegistry.corsConfig = CorsConfig().apply(block)
    }

    fun exceptionHandler(block: (Throwable) -> WebResponse) {
        GlobalRouteRegistry.exceptionHandler = block
    }

    @PublishedApi
    internal fun <I> registerWithType(method: com.koupper.shared.runtime.RouteMethod, inputClass: Class<*>, outputClass: Class<*>? = null, block: RouteBuilder<I>.() -> Unit) {
        val builder = RouteBuilder<I>()
        builder.block()
        val fullPath = (currentPathPrefix + builder.path).replace("//", "/")
        routes.add(RegisteredRuntimeRoute(
            method = method,
            fullPath = fullPath,
            middlewares = builder.middlewares,
            handler = builder.handler ?: throw IllegalStateException("Handler not defined for $fullPath"),
            inputType = if (inputClass != Any::class.java) inputClass else builder.inputType,
            outputType = outputClass,
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
    private val dispatcher = RouteDispatcher()

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
                val allowOrigin = resolveCorsAllowOrigin(cors?.allowedOrigins, request.getHeader("Origin"))
                if (allowOrigin != null) {
                    response.setHeader("Access-Control-Allow-Origin", allowOrigin)
                    if (allowOrigin != "*") {
                        response.setHeader("Vary", "Origin")
                    }
                }
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
        if (serveStatic(request, response)) return

        val headersMap = mutableMapOf<String, List<String>>()
        request.headerNames.forEach { name ->
            headersMap[name] = request.getHeaders(name).toList()
        }

        val outcome = dispatcher.dispatch(DispatchRequest(
            method = request.method.methodString,
            path = request.requestURI,
            headers = headersMap,
            queryString = request.queryString,
            contentType = request.contentType,
            bodyProvider = { readRequestBodyBytes(request) }
        ))

        when (outcome) {
            is DispatchOutcome.Stream -> handleStream(response, outcome.stream)
            is DispatchOutcome.Completed -> respond(response, outcome.status, outcome.payload, outcome.contentType, outcome.headers)
        }
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
        val (ct, bytes) = dispatcher.serializePayload(payload, explicitContentType)
        response.setContentType(ct)
        response.outputStream.write(bytes)
    }

    override fun stop() {
        val server = System.getProperties()["koupper.runtime.server"] as? HttpServer
        server?.shutdownNow()
    }
}
