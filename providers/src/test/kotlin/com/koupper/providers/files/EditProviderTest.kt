package com.koupper.providers.files

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.nio.file.Files

class EditProviderTest : StringSpec({

    fun tempFile(content: String): File =
        Files.createTempFile("edit-test-", ".kt").toFile().also { it.writeText(content) }

    val edit = EditProviderImpl()

    // ── replace ───────────────────────────────────────────────────────────────

    "replace - unique match rewrites file" {
        val f = tempFile("val x = 1\nval y = 2\n")
        val result = edit.replace(f, "val x = 1", "val x = 99")
        result.shouldBeInstanceOf<EditResult.Ok>()
        f.readText() shouldBe "val x = 99\nval y = 2\n"
    }

    "replace - preserves trailing newline" {
        val f = tempFile("foo\nbar\n")
        edit.replace(f, "foo", "baz")
        f.readText() shouldBe "baz\nbar\n"
    }

    "replace - no trailing newline preserved when absent" {
        val f = tempFile("foo\nbar")
        edit.replace(f, "foo", "baz")
        f.readText() shouldBe "baz\nbar"
    }

    "replace - returns STRING_NOT_FOUND when absent" {
        val f = tempFile("hello world\n")
        val result = edit.replace(f, "missing", "x")
        result shouldBe EditResult.Err("String not found in ${f.name}", EditErrorCode.STRING_NOT_FOUND)
    }

    "replace - returns NOT_UNIQUE when pattern appears more than once" {
        val f = tempFile("foo\nfoo\n")
        val result = edit.replace(f, "foo", "bar")
        result.shouldBeInstanceOf<EditResult.Err>()
        (result as EditResult.Err).code shouldBe EditErrorCode.NOT_UNIQUE
        f.readText() shouldBe "foo\nfoo\n" // file unchanged
    }

    "replace - replaceAll=true replaces every occurrence" {
        val f = tempFile("foo\nfoo\nfoo\n")
        val result = edit.replace(f, "foo", "bar", replaceAll = true)
        result shouldBe EditResult.Ok(occurrences = 3)
        f.readText() shouldBe "bar\nbar\nbar\n"
    }

    "replace - multiline old string works" {
        val f = tempFile("fun hello() {\n    println(\"hi\")\n}\n")
        edit.replace(f, "fun hello() {\n    println(\"hi\")\n}", "fun hello() {\n    println(\"hello\")\n}")
        f.readText() shouldBe "fun hello() {\n    println(\"hello\")\n}\n"
    }

    "replace - returns FILE_NOT_FOUND for missing file" {
        val result = edit.replace(File("/nonexistent/path.kt"), "x", "y")
        (result as EditResult.Err).code shouldBe EditErrorCode.FILE_NOT_FOUND
    }

    // ── view ─────────────────────────────────────────────────────────────────

    "view - returns requested lines as content" {
        val f = tempFile("line1\nline2\nline3\nline4\n")
        val result = edit.view(f, 2, 3)
        result shouldBe EditResult.Ok(content = "line2\nline3")
    }

    "view - single line" {
        val f = tempFile("a\nb\nc\n")
        val result = edit.view(f, 2, 2)
        result shouldBe EditResult.Ok(content = "b")
    }

    "view - out of range returns LINE_OUT_OF_RANGE" {
        val f = tempFile("a\nb\n")
        val result = edit.view(f, 1, 5)
        (result as EditResult.Err).code shouldBe EditErrorCode.LINE_OUT_OF_RANGE
    }

    // ── replaceLines ─────────────────────────────────────────────────────────

    "replaceLines - replaces middle range" {
        val f = tempFile("a\nb\nc\nd\n")
        edit.replaceLines(f, 2, 3, "X\nY")
        f.readText() shouldBe "a\nX\nY\nd\n"
    }

    "replaceLines - replaces first line" {
        val f = tempFile("a\nb\nc\n")
        edit.replaceLines(f, 1, 1, "Z")
        f.readText() shouldBe "Z\nb\nc\n"
    }

    "replaceLines - replaces last line" {
        val f = tempFile("a\nb\nc\n")
        edit.replaceLines(f, 3, 3, "Z")
        f.readText() shouldBe "a\nb\nZ\n"
    }

    "replaceLines - out of range returns LINE_OUT_OF_RANGE" {
        val f = tempFile("a\nb\n")
        val result = edit.replaceLines(f, 2, 5, "x")
        (result as EditResult.Err).code shouldBe EditErrorCode.LINE_OUT_OF_RANGE
    }

    // ── deleteLines ───────────────────────────────────────────────────────────

    "deleteLines - removes middle lines" {
        val f = tempFile("a\nb\nc\nd\n")
        edit.deleteLines(f, 2, 3)
        f.readText() shouldBe "a\nd\n"
    }

    "deleteLines - removes single line" {
        val f = tempFile("a\nb\nc\n")
        edit.deleteLines(f, 2, 2)
        f.readText() shouldBe "a\nc\n"
    }

    "deleteLines - removes all lines" {
        val f = tempFile("a\nb\nc\n")
        edit.deleteLines(f, 1, 3)
        f.readText() shouldBe "\n"
    }

    "deleteLines - out of range returns LINE_OUT_OF_RANGE" {
        val f = tempFile("a\nb\n")
        val result = edit.deleteLines(f, 0, 1)
        (result as EditResult.Err).code shouldBe EditErrorCode.LINE_OUT_OF_RANGE
    }
})
