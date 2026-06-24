package com.koupper.octopus

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ── Constants ──

internal const val OCTOPUS_HOST = "127.0.0.1"
internal const val OCTOPUS_PORT = 9998
internal const val OCTOPUS_HOST_PROPERTY = "koupper.octopus.host"
internal const val OCTOPUS_HOST_ENV = "KOUPPER_OCTOPUS_HOST"
internal const val OCTOPUS_PORT_PROPERTY = "koupper.octopus.port"
internal const val OCTOPUS_PORT_ENV = "KOUPPER_OCTOPUS_PORT"
internal const val OCTOPUS_AUTH_PROPERTY = "koupper.octopus.token"
internal const val OCTOPUS_AUTH_ENV = "KOUPPER_OCTOPUS_TOKEN"
internal const val OCTOPUS_ENABLE_URL_PROPERTY = "koupper.octopus.enableRunFromUrl"
internal const val OCTOPUS_ENABLE_URL_ENV = "KOUPPER_ENABLE_RUN_FROM_URL"
internal const val OCTOPUS_ALLOW_INSECURE_URL_PROPERTY = "koupper.octopus.allowInsecureRunFromUrl"
internal const val OCTOPUS_ALLOW_INSECURE_URL_ENV = "KOUPPER_ALLOW_INSECURE_RUN_FROM_URL"
internal const val OCTOPUS_DEPLOY_MAX_BYTES_PROPERTY = "koupper.octopus.deploy.maxBytes"
internal const val OCTOPUS_DEPLOY_MAX_BYTES_ENV = "KOUPPER_OCTOPUS_DEPLOY_MAX_BYTES"
internal const val OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES = 262144

// ── Enums ──

internal enum class ResponseMode { LEGACY, JSON }

internal enum class OutputStreamType { STDOUT, STDERR }

// ── Data types ──

private val daemonMapper = jacksonObjectMapper()
    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

internal data class DaemonRequest(
    val type: String? = null,
    val requestId: String? = null,
    val context: String? = null,
    val script: String? = null,
    val params: String? = null,
    val scriptContent: String? = null,
    val contentSha256: String? = null
)

internal data class DaemonResponse(
    val type: String,
    val requestId: String? = null,
    val level: String? = null,
    val message: String? = null,
    val result: String? = null,
    val error: String? = null
)

internal data class IncomingCommand(
    val mode: ResponseMode,
    val requestId: String? = null,
    val commandType: String,
    val context: String = "",
    val scriptPath: String = "",
    val params: String = "EMPTY_PARAMS",
    val scriptContent: String? = null,
    val contentSha256: String? = null
)

// ── Daemon metrics ──

internal data class DaemonMetricsSnapshot(
    val uptimeMs: Long,
    val activeConnections: Int,
    val totalConnections: Long,
    val totalCommands: Long,
    val totalScripts: Long,
    val successfulScripts: Long,
    val failedScripts: Long,
    val unauthorizedCommands: Long,
    val invalidCommands: Long
)

internal object DaemonMetrics {
    private val startedAt = System.currentTimeMillis()
    private val activeConnections = AtomicInteger(0)
    private val totalConnections = AtomicLong(0)
    private val totalCommands = AtomicLong(0)
    private val totalScripts = AtomicLong(0)
    private val successfulScripts = AtomicLong(0)
    private val failedScripts = AtomicLong(0)
    private val unauthorizedCommands = AtomicLong(0)
    private val invalidCommands = AtomicLong(0)

    fun onConnectionAccepted() {
        activeConnections.incrementAndGet()
        totalConnections.incrementAndGet()
    }

    fun onConnectionClosed() {
        val current = activeConnections.decrementAndGet()
        if (current < 0) activeConnections.set(0)
    }

    fun onCommandReceived() { totalCommands.incrementAndGet() }
    fun onUnauthorizedCommand() { unauthorizedCommands.incrementAndGet() }
    fun onInvalidCommand() { invalidCommands.incrementAndGet() }
    fun onScriptStarted() { totalScripts.incrementAndGet() }
    fun onScriptSucceeded() { successfulScripts.incrementAndGet() }
    fun onScriptFailed() { failedScripts.incrementAndGet() }

    fun snapshot() = DaemonMetricsSnapshot(
        uptimeMs = System.currentTimeMillis() - startedAt,
        activeConnections = activeConnections.get(),
        totalConnections = totalConnections.get(),
        totalCommands = totalCommands.get(),
        totalScripts = totalScripts.get(),
        successfulScripts = successfulScripts.get(),
        failedScripts = failedScripts.get(),
        unauthorizedCommands = unauthorizedCommands.get(),
        invalidCommands = invalidCommands.get()
    )
}

