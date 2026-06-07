package com.koupper.providers.lsp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.File
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

class LspBridgeProviderImpl : LspBridgeProvider {

    private val mapper = ObjectMapper().registerKotlinModule()

    override fun connect(
        projectDir: File,
        server: LspServerConfig,
        initTimeoutMs: Long
    ): LspConnectResult {
        if (!projectDir.exists()) return LspConnectResult.Err(
            "Project directory not found: ${projectDir.absolutePath}",
            LspConnectErrorCode.PROJECT_NOT_FOUND
        )
        val executable = server.command.firstOrNull() ?: return LspConnectResult.Err(
            "Empty command in LspServerConfig", LspConnectErrorCode.SERVER_NOT_FOUND
        )
        if (!File(executable).exists() && !isOnPath(executable)) return LspConnectResult.Err(
            "LSP server not found: $executable",
            LspConnectErrorCode.SERVER_NOT_FOUND
        )
        return runCatching {
            val pb = ProcessBuilder(server.command)
                .directory(projectDir)
                .redirectErrorStream(false)
                .also { pb -> server.env.forEach { (k, v) -> pb.environment()[k] = v } }
            val process = pb.start()
            val rpc     = LspRpc(process.inputStream, process.outputStream, mapper)
            val session = LspSessionImpl(process, rpc, mapper, server.languageId, projectDir, initTimeoutMs)
            LspConnectResult.Ok(session)
        }.getOrElse { e ->
            val code = if (e is TimeoutException) LspConnectErrorCode.INIT_TIMEOUT else LspConnectErrorCode.IO_ERROR
            LspConnectResult.Err("Failed to connect to LSP server: ${e.message}", code)
        }
    }

    private fun isOnPath(name: String): Boolean =
        System.getenv("PATH")?.split(File.pathSeparator)
            ?.any { dir -> File(dir, name).exists() || File(dir, "$name.exe").exists() }
            ?: false
}

// ── Session ───────────────────────────────────────────────────────────────────

