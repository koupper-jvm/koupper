package com.koupper.octopus.modules.http

class PostDsl<I : Any, O : Any>(
    private val basePath: String
) {

    private var subPath: String = ""
    private var middlewares: List<String> = emptyList()
    private var script: HttpRouteScript<I, O>? = null

    fun path(block: () -> String) {
        subPath = block()
    }

    fun middlewares(block: () -> List<String>) {
        middlewares = block()
    }

    fun script(block: () -> HttpRouteScript<I, O>) {
        script = block()
    }

    fun build(): RegisteredRoute {

        val finalScript = requireNotNull(script) { "POST script is required" }

        return RegisteredRoute(
            method = "POST",
            path = normalizePath(basePath, subPath),
            middlewares = middlewares,
            script = { input -> finalScript(input as I) }
        )
    }
}
