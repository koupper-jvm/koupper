package com.koupper.providers.lsp

import java.io.Closeable
import java.io.File

// ── Public data types ─────────────────────────────────────────────────────────

enum class LspSeverity { ERROR, WARNING, INFORMATION, HINT }

data class LspDiagnostic(
    val file: String,
    val line: Int,          // 1-based
    val column: Int,        // 1-based
    val endLine: Int,
    val endColumn: Int,
    val message: String,
    val severity: LspSeverity,
    val code: String? = null
)

data class LspHover(
    val content: String,
    val startLine: Int? = null,
    val startColumn: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null
)

data class LspLocation(
    val file: String,
    val line: Int,          // 1-based
    val column: Int         // 1-based
)

// Internal range used by parsers
internal data class LspRange(
    val startLine: Int,     // 1-based
    val startColumn: Int,
    val endLine: Int,
    val endColumn: Int
)

data class LspServerConfig(
    val command: List<String>,
    val languageId: String,
    val env: Map<String, String> = emptyMap()
)

// ── Connect result ────────────────────────────────────────────────────────────

enum class LspConnectErrorCode { SERVER_NOT_FOUND, PROJECT_NOT_FOUND, INIT_TIMEOUT, IO_ERROR }

sealed class LspConnectResult {
    data class Ok(val session: LspSession) : LspConnectResult()
    data class Err(val reason: String, val code: LspConnectErrorCode) : LspConnectResult()
}

// ── Session ───────────────────────────────────────────────────────────────────

interface LspSession : Closeable {
    /** Opens the file if needed, then waits up to [waitMs] for publishDiagnostics. */
    fun diagnostics(file: File, waitMs: Long = 5_000): List<LspDiagnostic>

    /** Returns hover info at the given 1-based position, or null if none. */
    fun hover(file: File, line: Int, column: Int): LspHover?

    /** Returns definition locations (1-based) for the symbol at the given position. */
    fun definition(file: File, line: Int, column: Int): List<LspLocation>

    override fun close()
}

// ── Provider ──────────────────────────────────────────────────────────────────

interface LspBridgeProvider {
    fun connect(
        projectDir: File,
        server: LspServerConfig,
        initTimeoutMs: Long = 10_000
    ): LspConnectResult

    companion object {
        /** Kotlin Language Server — looks for it in ~/.lsp/kotlin-language-server by default. */
        fun kotlinLanguageServer(
            serverPath: String = "${System.getProperty("user.home")}/.lsp/kotlin-language-server/server/bin/kotlin-language-server"
        ) = LspServerConfig(
            command  = listOf(serverPath),
            languageId = "kotlin"
        )

        /** typescript-language-server (must be on PATH: npm i -g typescript-language-server typescript) */
        fun typescriptLanguageServer() = LspServerConfig(
            command  = listOf("typescript-language-server", "--stdio"),
            languageId = "typescript"
        )

        /** Generic: any server that speaks LSP over stdio. */
        fun custom(command: List<String>, languageId: String, env: Map<String, String> = emptyMap()) =
            LspServerConfig(command, languageId, env)
    }
}
