package com.koupper.octopus

import com.koupper.shared.runtime.ScriptSandbox
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*

class ScriptSandboxTest : AnnotationSpec() {

    @BeforeTest
    fun setup() {
        System.setProperty("koupper.scripting.sandbox", "true")
        System.setProperty("koupper.scripting.timeoutMs", "5000") // 5s for tests
    }

    @AfterTest
    fun teardown() {
        System.clearProperty("koupper.scripting.sandbox")
        System.clearProperty("koupper.scripting.timeoutMs")
    }

    @Test
    fun `should block System exit in sandboxed script`() {
        val result = EmbeddedOctopus.get().runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                try {
                    System.exit(1)
                    "should-not-reach"
                } catch (e: Exception) {
                    "exit-blocked"
                }
            }
        """.trimIndent())
        assertTrue(
            result.contains("exit-blocked") || result.contains("blocked") || result.contains("sandbox"),
            "Script should handle blocked System.exit or show sandbox error. Got: $result"
        )
    }

    @Test
    fun `should enforce timeout on long-running script`() {
        val start = System.currentTimeMillis()
        val result = try {
            EmbeddedOctopus.get().runScript("""
                import com.koupper.shared.annotations.Export
                @Export
                val setup: () -> String = {
                    Thread.sleep(30000) // Way longer than 5s timeout
                    "completed"
                }
            """.trimIndent())
        } catch (e: Exception) {
            "timeout-or-interrupted"
        }
        val elapsed = System.currentTimeMillis() - start

        assertTrue(
            elapsed < 10000,
            "Script should have been terminated before 10s, but took ${elapsed}ms"
        )
        assertTrue(
            result.contains("timeout") || result.contains("interrupted") || result.contains("sandbox") || result == "timeout-or-interrupted",
            "Result should indicate timeout. Got: $result"
        )
    }

    @Test
    fun `should allow normal script execution in sandbox`() {
        val result = EmbeddedOctopus.get().runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = { "sandbox-ok" }
        """.trimIndent())
        assertEquals("sandbox-ok", result)
    }
}
