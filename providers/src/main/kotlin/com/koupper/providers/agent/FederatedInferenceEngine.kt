package com.koupper.providers.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ── Per-provider runtime state ────────────────────────────────────────────────

private class ProviderState(val config: ProviderConfig, val engine: OpenAICompatibleEngine) {

    private val consecutiveFailures   = AtomicInteger(0)
    private val lastFailureMs         = AtomicLong(0)
    private val requestsThisWindow    = AtomicInteger(0)
    private val windowStartMs         = AtomicLong(System.currentTimeMillis())

    fun isAvailable(): Boolean {
        // Circuit-breaker: 3+ consecutive failures within 60 s → skip
        if (consecutiveFailures.get() >= 3 &&
            System.currentTimeMillis() - lastFailureMs.get() < 60_000) return false

        // Rate-limit: sliding 1-minute window
        val now = System.currentTimeMillis()
        if (now - windowStartMs.get() > 60_000) {
            windowStartMs.set(now)
            requestsThisWindow.set(0)
        }
        return requestsThisWindow.get() < config.rateLimitPerMinute
    }

    fun recordSuccess() {
        consecutiveFailures.set(0)
        requestsThisWindow.incrementAndGet()
    }

    fun recordFailure() {
        consecutiveFailures.incrementAndGet()
        lastFailureMs.set(System.currentTimeMillis())
    }

    val name: String get() = config.name
}

// ── Privacy classifier ────────────────────────────────────────────────────────

private val LOCAL_PATH_PATTERN  = Regex("""(/home/\w|C:\\Users\\|~/)""")
private val EXPLICIT_LOCAL_TAG  = Regex("""\[(PRIVATE|LOCAL|SENSITIVE)\]""", RegexOption.IGNORE_CASE)

private fun requiresLocalOnly(history: List<AgentMessage>): Boolean {
    val content = history.joinToString("\n") { it.content }
    return LOCAL_PATH_PATTERN.containsMatchIn(content) ||
           EXPLICIT_LOCAL_TAG.containsMatchIn(content)
}

// ── FederatedInferenceEngine ──────────────────────────────────────────────────

/**
 * Routes inference requests across multiple providers transparently.
 *
 * ## How it works
 * 1. **Privacy guard** — if the message contains local file paths or a `[PRIVATE]` tag,
 *    the request goes to [localEngine] only and never touches the network.
 * 2. **Provider selection** — cloud providers are tried in priority order
 *    (Groq → Cerebras → Gemini → Mistral → OpenRouter by default).
 * 3. **Rate-limit tracking** — each provider tracks requests within a 1-minute
 *    sliding window and is skipped when the limit is reached.
 * 4. **Circuit-breaker** — 3 consecutive failures within 60 s mark a provider as
 *    unavailable; it recovers automatically after the window expires.
 * 5. **Local fallback** — if every cloud provider is exhausted or unavailable,
 *    the request falls back to [localEngine] (llama.cpp or equivalent).
 *
 * ## Zero-config activation
 * Providers activate automatically when their API key env var is present:
 * - `GROQ_API_KEY`       → Groq  (llama-3.1-8b-instant)
 * - `CEREBRAS_API_KEY`   → Cerebras  (llama3.1-8b)
 * - `GEMINI_API_KEY`     → Gemini Flash  (1M context)
 * - `MISTRAL_API_KEY`    → Mistral Small
 * - `OPENROUTER_API_KEY` → OpenRouter free tier
 *
 * Override the model for any provider: `KOUPPER_LLM_MODEL_GROQ=llama-3.1-70b-versatile`
 *
 * ## Usage
 * ```
 * KOUPPER_LLM_PROVIDER=federated
 * GROQ_API_KEY=gsk_...
 * GEMINI_API_KEY=AIza...
 * # optionally:
 * KOUPPER_LLM_MODEL_PATH=/models/llama-3.1-8b.gguf   # enables local fallback
 * ```
 */
