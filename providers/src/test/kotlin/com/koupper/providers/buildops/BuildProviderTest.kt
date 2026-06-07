package com.koupper.providers.buildops

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class BuildProviderTest : StringSpec({

    val provider = BuildProviderImpl()

    fun tempDir(vararg files: Pair<String, String>): File {
        val dir = Files.createTempDirectory("build-test-").toFile()
        files.forEach { (name, content) -> File(dir, name).writeText(content) }
        return dir
    }

    // ── Tool detection ────────────────────────────────────────────────────────

    "returns PROJECT_NOT_FOUND for non-existent directory" {
        val result = provider.build(File("/nonexistent/path"))
        result.shouldBeInstanceOf<BuildResult.Err>()
        (result as BuildResult.Err).code shouldBe BuildErrorCode.PROJECT_NOT_FOUND
    }

    "returns TOOL_NOT_FOUND when no build tool detected" {
        val dir = tempDir("README.md" to "hello")
        val result = provider.build(dir)
        result.shouldBeInstanceOf<BuildResult.Err>()
        (result as BuildResult.Err).code shouldBe BuildErrorCode.TOOL_NOT_FOUND
    }

    "test() returns PROJECT_NOT_FOUND for non-existent directory" {
        val result = provider.test(File("/nonexistent/path"))
        result.shouldBeInstanceOf<BuildResult.Err>()
        (result as BuildResult.Err).code shouldBe BuildErrorCode.PROJECT_NOT_FOUND
    }

    "run() returns PROJECT_NOT_FOUND for non-existent directory" {
        val result = provider.run(File("/nonexistent/path"), "build")
        result.shouldBeInstanceOf<BuildResult.Err>()
        (result as BuildResult.Err).code shouldBe BuildErrorCode.PROJECT_NOT_FOUND
    }

    // ── Failure.summary ───────────────────────────────────────────────────────

    "Failure.summary formats errors with file and line" {
        val errors = listOf(
            BuildError("src/Main.kt", 10, 5, "unresolved reference: foo", Severity.ERROR, "raw"),
            BuildError(null, null, null, "Task :compileKotlin FAILED", Severity.ERROR, "raw2")
        )
        val failure = BuildResult.Failure(errors, "", 0L, 1)
        failure.summary shouldContain "src/Main.kt:10:5"
        failure.summary shouldContain "unresolved reference: foo"
        failure.summary shouldContain "Task :compileKotlin FAILED"
    }

    "Failure.summary handles errors without file info" {
        val errors = listOf(BuildError(null, null, null, "generic error", Severity.ERROR, "raw"))
        val failure = BuildResult.Failure(errors, "", 0L, 1)
        failure.summary shouldContain "generic error"
    }

    // ── KotlinParser ──────────────────────────────────────────────────────────

    "KotlinParser parses e: error line" {
        val line = "e: /home/user/project/src/Main.kt:42:13: error: unresolved reference: bar"
        val errors = KotlinParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].file shouldBe "/home/user/project/src/Main.kt"
        errors[0].line shouldBe 42
        errors[0].column shouldBe 13
        errors[0].message shouldBe "unresolved reference: bar"
        errors[0].severity shouldBe Severity.ERROR
    }

    "KotlinParser parses w: warning line" {
        val line = "w: /src/Foo.kt:5:1: warning: variable shadowed"
        val errors = KotlinParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].severity shouldBe Severity.WARNING
    }

    "KotlinParser parses file:// URI prefix" {
        val line = "e: file:///home/user/Main.kt:10:1: error: type mismatch"
        val errors = KotlinParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].file shouldBe "/home/user/Main.kt"
    }

    "KotlinParser ignores non-matching lines" {
        val lines = listOf("BUILD FAILED", "> Task :compileKotlin FAILED", "  ^")
        KotlinParser.parse(lines) shouldHaveSize 0
    }

    // ── TypeScriptParser ──────────────────────────────────────────────────────

    "TypeScriptParser parses file:line:col format" {
        val line = "src/index.ts:10:5 - error TS2304: Cannot find name 'x'"
        val errors = TypeScriptParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].file shouldBe "src/index.ts"
        errors[0].line shouldBe 10
        errors[0].column shouldBe 5
        errors[0].message shouldBe "Cannot find name 'x'"
        errors[0].severity shouldBe Severity.ERROR
    }

    "TypeScriptParser parses file(line,col) format" {
        val line = "src/app.ts(20,3): error TS2551: Property 'foo' does not exist"
        val errors = TypeScriptParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].file shouldBe "src/app.ts"
        errors[0].line shouldBe 20
        errors[0].column shouldBe 3
    }

    "TypeScriptParser parses warning severity" {
        val line = "src/util.ts:3:1 - warning TS6133: 'x' is declared but its value is never read"
        val errors = TypeScriptParser.parse(listOf(line))
        errors shouldHaveSize 1
        errors[0].severity shouldBe Severity.WARNING
    }

    // ── NpmErrorParser ────────────────────────────────────────────────────────

    "NpmErrorParser parses npm ERR! lines" {
        val lines = listOf("npm ERR! code ENOENT", "npm ERR! syscall open")
        val errors = NpmErrorParser.parse(lines)
        errors shouldHaveSize 2
        errors[0].message shouldBe "code ENOENT"
        errors[1].message shouldBe "syscall open"
        errors.all { it.severity == Severity.ERROR } shouldBe true
    }

    // ── GradleTaskParser ──────────────────────────────────────────────────────

    "GradleTaskParser parses FAILED task line" {
        val lines = listOf("> Task :compileKotlin FAILED", "> Task :test FAILED")
        val errors = GradleTaskParser.parse(lines)
        errors shouldHaveSize 2
        errors[0].message shouldBe "Task :compileKotlin FAILED"
        errors[1].message shouldBe "Task :test FAILED"
    }

    "GradleTaskParser ignores non-failed tasks" {
        val lines = listOf("> Task :compileKotlin", "> Task :test UP-TO-DATE")
        GradleTaskParser.parse(lines) shouldHaveSize 0
    }
})