// ── Active executions ──

private data class ActiveExecution(
    val requestId: String,
    val thread: Thread,
    val scriptPath: String,
    val startedAt: Long = System.currentTimeMillis()
)

internal object ActiveExecutions {
    private val executions = ConcurrentHashMap<String, ActiveExecution>()

    fun register(requestId: String, scriptPath: String) {
        executions[requestId] = ActiveExecution(requestId, Thread.currentThread(), scriptPath)
    }

    fun unregister(requestId: String?) {
        if (requestId.isNullOrBlank()) return
        executions.remove(requestId)
    }

    fun cancel(requestId: String?): Boolean {
        if (requestId.isNullOrBlank()) return false
        val execution = executions[requestId] ?: return false
        execution.thread.interrupt()
        return true
    }
}

// ── JSON helpers ──

internal fun jsonEscape(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

internal fun jsonField(name: String, value: String?): String {
    return if (value == null) "\"$name\":null" else "\"$name\":\"${jsonEscape(value)}\""
}

internal fun daemonResponseJson(
    type: String,
    requestId: String? = null,
    level: String? = null,
    message: String? = null,
    result: String? = null,
    error: String? = null,
    errorCode: String? = null
): String {
    return "{" + listOf(
        jsonField("type", type),
        jsonField("requestId", requestId),
        jsonField("traceId", com.koupper.octopus.TraceContext.get().takeIf { it != "unknown" }),
        jsonField("level", level),
        jsonField("message", message),
        jsonField("result", result),
        jsonField("error", error),
        jsonField("errorCode", errorCode)
    ).joinToString(",") + "}"
}

internal fun structuredEvent(event: String, fields: Map<String, Any?> = emptyMap()): String {
    val pairs = mutableListOf(jsonField("event", event), jsonField("ts", System.currentTimeMillis().toString()))
    fields.forEach { (key, value) -> pairs += jsonField(key, value?.toString()) }
    return "{" + pairs.joinToString(",") + "}"
}

// ── Runtime config ──

internal fun optionalRuntimeSetting(propertyName: String, envName: String): String? {
    val fromProperty = System.getProperty(propertyName)?.trim()
    if (!fromProperty.isNullOrBlank()) return fromProperty
    val fromEnv = System.getenv(envName)?.trim()
    if (!fromEnv.isNullOrBlank()) return fromEnv
    return null
}

internal fun runtimeFlag(propertyName: String, envName: String, default: Boolean): Boolean {
    val raw = optionalRuntimeSetting(propertyName, envName) ?: return default
    return raw.equals("true", ignoreCase = true) || raw == "1" || raw.equals("yes", ignoreCase = true)
}

internal fun runtimeOctopusToken(): String? = optionalRuntimeSetting(OCTOPUS_AUTH_PROPERTY, OCTOPUS_AUTH_ENV)

internal fun runtimeOctopusHost(): String =
    optionalRuntimeSetting(OCTOPUS_HOST_PROPERTY, OCTOPUS_HOST_ENV) ?: OCTOPUS_HOST

internal fun runtimeOctopusPort(): Int =
    optionalRuntimeSetting(OCTOPUS_PORT_PROPERTY, OCTOPUS_PORT_ENV)?.toIntOrNull() ?: OCTOPUS_PORT

internal fun isRunFromUrlEnabled(): Boolean =
    runtimeFlag(OCTOPUS_ENABLE_URL_PROPERTY, OCTOPUS_ENABLE_URL_ENV, default = false)

internal fun isInsecureRunFromUrlAllowed(): Boolean =
    runtimeFlag(OCTOPUS_ALLOW_INSECURE_URL_PROPERTY, OCTOPUS_ALLOW_INSECURE_URL_ENV, default = false)

internal fun runtimeDeployMaxBytes(): Int {
    val value = optionalRuntimeSetting(OCTOPUS_DEPLOY_MAX_BYTES_PROPERTY, OCTOPUS_DEPLOY_MAX_BYTES_ENV)?.toIntOrNull()
        ?: OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES
    return if (value <= 0) OCTOPUS_DEFAULT_DEPLOY_MAX_BYTES else value
}

internal fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal val deployScriptNameRegex = Regex("^[A-Za-z0-9._-]+\\.(kts|kt)$")

internal fun isAllowedScriptUrl(scriptUrl: String): Boolean {
    val uri = runCatching { URI(scriptUrl) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme == "https") return true
    if (!isInsecureRunFromUrlAllowed()) return false
    if (scheme != "http") return false
    val host = uri.host?.lowercase() ?: return false
    return host == "127.0.0.1" || host == "localhost"
}

// ── Command parsing ──

internal fun parseJsonCommand(input: String): DaemonRequest? {
    return runCatching { daemonMapper.readValue(input, DaemonRequest::class.java) }.getOrNull()
}

internal fun parseAuthenticatedCommand(
    firstLine: String,
    reader: java.io.BufferedReader,
    requiredToken: String?
): Pair<Boolean, String?> {
    val trimmed = firstLine.trim()
    val tokenIsRequired = !requiredToken.isNullOrBlank()

    if (trimmed.startsWith("AUTH::")) {
        val providedToken = trimmed.removePrefix("AUTH::").trim()

        // JWT mode: if the token looks like a JWT (three base64url parts), validate via JwtAuth
        if (providedToken.count { it == '.' } == 2) {
            val jwt = com.koupper.octopus.security.JwtAuth.verifyToken(providedToken)
            return if (jwt != null) true to reader.readLine()?.trim() else false to null
        }

        // Legacy static token mode
        if (!tokenIsRequired) return true to reader.readLine()?.trim()
        return (providedToken == requiredToken) to (if (providedToken == requiredToken) reader.readLine()?.trim() else null)
    }

    if (tokenIsRequired) return false to null
    return true to trimmed
}

/**
 * Validates that the authenticated session has the required scope for the given command.
 * Returns true if authorized, false otherwise.
 */
internal fun validateCommandScope(token: String?, commandType: String): Boolean {
    if (token.isNullOrBlank()) return true // No token = no scope check (backward compat)

    // Only validate JWT tokens; legacy tokens bypass scope checks
    if (token.count { it == '.' } != 2) return true

    val jwt = com.koupper.octopus.security.JwtAuth.verifyToken(token) ?: return false
    val scopes = com.koupper.octopus.security.JwtAuth.extractScopes(jwt)
    return com.koupper.octopus.security.JwtAuth.isAuthorized(scopes, commandType)
}

internal fun parseIncomingCommand(command: String): IncomingCommand? {
    val trimmed = command.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("{")) {
        val req = parseJsonCommand(trimmed) ?: return null
        val type = req.type?.trim()?.uppercase() ?: "RUN"
        return when (type) {
            "UPDATING_CHECK" -> IncomingCommand(ResponseMode.JSON, req.requestId, "UPDATING_CHECK")
            "HEALTH", "HEALTH_CHECK" -> IncomingCommand(ResponseMode.JSON, req.requestId, "HEALTH_CHECK")
            "DEPLOY" -> IncomingCommand(ResponseMode.JSON, req.requestId, "DEPLOY", req.context?.trim().orEmpty(), req.script?.trim().orEmpty(), req.params?.trim().takeUnless { it.isNullOrBlank() } ?: "EMPTY_PARAMS", req.scriptContent, req.contentSha256?.trim())
            "CANCEL" -> IncomingCommand(ResponseMode.JSON, req.requestId, "CANCEL")
            "RELOAD_PROVIDERS" -> IncomingCommand(ResponseMode.JSON, req.requestId, "RELOAD_PROVIDERS")
            "WATCH" -> IncomingCommand(ResponseMode.JSON, req.requestId, "WATCH", req.context?.trim().orEmpty())
            else -> IncomingCommand(ResponseMode.JSON, req.requestId, "RUN", req.context?.trim().orEmpty(), req.script?.trim().orEmpty(), req.params?.trim().takeUnless { it.isNullOrBlank() } ?: "EMPTY_PARAMS")
        }
    }

    val inputData = tokenize(trimmed)
    if (inputData.isEmpty()) return null
    if (inputData[0] == "UPDATING_CHECK") return IncomingCommand(ResponseMode.LEGACY, commandType = "UPDATING_CHECK")
    if (inputData[0] == "HEALTH_CHECK") return IncomingCommand(ResponseMode.LEGACY, commandType = "HEALTH_CHECK")
    if (inputData[0] == "RELOAD_PROVIDERS") return IncomingCommand(ResponseMode.LEGACY, commandType = "RELOAD_PROVIDERS")

    val scriptContext = inputData[0].replace("\"", "")
    val scriptPath = if (inputData.size > 1) inputData[1].replace("\"", "") else ""
    val parameters = if (inputData.size > 2) inputData.drop(2).joinToString(" ") { if (it.contains(" ")) "\"$it\"" else it } else "EMPTY_PARAMS"

    return IncomingCommand(ResponseMode.LEGACY, commandType = "RUN", context = scriptContext, scriptPath = scriptPath, params = parameters)
}