internal class LspSessionImpl private constructor(
    private val process: Process,
    private val rpc: LspRpc,
    private val mapper: ObjectMapper,
    private val languageId: String,
    private val projectDir: File,
    private val initTimeoutMs: Long,
    private val skipInit: Boolean,
    // Prevents GC from closing a blocking input pipe in forTesting()
    @Suppress("unused") private val keepAlive: Any? = null
) : LspSession {

    constructor(
        process: Process,
        rpc: LspRpc,
        mapper: ObjectMapper,
        languageId: String,
        projectDir: File,
        initTimeoutMs: Long
    ) : this(process, rpc, mapper, languageId, projectDir, initTimeoutMs, skipInit = false, keepAlive = null)

    private val idGen       = AtomicInteger(1)
    private val pending     = ConcurrentHashMap<Int, CompletableFuture<Map<String, Any?>>>()
    internal val diagFutures = ConcurrentHashMap<String, CompletableFuture<List<LspDiagnostic>>>()
    private val openUris: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    private val readerThread = Thread(::readLoop, "lsp-reader").also {
        it.isDaemon = true
        it.start()
    }

    init {
        if (!skipInit) doInitialize()
    }

    companion object {
        /**
         * Creates a session for unit tests — never connects to a real server.
         * The reader thread blocks on a PipedInputStream so it doesn't exit and
         * race against the test registering diagFutures.
         */
        internal fun forTesting(): LspSessionImpl {
            val mapper     = ObjectMapper().registerKotlinModule()
            val blockIn    = java.io.PipedInputStream()
            val keepAlive  = java.io.PipedOutputStream(blockIn)   // holds pipe open
            val rpc        = LspRpc(blockIn, java.io.ByteArrayOutputStream(), mapper)
            val process    = ProcessBuilder(
                if (System.getProperty("os.name").lowercase().contains("win"))
                    listOf("cmd", "/c", "pause") else listOf("true")
            ).start()
            return LspSessionImpl(process, rpc, mapper, "kotlin", File("."), 0,
                skipInit = true, keepAlive = keepAlive)
        }
    }

    // ── LspSession ────────────────────────────────────────────────────────────

    override fun diagnostics(file: File, waitMs: Long): List<LspDiagnostic> {
        val uri    = file.toURI().toString()
        val future = CompletableFuture<List<LspDiagnostic>>()
        diagFutures[uri] = future
        openOrChange(file)
        return runCatching { future.get(waitMs, TimeUnit.MILLISECONDS) }.getOrDefault(emptyList())
    }

    override fun hover(file: File, line: Int, column: Int): LspHover? {
        ensureOpen(file)
        val result = request("textDocument/hover", mapOf(
            "textDocument" to mapOf("uri" to file.toURI().toString()),
            "position"     to lspPosition(line, column)
        ))
        return LspParsers.parseHover(result["result"])
    }

    override fun definition(file: File, line: Int, column: Int): List<LspLocation> {
        ensureOpen(file)
        val result = request("textDocument/definition", mapOf(
            "textDocument" to mapOf("uri" to file.toURI().toString()),
            "position"     to lspPosition(line, column)
        ))
        return LspParsers.parseLocations(result["result"])
    }

    override fun close() {
        runCatching { request("shutdown", emptyMap<String, Any>(), timeoutMs = 3_000) }
        runCatching { notify("exit", emptyMap<String, Any>()) }
        process.destroyForcibly()
        readerThread.interrupt()
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val msg = runCatching { rpc.receive() }.getOrNull() ?: break
            val id  = (msg["id"] as? Number)?.toInt()
            if (id != null) {
                pending.remove(id)?.complete(msg)
            } else {
                handleNotification(msg)
            }
        }
        pending.values.forEach { it.completeExceptionally(RuntimeException("LSP server closed")) }
        diagFutures.values.forEach { it.complete(emptyList()) }
    }

    @Suppress("UNCHECKED_CAST")
    internal fun handleNotification(msg: Map<String, Any?>) {
        when (msg["method"] as? String) {
            "textDocument/publishDiagnostics" -> {
                val params  = msg["params"] as? Map<String, Any?> ?: return
                val uri     = params["uri"] as? String ?: return
                val rawList = params["diagnostics"] as? List<*> ?: emptyList<Any>()
                val diags   = rawList.filterIsInstance<Map<String, Any?>>()
                    .map { LspParsers.parseDiagnostic(it, uri) }
                diagFutures.remove(uri)?.complete(diags)
            }
        }
    }

    // ── Protocol ──────────────────────────────────────────────────────────────

    private fun doInitialize() {
        request("initialize", mapOf(
            "processId"    to ProcessHandle.current().pid().toInt(),
            "rootUri"      to projectDir.toURI().toString(),
            "capabilities" to mapOf(
                "textDocument" to mapOf(
                    "synchronization" to mapOf(
                        "dynamicRegistration" to false,
                        "willSave"            to false,
                        "didSave"             to false,
                        "willSaveWaitUntil"   to false
                    ),
                    "hover"              to mapOf("dynamicRegistration" to false,
                                                  "contentFormat" to listOf("plaintext", "markdown")),
                    "definition"         to mapOf("dynamicRegistration" to false),
                    "publishDiagnostics" to mapOf("relatedInformation" to false)
                )
            )
        ), initTimeoutMs)
        notify("initialized", emptyMap<String, Any>())
    }

    private fun request(method: String, params: Any?, timeoutMs: Long = 10_000): Map<String, Any?> {
        val id     = idGen.getAndIncrement()
        val future = CompletableFuture<Map<String, Any?>>()
        pending[id] = future
        rpc.send(buildMap {
            put("jsonrpc", "2.0")
            put("id",      id)
            put("method",  method)
            if (params != null) put("params", params)
        })
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun notify(method: String, params: Any) {
        rpc.send(mapOf("jsonrpc" to "2.0", "method" to method, "params" to params))
    }

    private fun ensureOpen(file: File) {
        if (file.toURI().toString() !in openUris) openFile(file)
    }

    private fun openOrChange(file: File) {
        val uri = file.toURI().toString()
        if (uri in openUris) {
            notify("textDocument/didChange", mapOf(
                "textDocument"   to mapOf("uri" to uri, "version" to 2),
                "contentChanges" to listOf(mapOf("text" to file.readText()))
            ))
        } else {
            openFile(file)
        }
    }

    private fun openFile(file: File) {
        val uri = file.toURI().toString()
        notify("textDocument/didOpen", mapOf(
            "textDocument" to mapOf(
                "uri"        to uri,
                "languageId" to languageId,
                "version"    to 1,
                "text"       to file.readText()
            )
        ))
        openUris.add(uri)
    }

    private fun lspPosition(line: Int, column: Int) =
        mapOf("line" to (line - 1), "character" to (column - 1))
}