class FederatedInferenceEngine(
    private val localEngine: InferenceEngine? = null,
    providers: List<ProviderConfig> = ProviderRegistry.all
) : InferenceEngine {

    private val states: List<ProviderState> = providers
        .sortedBy { it.priority }
        .mapNotNull { config ->
            val key = System.getenv(config.envKeyName)?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val model = System.getenv("KOUPPER_LLM_MODEL_${config.name.uppercase()}")
                ?: config.defaultModel
            ProviderState(
                config = config,
                engine = OpenAICompatibleEngine(
                    baseUrl   = config.baseUrl,
                    apiKey    = key,
                    model     = model
                )
            )
        }

    init {
        val cloud = states.map { it.name }
        val local = if (localEngine != null) listOf("local") else emptyList()
        val all   = cloud + local
        if (all.isEmpty()) {
            println("[FederatedEngine] ⚠  No providers active. Set GROQ_API_KEY, GEMINI_API_KEY, " +
                    "or KOUPPER_LLM_MODEL_PATH (local).")
        } else {
            println("[FederatedEngine] ◈  Active providers: ${all.joinToString(" → ")}")
        }
    }

    // ── InferenceEngine ───────────────────────────────────────────────────────

    override suspend fun <T : Any> predict(
        history: List<AgentMessage>,
        outputSchema: Class<T>?,
        listener: TokenListener?
    ): T = route(history) { engine ->
        engine.predict(history, outputSchema, listener)
    }

    override suspend fun predictWithTools(
        history: List<AgentMessage>,
        tools: List<ToolDefinition>
    ): NativeInferenceResult = route(history) { engine ->
        engine.predictWithTools(history, tools)
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private suspend fun <T> route(
        history: List<AgentMessage>,
        block: suspend (InferenceEngine) -> T
    ): T = withContext(Dispatchers.IO) {

        // Privacy guard — sensitive content never leaves the machine
        if (requiresLocalOnly(history)) {
            val local = localEngine ?: error(
                "[FederatedEngine] 🔒 Sensitive content detected but no local engine is configured. " +
                "Set KOUPPER_LLM_MODEL_PATH to enable local inference."
            )
            println("[FederatedEngine] 🔒 local  (sensitive content)")
            return@withContext block(local)
        }

        // Try cloud providers in priority order
        for (state in states) {
            if (!state.isAvailable()) {
                println("[FederatedEngine] ⏭  ${state.name} skipped (rate-limited or circuit open)")
                continue
            }

            val result = runCatching { block(state.engine) }

            if (result.isSuccess) {
                state.recordSuccess()
                println("[FederatedEngine] ☁  ${state.name}/${state.config.defaultModel}")
                return@withContext result.getOrThrow()
            }

            val reason = result.exceptionOrNull()?.message ?: "unknown error"
            state.recordFailure()
            println("[FederatedEngine] ✗  ${state.name} failed: $reason — trying next provider")
        }

        // All cloud providers exhausted → local fallback
        val local = localEngine ?: error(
            "[FederatedEngine] All providers exhausted and no local fallback configured. " +
            "Active providers: ${states.map { it.name }}. " +
            "Set KOUPPER_LLM_MODEL_PATH to enable local inference as fallback."
        )

        println("[FederatedEngine] 🏠 local  (all cloud providers exhausted)")
        block(local)
    }

    // ── Observability ─────────────────────────────────────────────────────────

    /** Returns a snapshot of each provider's current availability and request count. */
    fun status(): List<Map<String, Any>> = states.map { s ->
        mapOf(
            "provider"    to s.name,
            "model"       to s.config.defaultModel,
            "available"   to s.isAvailable(),
            "priority"    to s.config.priority,
            "rateLimit"   to s.config.rateLimitPerMinute,
            "capabilities" to s.config.capabilities.map { it.name }
        )
    }
}
