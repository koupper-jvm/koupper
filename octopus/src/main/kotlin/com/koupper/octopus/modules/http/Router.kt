package com.koupper.octopus.modules.http

class Router {
    private val routes = mutableListOf<RegisteredRoute>()

    fun registerRouter(block: RouterDsl.() -> Unit): Router {

        val builtRoutes = RouterDsl()
            .apply(block)
            .build()

        routes += builtRoutes

        return this
    }

    fun routes(): List<RegisteredRoute> {
        return routes
    }
}
