package com.koupper.orchestrator.paginator

import com.koupper.orchestrator.buildParamsJson
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class ScriptRunnerOrchestratorTest {
    @Test
    fun `should build params json using params first and then positionals ignoring flags`() {
        val result = buildParamsJson(
            types = listOf(
                "NetworkCommunicationForm",
                "String",
                "Int",
                "Int",
                "String",
                "User",
                "String"
            ),
            positionals = listOf("hello", "15", "99", "active", """{"id":1}""", "done"),
            params = mapOf(
                "arg0" to """{"challenge":"automatizacion","urgency":"inmediato"}"""
            ),
            flags = setOf("--verbose", "-x")
        )

        assertEquals(
            linkedMapOf(
                "arg0" to """{"challenge":"automatizacion","urgency":"inmediato"}""",
                "arg1" to "hello",
                "arg2" to "15",
                "arg3" to "99",
                "arg4" to "active",
                "arg5" to """{"id":1}""",
                "arg6" to "done"
            ),
            result
        )
    }
}