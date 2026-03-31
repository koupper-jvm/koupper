package com.koupper.octopus.process

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleAnalyzerTest {

    @Test
    fun `discoverHandlers detects KHandler and AWS RequestHandler under handlers directories`() {
        val moduleDir = Files.createTempDirectory("module-analyzer-test").toFile()
        try {
            writeKotlinFile(
                moduleDir,
                "src/main/kotlin/http/handlers/CreateUserHandler.kt",
                """
                package http.handlers

                class CreateUserHandler : KHandler {
                    fun execute() = Unit
                }
                """.trimIndent()
            )

            writeKotlinFile(
                moduleDir,
                "src/main/kotlin/com/example/handlers/RequestHandlerNotify.kt",
                """
                package com.example.handlers

                class RequestHandlerNotify : com.amazonaws.services.lambda.runtime.RequestHandler<Any, Any> {
                    override fun handleRequest(input: Any, context: com.amazonaws.services.lambda.runtime.Context): Any = input
                }
                """.trimIndent()
            )

            val analyzer = ModuleAnalyzer(context = moduleDir.path)
            val discovery = invokePrivate(analyzer, "discoverHandlers", moduleDir)

            val sourceFiles = readSet(discovery, "sourceFiles")
            val khandlerNames = readSet(discovery, "khandlerNames")
            val awsRequestHandlerNames = readSet(discovery, "awsRequestHandlerNames")

            assertEquals(2, sourceFiles.size)
            assertEquals(setOf("CreateUserHandler"), khandlerNames)
            assertEquals(setOf("RequestHandlerNotify"), awsRequestHandlerNames)
        } finally {
            moduleDir.deleteRecursively()
        }
    }

    @Test
    fun `discoverHandlers detects AWS handlers via inherited Setup handleRequest override`() {
        val moduleDir = Files.createTempDirectory("module-analyzer-test").toFile()
        try {
            writeKotlinFile(
                moduleDir,
                "src/main/kotlin/Setup.kt",
                """
                abstract class Setup : com.amazonaws.services.lambda.runtime.RequestHandler<Any, Any>
                """.trimIndent()
            )

            writeKotlinFile(
                moduleDir,
                "src/main/kotlin/com/example/handlers/SendMailHandler.kt",
                """
                package com.example.handlers

                class SendMailHandler : Setup() {
                    override fun handleRequest(input: Any, context: com.amazonaws.services.lambda.runtime.Context): Any = input
                }
                """.trimIndent()
            )

            val analyzer = ModuleAnalyzer(context = moduleDir.path)
            val discovery = invokePrivate(analyzer, "discoverHandlers", moduleDir)

            assertEquals(setOf("SendMailHandler"), readSet(discovery, "awsRequestHandlerNames"))
        } finally {
            moduleDir.deleteRecursively()
        }
    }

    @Test
    fun `discoverHandlers ignores files outside handlers directories`() {
        val moduleDir = Files.createTempDirectory("module-analyzer-test").toFile()
        try {
            writeKotlinFile(
                moduleDir,
                "src/main/kotlin/http/controllers/CreateUserController.kt",
                """
                package http.controllers

                class CreateUserController : KHandler
                """.trimIndent()
            )

            val analyzer = ModuleAnalyzer(context = moduleDir.path)
            val discovery = invokePrivate(analyzer, "discoverHandlers", moduleDir)

            assertTrue(readSet(discovery, "sourceFiles").isEmpty())
            assertTrue(readSet(discovery, "khandlerNames").isEmpty())
            assertTrue(readSet(discovery, "awsRequestHandlerNames").isEmpty())
        } finally {
            moduleDir.deleteRecursively()
        }
    }

    private fun writeKotlinFile(baseDir: File, relativePath: String, content: String) {
        val file = File(baseDir, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    private fun invokePrivate(analyzer: ModuleAnalyzer, methodName: String, moduleDir: File): Any {
        val method = analyzer.javaClass.getDeclaredMethod(methodName, File::class.java)
        method.isAccessible = true
        return method.invoke(analyzer, moduleDir)
    }

    private fun readSet(discovery: Any, fieldName: String): Set<String> {
        val field = discovery.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        val value = field.get(discovery)

        return when (value) {
            is Set<*> -> value.filterIsInstance<String>().toSet()
            is List<*> -> value.mapNotNull {
                when (it) {
                    is String -> it
                    is File -> it.name
                    else -> null
                }
            }.toSet()

            else -> emptySet()
        }
    }
}
