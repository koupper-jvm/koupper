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
        val result = octopus.runScript("""val x: () -> String = { "nothing" }""")
        assertTrue(result.contains("[ERR_EXPORT_MISSING]"), "should include error code: $result")
    }

    @Test
    fun `should fail when multiple Export declarations`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export val setup: () -> String = { "one" }
            @Export val runner: () -> String = { "two" }
        """.trimIndent())
        assertTrue(result.contains("[ERR_EXPORT_MULTIPLE]"), "should include error code: $result")
    }

    @Test
    fun `should compile and run script with Secret annotation`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Secret
            @Secret @Export
            val setup: () -> String = { "secret-op-ok" }
        """.trimIndent())
        assertEquals("secret-op-ok", result)
    }

    @Test
    fun `should pass version check when declared version matches runtime`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.KoupperVersion
            @KoupperVersion("6.5") @Export
            val setup: () -> String = { "version-ok" }
        """.trimIndent())
        assertEquals("version-ok", result)
    }

    @Test
    fun `should fail when declared version does not match runtime`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.KoupperVersion
            @KoupperVersion("99.0") @Export
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
        assertTrue(result.contains("[ERR_COMPILE]") || result.contains("error"), "should indicate compile failure: $result")
    }

    @Test
    fun `should map compile error lines to original source when preamble is injected`() {
        // This script uses log. which triggers preamble injection.
        // The error is on line 4 of the user script, but with preamble it becomes line ~25.
        // Source mapping should report line 4 (or close to it), not 25.
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                log.info { "hello" }
                brokenSyntaxForSourceMapping!!!
            }
        """.trimIndent())
        assertTrue(result.contains("[ERR_COMPILE]"), "should have error code: $result")
        // With source mapping, the line number should be small (original script line)
        // rather than a large number (preamble-augmented line).
        // The error occurs at line 5 of the user script (0-indexed: 4).
        // We assert the line number is reasonable (< 20) and not > 30.
        val lineNumberRegex = Regex("""\(line (\d+),""")
        val match = lineNumberRegex.find(result)
        if (match != null) {
            val reportedLine = match.groupValues[1].toInt()
            assertTrue(
                reportedLine < 20,
                "Reported line should be mapped to original source (< 20), but was $reportedLine. Full output: $result"
            )
        }
        assertTrue(
            result.contains("preamble offset:") || result.contains("relative to your .kts file"),
            "Error message should mention source mapping: $result"
        )
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

    @Test
    fun `should access env shortcut from preamble`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                val home = env("PATH")
                if (home.isNotEmpty()) "env-ok" else "env-empty"
            }
        """.trimIndent())
        assertEquals("env-ok", result)
    }

    @Test
    fun `should use emit shortcut from preamble`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                emit("emitted message")
                "emit-ok"
            }
        """.trimIndent())
        assertEquals("emit-ok", result)
    }

    @Test
    fun `should handle null input gracefully`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = { "no-input-needed" }
        """.trimIndent())
        assertEquals("no-input-needed", result)
    }

    @Test
    fun `should return error code prefix in all error formats`() {
        val missing = octopus.runScript("val x = 1")
        assertTrue(missing.startsWith("[ERR_"), "should start with error code: $missing")

        val multi = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export val a: () -> String = {"a"}
            @Export val b: () -> String = {"b"}
        """.trimIndent())
        assertTrue(multi.startsWith("[ERR_"), "should start with error code: $multi")

        val version = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.KoupperVersion
            @KoupperVersion("99.0") @Export val s: () -> String = {"x"}
        """.trimIndent())
        assertTrue(version.startsWith("[ERR_"), "should start with error code: $version")
    }

    @Test
    fun `should run two separate scripts without cross-contamination`() {
        val r1 = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export val s: () -> String = {"first"}
        """.trimIndent())
        val r2 = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export val s: () -> String = {"second"}
        """.trimIndent())
        assertEquals("first", r1)
        assertEquals("second", r2)
    }

    @Test
    fun `should use env with default value`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                val v = env("NONEXISTENT_VAR_12345", "fallback")
                "got: ${'$'}v"
            }
        """.trimIndent())
        assertEquals("got: fallback", result)
    }

    @Test
    fun `should run same script twice with consistent results`() {
        val script = """
            import com.koupper.shared.annotations.Export
            @Export val s: () -> String = {"consistent"}
        """.trimIndent()

        val r1 = octopus.runScript(script)
        val r2 = octopus.runScript(script)
        assertEquals("consistent", r1)
        assertEquals("consistent", r2)
    }

    @Test
    fun `should handle export with multiline lambda body`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            @Export
            val setup: () -> String = {
                val x = 10
                val y = 20
                (x + y).toString()
            }
        """.trimIndent())
        assertEquals("30", result)
    }

    @Test
    fun `should include suggestion in error message`() {
        val result = octopus.runScript("val x = 1")
        assertTrue(result.contains("@Export"), "error should contain @Export hint: $result")
        assertTrue(result.contains("Add exactly one"), "error should have actionable suggestion: $result")
    }

    @Test
    fun `should handle script with only whitespace and comments`() {
        val result = octopus.runScript("""
            // This is a comment
            val x = 1
        """.trimIndent())
        assertTrue(result.contains("[ERR_EXPORT_MISSING]"), "should fail with error: $result")
    }

    @Test
    fun `should register scheduled job with rate`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Scheduled
            @Scheduled(rate=3600000)
            @Export
            val setup_scheduled_test: () -> String = { "scheduled-ok" }
        """.trimIndent())
        // ScheduledSetup.run() returns a registration confirmation
        assertTrue(
            result.contains("Scheduled") || result.contains("registered") || result.contains("⏭️"),
            "Should indicate scheduled registration: $result"
        )
    }

    @Test
    fun `should not register same scheduled script twice`() {
        val script = """
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Scheduled
            @Scheduled(rate=3600000)
            @Export
            val setup_scheduled_dup_test: () -> String = { "scheduled-dup" }
        """.trimIndent()
        val r1 = octopus.runScript(script)
        val r2 = octopus.runScript(script)
        // First run registers, second run skips
        assertTrue(
            r1.contains("Scheduled") || r1.contains("registered"),
            "First run should register: $r1"
        )
        assertTrue(
            r2.contains("Already scheduled") || r2.contains("⏭️"),
            "Second run should skip: $r2"
        )
    }

    @Test
    fun `should execute pipeline annotation`() {
        val result = octopus.runScript("""
            import com.koupper.shared.annotations.Export
            import com.koupper.shared.annotations.Pipeline
            @Pipeline(cron="0 0 * * *", chain="StageA,StageB", id="test-pipeline")
            @Export
            val setup_pipeline_test: () -> String = { "pipeline-trigger" }
        """.trimIndent())
        // Pipeline resolver is terminal; it should execute and return something
        assertTrue(
            result.contains("pipeline") || result.contains("Pipeline") || result.contains("coordinator"),
            "Should execute pipeline setup: $result"
        )
    }
}
