package com.koupper.shared.runtime

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Common HTTP Method definitions.
 */
enum class RouteMethod {
    GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
}

/**
 * Global Route Contract.
 * Lives in 'shared' to be visible across all ClassLoaders.
 */
data class RegisteredRuntimeRoute(
    val method: RouteMethod,
    val fullPath: String,
    val middlewares: List<String>,
    val handler: Any,
    val inputType: java.lang.reflect.Type? = null,
    val outputType: java.lang.reflect.Type? = null,
    val validationSchema: Any? = null
)

data class CorsConfig(
    var allowedOrigins: List<String> = listOf("*"),
    var allowedMethods: List<String> = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"),
    var allowedHeaders: List<String> = listOf("Content-Type", "Authorization")
)

/**
 * Resolve the value for `Access-Control-Allow-Origin`.
 *
 * Browsers require a single origin (or `*`), never a comma-joined list.
 * When a concrete allow-list is configured, echo the request Origin only if it matches.
 *
 * @return header value, or `null` when the request Origin is not allowed (omit the header).
 */
fun resolveCorsAllowOrigin(allowedOrigins: List<String>?, requestOrigin: String?): String? {
    val allowed = allowedOrigins.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
    if (allowed.isEmpty() || allowed.any { it == "*" }) return "*"
    if (requestOrigin.isNullOrBlank()) return null
    return if (allowed.any { it == requestOrigin }) requestOrigin else null
}

/**
 * Shared Registry for the Web Server.
 * This is the SINGLE point of truth for the entire JVM process.
 */
object GlobalRouteRegistry {
    val routes = CopyOnWriteArrayList<RegisteredRuntimeRoute>()
    val middlewares = ConcurrentHashMap<String, (Any) -> Any>() // Generic bridge
    var corsConfig: CorsConfig? = null
    var exceptionHandler: ((Throwable) -> WebResponse)? = null
    val currentRequest = ThreadLocal<Any>()
}
