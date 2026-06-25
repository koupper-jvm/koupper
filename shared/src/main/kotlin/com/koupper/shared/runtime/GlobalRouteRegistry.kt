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
    val validationSchema: Any? = null
)

/**
 * Shared Registry for the Web Server.
 * This is the SINGLE point of truth for the entire JVM process.
 */
object GlobalRouteRegistry {
    val routes = CopyOnWriteArrayList<RegisteredRuntimeRoute>()
    val middlewares = ConcurrentHashMap<String, (Any) -> Any>() // Generic bridge
}
