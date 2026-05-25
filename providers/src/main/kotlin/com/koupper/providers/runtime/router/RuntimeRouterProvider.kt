package com.koupper.providers.runtime.router

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.shared.annotations.Auth
import com.koupper.shared.annotations.Export
import com.koupper.shared.annotations.WebRoute
import com.koupper.shared.annotations.RouteMethod
import org.glassfish.grizzly.http.server.HttpHandler
import org.glassfish.grizzly.http.server.HttpServer
import org.glassfish.grizzly.http.server.Request
import org.glassfish.grizzly.http.server.Response
import java.net.JarURLConnection
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class MiddlewareResult(val allowed: Boolean, val statusCode: Int = 401, val message: String = "Unauthorized")

// DEFINICIÓN CORRECTA DE REQUEST CONTEXT
data class RequestContext(
    val method: String, 
    val path: String, 
    val body: String, 
    val headers: Map<String, List<String>>,
    val queryParams: Map<String, List<String>>
)

data class RegisteredRuntimeRoute(
    val method: RouteMethod,
    val fullPath: String,
    val middlewares: List<String>,
    val handler: Any,
    val inputType: java.lang.reflect.Type?
)

interface RuntimeRouterProvider {
    fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult)
    fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo
    fun autoDiscover(packageName: String): RuntimeServerInfo
    fun start(port: Int = 8080, host: String = "0.0.0.0"): RuntimeServerInfo
    fun stop()
}

class RuntimeRouterDsl {
    private val routes = mutableListOf<RegisteredRuntimeRoute>()
    private var currentPathPrefix = ""

    fun path(prefix: () -> String) {
        currentPathPrefix += prefix()
    }

    fun <I> get(block: RouteBuilder<I>.() -> Unit) {
        register(RouteMethod.GET, block)
    }

    fun <I> post(block: RouteBuilder<I>.() -> Unit) {
        register(RouteMethod.POST, block)
    }

    private fun <I> register(method: RouteMethod, block: RouteBuilder<I>.() -> Unit) {
        val builder = RouteBuilder<I>()
        builder.block()
        val fullPath = (currentPathPrefix + builder.path).replace("//", "/")
        routes.add(RegisteredRuntimeRoute(
            method = method,
            fullPath = fullPath,
            middlewares = builder.middlewares,
            handler = builder.handler ?: throw IllegalStateException("Handler not defined for $fullPath"),
            inputType = builder.inputType
        ))
    }

    internal fun build(): List<RegisteredRuntimeRoute> = routes.toList()
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
        // Intentamos inferir el tipo del parámetro si es una lambda
        inputType = handler?.javaClass?.methods
            ?.filter { it.name == "invoke" && !it.isBridge && it.parameterCount == 1 }
            ?.firstOrNull()?.genericParameterTypes?.firstOrNull()
    }
}

data class RuntimeServerInfo(val host: String, val port: Int, val routes: List<String>)

class GrizzlyRuntimeRouterProvider : RuntimeRouterProvider {
    private val mapper = jacksonObjectMapper()
    private val routes = CopyOnWriteArrayList<RegisteredRuntimeRoute>()
    private val middlewares = ConcurrentHashMap<String, (RequestContext) -> MiddlewareResult>()
    private var server: HttpServer? = null

    override fun registerMiddleware(name: String, middleware: (RequestContext) -> MiddlewareResult) {
        middlewares[name] = middleware
    }

    override fun registerRouter(block: RuntimeRouterDsl.() -> Unit): RuntimeServerInfo {
        val dsl = RuntimeRouterDsl()
        dsl.block()
        val newRoutes = dsl.build()
        routes.addAll(newRoutes)
        return RuntimeServerInfo("0.0.0.0", 8080, newRoutes.map { "${it.method} ${it.fullPath}" })
    }

