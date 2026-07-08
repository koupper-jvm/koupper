package com.koupper.providers.runtime.router

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.koupper.container.app
import com.koupper.providers.templates.TemplateProvider
import com.koupper.shared.runtime.GlobalRouteRegistry
import com.koupper.shared.runtime.MultipartForm
import com.koupper.shared.runtime.RegisteredRuntimeRoute
import com.koupper.shared.runtime.TemplateResponse
import com.koupper.shared.runtime.WebResponse
import com.koupper.shared.validators.core.Schema
import java.net.URLDecoder

/**
 * Transport-agnostic view of an incoming HTTP request. Adapters (Grizzly,
 * AWS Lambda, tests) translate their native request into this shape.
 */
class DispatchRequest(
    val method: String,
    val path: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val queryString: String? = null,
    val contentType: String? = null,
    bodyProvider: () -> ByteArray = { ByteArray(0) }
) {
    val bodyBytes: ByteArray by lazy(bodyProvider)
}

sealed class DispatchOutcome {
    data class Completed(
        val status: Int,
        val payload: Any,
        val contentType: String? = null,
        val headers: Map<String, String> = emptyMap()
    ) : DispatchOutcome()

    data class Stream(val stream: StreamResponse) : DispatchOutcome()
}

/**
 * Core routing engine: matches a request against GlobalRouteRegistry, runs
 * middlewares, builds the handler argument, invokes the handler and
 * normalizes its output. Contains no server/transport code.
 */
class RouteDispatcher {
    private val mapper = jacksonObjectMapper()

    fun dispatch(request: DispatchRequest): DispatchOutcome {
        val method = request.method.uppercase()
        val path = request.path

        val routes = GlobalRouteRegistry.routes
        val route = routes.firstOrNull { it.method.name == method && matches(it.fullPath, path) }
            ?: return DispatchOutcome.Completed(404, mapOf("error" to "Route not found", "path" to path, "registered" to routes.map { "${it.method} ${it.fullPath}" }))

        val reqCtx = RequestContext(
            method = method,
            path = path,
            body = "",
            headers = request.headers,
            queryParams = parseQueryString(request.queryString ?: ""),
            pathParams = extractPathParams(route.fullPath, path)
        )
        GlobalRouteRegistry.currentRequest.set(reqCtx)
        try {
            for (middlewareName in route.middlewares) {
                // Fail closed: a declared-but-missing middleware (typo, setup not run)
                // must never silently leave the route unprotected.
                val middleware = GlobalRouteRegistry.middlewares[middlewareName]
                    ?: return DispatchOutcome.Completed(
                        500,
                        mapOf("error" to "Middleware '$middlewareName' declared on ${route.method} ${route.fullPath} is not registered")
                    )
                val result = middleware(reqCtx) as? MiddlewareResult
                    ?: return DispatchOutcome.Completed(
                        500,
                        mapOf("error" to "Middleware '$middlewareName' returned an invalid result")
                    )
                if (!result.allowed) {
                    return DispatchOutcome.Completed(result.statusCode, mapOf("error" to result.message))
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
                        @Suppress("UNCHECKED_CAST")
                        val schema = route.validationSchema as Schema<Any>
                        val vResult = com.koupper.shared.validators.core.validate(arg, schema)
                        if (!vResult.ok) {
                            return DispatchOutcome.Completed(400, mapOf("error" to "Validation failed", "details" to vResult.errors))
                        }
                    }

                    invokeMethod.invoke(handler, arg)
                } else {
                    invokeMethod.invoke(handler)
                }

                if (output is StreamResponse) {
                    return DispatchOutcome.Stream(output)
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
                            @Suppress("UNCHECKED_CAST")
                            explicitHeaders = headersGetter.invoke(output) as? Map<String, String> ?: emptyMap()
                        }
                    } catch (e: Exception) {
                        // Ignore reflection errors and treat as normal payload
                    }
                }

                return DispatchOutcome.Completed(status, finalOutput, explicitContentType, explicitHeaders)
            } catch (e: IllegalArgumentException) {
                return DispatchOutcome.Completed(400, mapOf("error" to "Invalid input format", "detail" to (e.message ?: "")))
            } catch (e: Throwable) {
                val root = e.cause ?: e
                val handler = GlobalRouteRegistry.exceptionHandler
                return if (handler != null) {
                    try {
                        val res = handler(root)
                        DispatchOutcome.Completed(res.statusCode, res.body, res.contentType, res.headers)
                    } catch (e2: Throwable) {
                        DispatchOutcome.Completed(500, mapOf("error" to "Error in exception handler", "type" to e2.javaClass.name))
                    }
                } else {
                    DispatchOutcome.Completed(500, mapOf("error" to root.message, "type" to root.javaClass.name))
                }
            }
        } finally {
            GlobalRouteRegistry.currentRequest.remove()
        }
    }

    /**
     * Serializes a dispatch payload to (contentType, bytes) so every transport
     * (Grizzly response, Lambda proxy response) renders bodies identically.
     */
    fun serializePayload(payload: Any, explicitContentType: String? = null): Pair<String, ByteArray> = when {
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

    private fun buildArgument(request: DispatchRequest, route: RegisteredRuntimeRoute): Any? {
        val inputType = route.inputType ?: return null
        val httpMethod = request.method.uppercase()

        if (inputType == MultipartForm::class.java) {
            val contentType = request.contentType ?: ""
            if (contentType.startsWith("multipart/form-data")) {
                return parseMultipart(contentType, request.bodyBytes)
            }
            return MultipartForm()
        }

        if (inputType == String::class.java) {
            if (httpMethod == "POST" || httpMethod == "PUT" || httpMethod == "PATCH") {
                return String(request.bodyBytes, Charsets.UTF_8)
            }
            val pathParams = extractPathParams(route.fullPath, request.path)
            return pathParams.values.firstOrNull()
                ?: URLDecoder.decode(request.path.trim('/').split("/").lastOrNull() ?: "", "UTF-8")
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
                val bodyBytes = request.bodyBytes
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
}
