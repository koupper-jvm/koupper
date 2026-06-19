package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.*

class OctopusE2ETest : AnnotationSpec() {

    private val octopus = EmbeddedOctopus.get()

    @Test
    fun `should run script with Export and return result`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export

            @Export
            val setup: () -> String = { "hello world" }
        """.trimIndent())

        assertEquals("hello world", result)
    }

    @Test
    fun `should run simple computation script`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export

            @Export
            val setup: () -> String = { (40 + 2).toString() }
        """.trimIndent())

        assertEquals("42", result)
    }

    @Test
    fun `should fail when no Export annotation`() {
        val result = octopus.runScript("""
            val x: () -> String = { "nothing" }
        """.trimIndent())

        assertTrue(result.contains("[ERR_EXPORT_MISSING]"), "should include error code: $result")
    }

    @Test
    fun `should fail when multiple Export declarations`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export

            @Export
            val setup: () -> String = { "one" }

            @Export
            val runner: () -> String = { "two" }
        """.trimIndent())

        assertTrue(result.contains("[ERR_EXPORT_MULTIPLE]"), "should include error code: $result")
    }

    @Test
    fun `should compile and run script with Secret annotation`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Secret

            @Secret
            @Export
            val setup: () -> String = { "secret-op-ok" }
        """.trimIndent())

        assertEquals("secret-op-ok", result)
    }

    @Test
    fun `should pass version check when declared version matches runtime`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.KoupperVersion

            @KoupperVersion("6.5")
            @Export
            val setup: () -> String = { "version-ok" }
        """.trimIndent())

        assertEquals("version-ok", result)
    }

    @Test
    fun `should fail when declared version does not match runtime`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.KoupperVersion

            @KoupperVersion("99.0")
            @Export
            val setup: () -> String = { "never-runs" }
        """.trimIndent())

        assertTrue(result.contains("[ERR_VERSION_MISMATCH]"), "should have error code: $result")
    }

    @Test
    fun `should return compile error code for broken syntax`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export

            @Export
            val setup: () -> String = { brokenSyntax!!!
        """.trimIndent())

        assertTrue(result.contains("[ERR_COMPILE]") || result.contains("error"),
            "should indicate compile failure: $result")
    }

    @Test
    fun `should have KOUPPER_VERSION available in script`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export

            @Export
            val setup: () -> String = { KOUPPER_VERSION }
        """.trimIndent())

        assertTrue(result.startsWith("6."), "KOUPPER_VERSION should be 6.x: $result")
    }
}