    override fun autoDiscover(packageName: String): RuntimeServerInfo {
        println("🔍 [Koupper] STARTING PRODUCTION AUTO-DISCOVERY in: $packageName")
        val classLoader = Thread.currentThread().contextClassLoader
        val path = packageName.replace('.', '/')
        val resources = classLoader.getResources(path)
        
        while (resources.hasMoreElements()) {
            val resource = resources.nextElement()
            
            if (resource.protocol == "file") {
                val decodedPath = URLDecoder.decode(resource.path, "UTF-8")
                val cleanPath = if (System.getProperty("os.name").lowercase().contains("win") && decodedPath.startsWith("/")) {
                    decodedPath.substring(1)
                } else decodedPath
                val directory = java.io.File(cleanPath)
                if (directory.exists()) scanDirectory(directory, packageName, classLoader)
            } 
            else if (resource.protocol == "jar") {
                println("📦 [Koupper] Detecting JAR execution. Scanning entries...")
                val connection = resource.openConnection() as JarURLConnection
                val jarFile = connection.jarFile
                val entries = jarFile.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (name.startsWith(path) && name.endsWith(".class") && !name.contains("$")) {
                        val className = name.replace('/', '.').removeSuffix(".class")
                        processClass(className, classLoader)
                    }
                }
            }
        }
        
