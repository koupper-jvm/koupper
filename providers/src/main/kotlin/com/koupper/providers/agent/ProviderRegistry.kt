package com.koupper.providers.agent

enum class ProviderCapability {
    FAST,         // sub-second first token (Groq, Cerebras)
    LONG_CONTEXT, // >32k tokens context window
    CODE,         // strong at code generation
    TOOL_USE,     // native OpenAI-style function calling
    REASONING,    // multi-step complex tasks
    LOCAL         // runs on-device, never touches cloud
}

/**
 * Static description of a cloud inference provider.
 * [priority] — lower number = tried first.
 * [rateLimitPerMinute] — conservative estimate of free tier limit.
 * [envKeyName] — env var that holds the API key; if blank/absent the provider is disabled.
 */
data class ProviderConfig(
    val name: String,
    val baseUrl: String,
    val envKeyName: String,
    val defaultModel: String,
    val capabilities: Set<ProviderCapability>,
    val rateLimitPerMinute: Int = 30,
    val maxContextTokens: Int = 8_192,
    val supportsNativeTools: Boolean = true,
    val priority: Int = 100
)

object ProviderRegistry {

    // ── Free tiers, sorted by default priority ────────────────────────────────

    /** Groq — fastest free inference, great tool calling */
    val GROQ = ProviderConfig(
        name                = "groq",
        baseUrl             = "https://api.groq.com/openai/v1",
        envKeyName          = "GROQ_API_KEY",
        defaultModel        = "llama-3.1-8b-instant",
        capabilities        = setOf(ProviderCapability.FAST, ProviderCapability.TOOL_USE),
        rateLimitPerMinute  = 30,
        maxContextTokens    = 8_192,
        priority            = 10
    )

    /** Cerebras — extremely fast inference, free tier */
    val CEREBRAS = ProviderConfig(
        name                = "cerebras",
        baseUrl             = "https://api.cerebras.ai/v1",
        envKeyName          = "CEREBRAS_API_KEY",
        defaultModel        = "llama3.1-8b",
        capabilities        = setOf(ProviderCapability.FAST),
        rateLimitPerMinute  = 60,
        priority            = 20
    )

    /** Google Gemini Flash — 1M context, good for long document analysis */
    val GEMINI = ProviderConfig(
        name                = "gemini",
        baseUrl             = "https://generativelanguage.googleapis.com/v1beta/openai",
        envKeyName          = "GEMINI_API_KEY",
        defaultModel        = "gemini-1.5-flash",
        capabilities        = setOf(ProviderCapability.LONG_CONTEXT, ProviderCapability.TOOL_USE),
        rateLimitPerMinute  = 15,
        maxContextTokens    = 1_000_000,
        priority            = 30
    )

    /** Mistral — strong at code and reasoning */
    val MISTRAL = ProviderConfig(
        name                = "mistral",
        baseUrl             = "https://api.mistral.ai/v1",
        envKeyName          = "MISTRAL_API_KEY",
        defaultModel        = "mistral-small-latest",
        capabilities        = setOf(ProviderCapability.CODE, ProviderCapability.TOOL_USE, ProviderCapability.REASONING),
        rateLimitPerMinute  = 5,
        priority            = 40
    )

    /** OpenRouter — aggregates 50+ models, many with free tier */
    val OPENROUTER = ProviderConfig(
        name                = "openrouter",
        baseUrl             = "https://openrouter.ai/api/v1",
        envKeyName          = "OPENROUTER_API_KEY",
        defaultModel        = "meta-llama/llama-3.1-8b-instruct:free",
        capabilities        = setOf(ProviderCapability.FAST, ProviderCapability.TOOL_USE),
        rateLimitPerMinute  = 20,
        priority            = 50
    )

    /** All providers in default priority order */
    val all: List<ProviderConfig> = listOf(GROQ, CEREBRAS, GEMINI, MISTRAL, OPENROUTER)

    /** Providers with long-context capability, sorted by context window size */
    val longContext: List<ProviderConfig> = all
        .filter { ProviderCapability.LONG_CONTEXT in it.capabilities }
        .sortedByDescending { it.maxContextTokens }

    /** Providers with native tool calling */
    val toolCapable: List<ProviderConfig> = all
        .filter { it.supportsNativeTools }
}
