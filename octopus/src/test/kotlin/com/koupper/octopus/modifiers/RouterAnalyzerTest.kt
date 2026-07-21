package com.koupper.octopus.modifiers

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RouterAnalyzerTest {

    @Test
    fun `extracts V7 RuntimeRouterDsl endpoints with script handle`() {
        val moduleDir = Files.createTempDirectory("router-analyzer-test").toFile()
        try {
            val routes = File(
                moduleDir,
                "src/main/kotlin/http/routes/CommsRoutes.kt"
            ).apply { parentFile.mkdirs() }
            routes.writeText(
                """
                package http.routes
                import com.koupper.providers.runtime.router.RuntimeRouterDsl

                fun RuntimeRouterDsl.commsRoutes() {
                    get<Any> {
                        path { "/api/v1/test/health" }
                        fun handle(req: Any): String = "ok"
                        script { ::handle }
                    }
                    post<IglyUserInfo> {
                        path { "/api/v1/test/igly/newsletter" }
                        fun handle(dto: IglyUserInfo): String = "queued"
                        script { ::handle }
                    }
                }
                """.trimIndent()
            )

            val data = RouterAnalyzer().analyzeRouters(moduleDir, port = 8081)
            assertEquals(1, data.size)
            val endpoints = data.first()["endpoints"] as List<*>
            assertEquals(2, endpoints.size)

            val methods = endpoints.map { (it as Map<*, *>)["method"] }
            val paths = endpoints.map { (it as Map<*, *>)["path"] }
            val handlers = endpoints.map { (it as Map<*, *>)["handler"] }

            assertTrue(methods.containsAll(listOf("GET", "POST")))
            assertTrue(paths.contains("/api/v1/test/igly/newsletter"))
            assertTrue(handlers.all { it == "handle" })
        } finally {
            moduleDir.deleteRecursively()
        }
    }
}
