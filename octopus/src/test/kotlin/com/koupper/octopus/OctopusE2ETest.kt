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

        assertTrue(result.contains("@Export"), "should mention @Export: $result")
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

        assertTrue(result.contains("Multiple @Export"), "should mention Multiple @Export: $result")
    }
}
