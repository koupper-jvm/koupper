package com.koupper.providers.lsp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.collections.shouldHaveSize
import java.io.File

class LspParserTest : StringSpec({

    // ── parseRange ────────────────────────────────────────────────────────────

    "parseRange converts 0-based LSP positions to 1-based" {
        val range = mapOf(
            "start" to mapOf("line" to 4, "character" to 2),
            "end"   to mapOf("line" to 4, "character" to 10)
        )
        val result = LspParsers.parseRange(range)!!
        result.startLine   shouldBe 5
        result.startColumn shouldBe 3
        result.endLine     shouldBe 5
        result.endColumn   shouldBe 11
    }

    "parseRange returns null for null input" {
        LspParsers.parseRange(null) shouldBe null
    }

    "parseRange handles zero-zero position" {
        val range = mapOf(
            "start" to mapOf("line" to 0, "character" to 0),
            "end"   to mapOf("line" to 0, "character" to 0)
        )
        val result = LspParsers.parseRange(range)!!
        result.startLine shouldBe 1; result.startColumn shouldBe 1
    }

    // ── parseDiagnostic ───────────────────────────────────────────────────────

    "parseDiagnostic maps severity 1 → ERROR" {
        val obj = mapOf(
            "range"    to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                "end"   to mapOf("line" to 0, "character" to 5)),
            "severity" to 1,
            "message"  to "unresolved reference: foo"
        )
        val d = LspParsers.parseDiagnostic(obj, "file:///src/Main.kt")
        d.severity shouldBe LspSeverity.ERROR
        d.message  shouldBe "unresolved reference: foo"
        d.file     shouldBe "/src/Main.kt"
        d.line     shouldBe 1
        d.column   shouldBe 1
    }

    "parseDiagnostic maps severity 2 → WARNING" {
        val obj = mapOf(
            "range"    to mapOf("start" to mapOf("line" to 2, "character" to 4),
                                "end"   to mapOf("line" to 2, "character" to 8)),
            "severity" to 2,
            "message"  to "unused variable",
            "code"     to "W001"
        )
        val d = LspParsers.parseDiagnostic(obj, "file:///src/Foo.kt")
        d.severity shouldBe LspSeverity.WARNING
        d.code     shouldBe "W001"
        d.line     shouldBe 3
        d.column   shouldBe 5
    }

    "parseDiagnostic maps severity 3 → INFORMATION" {
        val obj = mapOf(
            "range"    to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                "end"   to mapOf("line" to 0, "character" to 1)),
            "severity" to 3,
            "message"  to "info"
        )
        LspParsers.parseDiagnostic(obj, "file:///f.kt").severity shouldBe LspSeverity.INFORMATION
    }

    "parseDiagnostic maps severity 4 → HINT" {
        val obj = mapOf(
            "range"    to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                "end"   to mapOf("line" to 0, "character" to 1)),
            "severity" to 4,
            "message"  to "hint"
        )
        LspParsers.parseDiagnostic(obj, "file:///f.kt").severity shouldBe LspSeverity.HINT
    }

    "parseDiagnostic defaults to ERROR for missing severity" {
        val obj = mapOf(
            "range"   to mapOf("start" to mapOf("line" to 0, "character" to 0),
                               "end"   to mapOf("line" to 0, "character" to 1)),
            "message" to "some error"
        )
        LspParsers.parseDiagnostic(obj, "file:///f.kt").severity shouldBe LspSeverity.ERROR
    }

    // ── parseHover ────────────────────────────────────────────────────────────

    "parseHover extracts plain string content" {
        LspParsers.parseHover(mapOf("contents" to "fun main()"))!!.content shouldBe "fun main()"
    }

    "parseHover extracts MarkupContent value" {
        val result = mapOf("contents" to mapOf("kind" to "markdown", "value" to "```kotlin\nfun foo(): Int\n```"))
        LspParsers.parseHover(result)!!.content shouldBe "```kotlin\nfun foo(): Int\n```"
    }

    "parseHover extracts list of MarkedString" {
        val result = mapOf("contents" to listOf(
            mapOf("language" to "kotlin", "value" to "fun bar()"),
            "some docs"
        ))
        LspParsers.parseHover(result)!!.content shouldBe "fun bar()\nsome docs"
    }

    "parseHover returns null for null result" {
        LspParsers.parseHover(null) shouldBe null
    }

    "parseHover returns null for blank content" {
        LspParsers.parseHover(mapOf("contents" to "   ")) shouldBe null
    }

    "parseHover includes range when present" {
        val result = mapOf(
            "contents" to "val x: Int",
            "range"    to mapOf("start" to mapOf("line" to 9, "character" to 4),
                                "end"   to mapOf("line" to 9, "character" to 5))
        )
        val hover = LspParsers.parseHover(result)!!
        hover.startLine   shouldBe 10
        hover.startColumn shouldBe 5
    }

    // ── parseLocation ─────────────────────────────────────────────────────────

    "parseLocation extracts uri and range" {
        val obj = mapOf(
            "uri"   to "file:///home/user/Main.kt",
            "range" to mapOf("start" to mapOf("line" to 19, "character" to 3),
                             "end"   to mapOf("line" to 19, "character" to 7))
        )
        val loc = LspParsers.parseLocation(obj)!!
        loc.file   shouldBe "/home/user/Main.kt"
        loc.line   shouldBe 20
        loc.column shouldBe 4
    }

    "parseLocation handles targetUri / targetRange (LocationLink)" {
        val obj = mapOf(
            "targetUri"   to "file:///src/Foo.kt",
            "targetRange" to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                   "end"   to mapOf("line" to 0, "character" to 0))
        )
        LspParsers.parseLocation(obj) shouldNotBe null
    }

    "parseLocation returns null when uri missing" {
        LspParsers.parseLocation(mapOf("range" to mapOf<String, Any>())) shouldBe null
    }

    // ── parseLocations ────────────────────────────────────────────────────────

    "parseLocations handles null result" {
        LspParsers.parseLocations(null) shouldHaveSize 0
    }

    "parseLocations handles single Location object" {
        val obj = mapOf(
            "uri"   to "file:///A.kt",
            "range" to mapOf("start" to mapOf("line" to 0, "character" to 0),
                             "end"   to mapOf("line" to 0, "character" to 1))
        )
        LspParsers.parseLocations(obj) shouldHaveSize 1
    }

    "parseLocations handles Location array" {
        val locs = listOf(
            mapOf("uri"   to "file:///A.kt",
                  "range" to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                   "end"   to mapOf("line" to 0, "character" to 1))),
            mapOf("uri"   to "file:///B.kt",
                  "range" to mapOf("start" to mapOf("line" to 5, "character" to 2),
                                   "end"   to mapOf("line" to 5, "character" to 3)))
        )
        LspParsers.parseLocations(locs) shouldHaveSize 2
    }

    // ── LspBridgeProviderImpl error cases ─────────────────────────────────────

    "connect returns PROJECT_NOT_FOUND for non-existent directory" {
        val result = LspBridgeProviderImpl().connect(
            File("/no/such/dir"),
            LspBridgeProvider.kotlinLanguageServer()
        )
        (result as LspConnectResult.Err).code shouldBe LspConnectErrorCode.PROJECT_NOT_FOUND
    }

    "connect returns SERVER_NOT_FOUND when server binary missing" {
        val dir = kotlin.io.path.createTempDirectory("lsp-test-").toFile()
        val cfg = LspServerConfig(listOf("/no/such/lsp-server"), "kotlin")
        val result = LspBridgeProviderImpl().connect(dir, cfg)
        dir.deleteRecursively()
        (result as LspConnectResult.Err).code shouldBe LspConnectErrorCode.SERVER_NOT_FOUND
    }

    // ── handleNotification + diagFutures wiring ──────────────────────────────

    "handleNotification completes diagFuture with parsed diagnostics" {
        val session = LspSessionImpl.forTesting()
        val uri     = "file:///tmp/test.kt"
        val future  = java.util.concurrent.CompletableFuture<List<LspDiagnostic>>()
        session.diagFutures[uri] = future

        session.handleNotification(mapOf(
            "method" to "textDocument/publishDiagnostics",
            "params" to mapOf(
                "uri"         to uri,
                "diagnostics" to listOf(
                    mapOf(
                        "range"    to mapOf("start" to mapOf("line" to 0, "character" to 0),
                                            "end"   to mapOf("line" to 0, "character" to 3)),
                        "severity" to 1,
                        "message"  to "unresolved reference: foo"
                    )
                )
            )
        ))

        val diags = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        diags shouldHaveSize 1
        diags[0].message  shouldBe "unresolved reference: foo"
        diags[0].severity shouldBe LspSeverity.ERROR
        diags[0].line     shouldBe 1
    }

    "handleNotification with empty diagnostics completes future with empty list" {
        val session = LspSessionImpl.forTesting()
        val uri     = "file:///tmp/clean.kt"
        val future  = java.util.concurrent.CompletableFuture<List<LspDiagnostic>>()
        session.diagFutures[uri] = future

        session.handleNotification(mapOf(
            "method" to "textDocument/publishDiagnostics",
            "params" to mapOf("uri" to uri, "diagnostics" to emptyList<Any>())
        ))

        future.get(1, java.util.concurrent.TimeUnit.SECONDS) shouldHaveSize 0
    }

    "handleNotification with unknown method is a no-op" {
        val session = LspSessionImpl.forTesting()
        // should not throw
        session.handleNotification(mapOf("method" to "window/logMessage", "params" to mapOf("message" to "hello")))
    }
})
