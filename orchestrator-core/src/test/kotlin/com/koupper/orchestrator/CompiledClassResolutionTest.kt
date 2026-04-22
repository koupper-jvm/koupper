package com.koupper.orchestrator

import io.kotest.core.spec.style.AnnotationSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.assertTrue

/**
 * Verifies that [JobRunner.runCompiled] builds class-name candidates correctly and
 * tries all case variants before failing.
 *
 * [runCompiled] swallows exceptions internally, but always logs a
 * "[compiled] class candidates: ..." line to stdout before loading. We capture
 * stdout to assert the candidate list without needing real compiled classes.
 */
class CompiledClassResolutionTest : AnnotationSpec() {

    private fun task(
        fileName: String,
        packageName: String = "com.example",
        functionName: String = "setup"
    ) = KouTask(
        id = "test-${fileName}",
        fileName = fileName,
        functionName = functionName,
        packageName = packageName,
        sourceType = "compiled",
        context = "."
    )

    /** Captures stdout produced by [JobRunner.runCompiled] for the given task. */
    private fun capturedOutput(task: KouTask): String {
        val baos = ByteArrayOutputStream()
        val ps = PrintStream(baos)
        val old = System.out
        System.setOut(ps)
        try { JobRunner.runCompiled(".", task) } finally {
            System.setOut(old)
            ps.flush()
        }
        return baos.toString()
    }

    // -------------------------------------------------------------------------
    // Candidate list — exact + lowercase-first + uppercase-first, with and without Kt
    // -------------------------------------------------------------------------

    @Test
    fun `PascalCase fileName tries lowercase-first Kt candidate`() {
        // file = RegisterIglyContactLeadUser.kt  →  JVM class = registerIglyContactLeadUserKt
        val out = capturedOutput(task("RegisterIglyContactLeadUser"))
        assertTrue(
            "com.example.registerIglyContactLeadUserKt" in out,
            "Expected lowercase-first Kt candidate in output:\n$out"
        )
    }

    @Test
    fun `camelCase fileName tries exact Kt candidate`() {
        val out = capturedOutput(task("registerIglyContactLeadUser"))
        assertTrue(
            "com.example.registerIglyContactLeadUserKt" in out,
            "Expected exact camelCase Kt candidate in output:\n$out"
        )
    }

    @Test
    fun `PascalCase fileName also tries exact PascalCase Kt candidate`() {
        val out = capturedOutput(task("RegisterFoo"))
        assertTrue(
            "com.example.RegisterFooKt" in out,
            "Expected PascalCase Kt candidate in output:\n$out"
        )
    }

    @Test
    fun `fileName with dot-kt extension is stripped before building candidates`() {
        val out = capturedOutput(task("MyScript.kt"))
        assertTrue(
            "com.example.MyScriptKt" in out,
            "Expected .kt to be stripped in output:\n$out"
        )
        val candidateLine = out.lines().first { "[compiled] class candidates:" in it }
        assertTrue(
            ".kt" !in candidateLine.substringAfter("candidates:"),
            "Residual .kt extension must not appear in candidate names:\n$candidateLine"
        )
    }

    @Test
    fun `fileName with dot-class extension is stripped before building candidates`() {
        val out = capturedOutput(task("MyScript.class"))
        assertTrue(
            "com.example.MyScriptKt" in out,
            "Expected .class to be stripped in output:\n$out"
        )
    }

    @Test
    fun `no-package task builds unqualified candidates`() {
        val out = capturedOutput(task("Standalone", packageName = ""))
        assertTrue("StandaloneKt" in out, "Expected unqualified PascalCase Kt candidate:\n$out")
        assertTrue("standaloneKt" in out, "Expected unqualified lowercase-first Kt candidate:\n$out")
    }

    @Test
    fun `at least four candidate variants are logged (deduplication collapses same-case pairs)`() {
        // For a camelCase name like "myJob", all 6 variants are distinct.
        // For a PascalCase name, ucFirst == exact so linkedSetOf deduplicates to 4.
        // The minimum guarantee is 4: exact+Kt, lcFirst+Kt, exact (no-Kt), lcFirst (no-Kt).
        val out = capturedOutput(task("myJob"))
        val candidateLine = out.lines().first { "[compiled] class candidates:" in it }
        val count = candidateLine.substringAfter("candidates:").split(",").size
        assertTrue(count >= 4, "Expected at least 4 candidates, got $count in:\n$candidateLine")
    }

    @Test
    fun `fileName with trailing Kt is not double-stripped — bare Kt class is still a candidate`() {
        // fileName="MyJobKt" → fileBase="MyJobKt" → candidates include "MyJobKt" (no extra Kt)
        val out = capturedOutput(task("MyJobKt"))
        assertTrue(
            "com.example.MyJobKt" in out,
            "Bare MyJobKt must be a candidate:\n$out"
        )
    }
}