        println("✅ [Koupper] DISCOVERY COMPLETED. Routes registered: ${routes.size}")
        return RuntimeServerInfo("0.0.0.0", 3000, routes.map { it.fullPath })
    }

    private fun scanDirectory(directory: java.io.File, packageName: String, classLoader: ClassLoader) {
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                scanDirectory(file, "$packageName.${file.name}", classLoader)
            } else if (file.name.endsWith(".class") && !file.name.contains("$")) {
                val className = packageName + "." + file.name.removeSuffix(".class")
                processClass(className, classLoader)
            }
        }
    }

    private fun processClass(className: String, classLoader: ClassLoader) {
        try {
            val clazz = classLoader.loadClass(className)
            clazz.declaredMethods.forEach { method ->
                if (method.name.endsWith("\$annotations")) {
                    val annotations = method.annotations
                    val webRouteAnn = annotations.firstOrNull { it.annotationClass.java.name.endsWith("WebRoute") }
                    val hasExport = annotations.any { it.annotationClass.java.name.endsWith("Export") }
                    
                    if (webRouteAnn != null && hasExport) {
                        val propName = method.name.removePrefix("get").removeSuffix("\$annotations")
                        val getterName = "get$propName"
                        val getter = clazz.getDeclaredMethod(getterName)
                        getter.isAccessible = true
                        val handler = getter.invoke(null)
                        
                        if (handler != null) {
                            val pathStr = webRouteAnn.annotationClass.java.getMethod("path").invoke(webRouteAnn) as String
                            val methodObj = webRouteAnn.annotationClass.java.getMethod("method").invoke(webRouteAnn)
                            val routeMethod = RouteMethod.valueOf(methodObj.toString())
                            val hasAuth = annotations.any { it.annotationClass.java.name.endsWith("Auth") }
                            
                            // 🛡️ DETECCIÓN DE TIPO ULTRA-ROBUSTA
                            val invokeMethods = handler.javaClass.methods
                                .filter { it.name == "invoke" && !it.isBridge }
                            
                            val specificInvoke = invokeMethods.filter { it.parameterCount == 1 }.firstOrNull()
                            
                            val inputType = if (specificInvoke != null) {
                                val pType = specificInvoke.parameterTypes.firstOrNull()
                                if (pType == Any::class.java || pType?.typeName == "java.lang.Object") {
                                    // 🕵️ Si es Object, buscamos la info genérica en el Getter de la propiedad
                                    val genericType = getter.genericReturnType
                                    if (genericType is java.lang.reflect.ParameterizedType && genericType.actualTypeArguments.isNotEmpty()) {
                                        // En Function1<I, O>, el primer argumento es I
                                        genericType.actualTypeArguments.first()
                                    } else {
                                        pType
                                    }
                                } else {
                                    pType
                                }
                            } else null

                            println("✨ [Koupper] Registered: $routeMethod $pathStr (Input: ${inputType?.typeName ?: "None"})")
                            routes += RegisteredRuntimeRoute(routeMethod, pathStr, if (hasAuth) listOf("auth") else emptyList(), handler, inputType)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            println("⚠️ [Koupper] Error processing class $className: ${e.message}")
        }
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
        server = httpServer
        return RuntimeServerInfo(host, port, routes.map { it.fullPath })
    }

    private fun handleInternal(request: Request, response: Response) {
        val method = request.method.methodString.uppercase()
        val path = request.requestURI
        val route = routes.firstOrNull { it.method.name == method && matches(it.fullPath, path) }
        
        if (route == null) {
            respond(response, 404, mapOf("error" to "Route not found", "path" to path))
            return
        }

        try {
            // 🛡️ LECTURA TEMPRANA DEL BODY (Solo para métodos de escritura)
            val isWriteMethod = method == "POST" || method == "PUT" || method == "PATCH"
            val body = if (isWriteMethod) {
                try {
                    val contentLength = request.contentLength
                    if (contentLength > 0) {
                        val buffer = ByteArray(contentLength)
                        var totalRead = 0
                        while (totalRead < contentLength) {
                            val read = request.inputStream.read(buffer, totalRead, contentLength - totalRead)
                            if (read == -1) break
                            totalRead += read
                        }
                        String(buffer, 0, totalRead, Charsets.UTF_8)
                    } else if (request.getHeader("Transfer-Encoding")?.contains("chunked") == true) {
                        request.inputStream.readBytes().toString(Charsets.UTF_8)
                    } else ""
                } catch (e: Exception) { 
                    println("⚠️ [Koupper] BODY READ ERROR: ${e.message}")
                    "" 
                }
            } else ""

            // 🛡️ EXTRACCIÓN DE HEADERS
            val headers = mutableMapOf<String, List<String>>()
            request.headerNames.forEach { headers[it] = request.getHeaders(it).toList() }
            
            // 🛡️ EXTRACCIÓN DE QUERY PARAMS
            val queryParams = mutableMapOf<String, List<String>>()
            request.parameterNames.forEach { name ->
                queryParams[name] = request.getParameterValues(name).toList()
            }
            
            val context = RequestContext(method, path, body, headers, queryParams)
            
            for (name in route.middlewares) {
                val decision = middlewares[name]?.invoke(context) ?: MiddlewareResult(true)
                if (!decision.allowed) {
                    respond(response, decision.statusCode, mapOf("error" to decision.message))
                    return
                }
            }

            // 🛡️ USAMOS EL INVOKE ESPECÍFICO SI EXISTE
            val invokeMethod = route.handler.javaClass.methods
                .filter { 
                    it.name == "invoke" && 
                    !it.isBridge && 
                    (route.inputType == null || it.parameterTypes.firstOrNull() == route.inputType || it.parameterTypes.firstOrNull() == Any::class.java) 
                }
                .minByOrNull { if (it.parameterTypes.firstOrNull() == Any::class.java) 1 else 0 } 

            invokeMethod?.isAccessible = true

            // 🛡️ MAPEO INTELIGENTE
            val input = try {
                mapInput(route, context)
            } catch (e: Exception) {
                println("⚠️ [Koupper] Bad Request: ${e.message}")
                respond(response, 400, mapOf("error" to "Invalid input format", "details" to e.message))
                return
            }
            
            val output = if (invokeMethod?.parameterCount == 1) {
                invokeMethod.invoke(route.handler, input)
            } else {
                invokeMethod?.invoke(route.handler)
            }
            
            val finalBody = try {
                val bodyField = output?.javaClass?.declaredFields?.firstOrNull { it.name == "body" }
                bodyField?.isAccessible = true
                bodyField?.get(output) ?: output
            } catch (e: Exception) { output }

            respond(response, 200, finalBody ?: mapOf("ok" to true))
        } catch (e: Throwable) {
            println("❌ [Koupper] Internal Error: " + e.message)
            e.printStackTrace()
            respond(response, 500, mapOf("error" to e.message))
        }
    }

    private fun mapInput(route: RegisteredRuntimeRoute, context: RequestContext): Any? {
        val pathParams = extractPathParameters(route.fullPath, context.path)
        val targetType = route.inputType ?: return null
        
        return if (route.method == RouteMethod.GET) {
            val allParams = context.queryParams.toMutableMap()
            pathParams.forEach { (k, v) -> allParams[k] = listOf(v) }

            if (allParams.isEmpty()) return null

            val isTargetSimpleString = targetType == String::class.java
            val isTargetObject = targetType == Any::class.java || targetType.typeName == "java.lang.Object"
            val isTargetList = targetType is java.lang.reflect.ParameterizedType && 
                              (targetType.rawType == List::class.java || targetType.rawType == Collection::class.java)

            when {
                isTargetSimpleString -> {
                    // 🛡️ PRIORIDAD AL PATH PARAM: Si hay params en la URL, usamos el primero de ellos
                    if (pathParams.isNotEmpty()) pathParams.values.first()
                    else allParams.values.firstOrNull()?.firstOrNull()
                }
                isTargetList -> allParams.values.firstOrNull() ?: emptyList<String>()
                isTargetObject -> {
                    val flatParams = allParams.mapValues { it.value.firstOrNull() }
                    val isPathParam = pathParams.containsKey(flatParams.keys.firstOrNull())
                    if (flatParams.size == 1 && isPathParam) flatParams.values.first()
                    else flatParams
                }
                else -> {
                    val flatParams = allParams.mapValues { it.value.firstOrNull() }
                    mapper.convertValue(flatParams, mapper.typeFactory.constructType(targetType))
                }
            }
        } else {
            val body = context.body
            if (body.isBlank()) {
                if (pathParams.isNotEmpty()) {
                    if (targetType == String::class.java && pathParams.size == 1) pathParams.values.first()
                    else mapper.convertValue(pathParams, mapper.typeFactory.constructType(targetType))
                } else null
            } else {
                if (targetType == String::class.java) {
                    body
                } else if (targetType == Any::class.java || targetType.typeName == "java.lang.Object") {
                    try {
                        mapper.readValue(body, mapper.typeFactory.constructType(targetType))
                    } catch (e: Exception) {
                        body // Fallback to raw string if it's not valid JSON
                    }
                } else {
                    mapper.readValue(body, mapper.typeFactory.constructType(targetType))
                }
            }
        }
    }

    private fun extractPathParameters(routePath: String, requestPath: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val routeSegments = routePath.trim('/').split("/").filter { it.isNotEmpty() }
        val requestSegments = requestPath.trim('/').split("/").filter { it.isNotEmpty() }
        
        if (routeSegments.size != requestSegments.size) return emptyMap()

        for (i in routeSegments.indices) {
            val routeSegment = routeSegments[i]
            if (routeSegment.startsWith("{") && routeSegment.endsWith("}")) {
                val paramName = routeSegment.substring(1, routeSegment.length - 1)
                params[paramName] = requestSegments[i]
            }
        }
        return params
    }

    private fun matches(routePath: String, requestPath: String): Boolean {
        val cleanRoute = routePath.trim('/')
        val cleanRequest = requestPath.trim('/')
        if (cleanRoute == cleanRequest) return true
        
        val regex = "^" + cleanRoute.replace(Regex("\\{[^/]+\\}"), "[^/]+") + "$"
        return cleanRequest.matches(Regex(regex))
    }

    private fun respond(response: Response, status: Int, payload: Any) {
        response.status = status
        response.setContentType("application/json")
        
        val bytes = if (payload is String && (payload.startsWith("{") || payload.startsWith("["))) {
            payload.toByteArray()
        } else {
            mapper.writeValueAsBytes(payload)
        }
        response.outputStream.write(bytes)
    }

    override fun stop() { server?.shutdownNow() }
}
