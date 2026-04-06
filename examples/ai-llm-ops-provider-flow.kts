import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.aillmops.AILlmOpsProvider
import com.koupper.providers.aillmops.ChatRequest
import com.koupper.providers.aillmops.EmbedRequest
import com.koupper.providers.aillmops.StructuredRequest
import com.koupper.providers.aillmops.ToolCallRequest

data class Input(
    val prompt: String = "Summarize Koupper in one sentence"
)

@Export
val aiOpsDemo: (Input) -> Map<String, Any?> = { input ->
    val aiOps = app.getInstance(AILlmOpsProvider::class)
    val chat = aiOps.chat(ChatRequest(input = input.prompt))
    val structured = aiOps.structured(StructuredRequest(input = input.prompt, schemaHint = "{summary:string}"))
    val embeddings = aiOps.embed(EmbedRequest(texts = listOf("alpha", "beta")))
    val reranked = aiOps.rerank("alpha", listOf("beta doc", "alpha doc"))
    val toolCall = aiOps.toolCall(ToolCallRequest(tool = "summarizer", arguments = mapOf("text" to input.prompt)))
    mapOf(
        "ok" to true,
        "chat" to chat,
        "structured" to structured,
        "embeddingCount" to embeddings.size,
        "rerankedTop" to reranked.firstOrNull(),
        "toolCall" to toolCall
    )
}
