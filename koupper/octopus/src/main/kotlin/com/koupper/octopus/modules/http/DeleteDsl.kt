package com.koupper.octopus.modules.http

class DeleteDsl<O : Any>(
    private val basePath: String
) {

    private var subPath: String = ""
    private var middlewares: List<String> = emptyList()
    private var script: (() -> O)? = null

    fun path(block: () -> String) {
        subPath = block()
    }

    fun middlewares(block: () -> List<String>) {
        middlewares = block()
    }

    fun script(block: () -> (() -> O)) {
        script = block()
    }

    fun build(): RegisteredRoute {

        val finalScript = requireNotNull(script) { "DELETE script is required" }

        return RegisteredRoute(
            method = "DELETE",
            path = normalizePath(basePath, subPath),
            middlewares = middlewares,
            script = { finalScript() }
        )
    }
}
