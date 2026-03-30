/**
 * AI Email Responder Worker
 * 
 * Demonstrates advanced Koupper Dependency Injection via the global 'app' object alongside Event-Driven execution.
 * 
 * Features showcased:
 * - @JobsListener: Wakes up when JSON payloads land in the 'customer-support' queue.
 * - app.getInstance(): Injects instances from Koupper's DI container (AI and Mailing Providers).
 * - Custom Logger: Writes specifically to 'ai-support-[date].log'.
 */
import com.koupper.container.app
import com.koupper.logging.GlobalLogger.log
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.JobsListener
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.process.JobEvent
import com.koupper.providers.ai.AI
import com.koupper.providers.ai.ModelType
import com.koupper.providers.mailing.Sender

// Automatic deserialization from the Job payload
data class SupportTicket(
    val ticketId: String = "",
    val userEmail: String = "",
    val issueDescription: String = ""
)

@Export
@Logger(destination = "file:ai-support-[yyyy-MM-dd]", level = "INFO")
@JobsListener(debug = true, configId = "customer-support")
val processSupportTicket: (JobEvent, SupportTicket) -> Int = { event, ticket ->
    try {
        log.info { "🎟️ Received support ticket ${ticket.ticketId} from ${ticket.userEmail}" }
        
        // 1. Fetch singleton instances directly from the Framework!
        val aiProvider = app.getInstance(AI::class, tagName = "openai")
        val sender = app.getInstance(Sender::class)
        
        // 2. Do heavy lifting with the injected AI Provider
        val prompt = "A user says: '${ticket.issueDescription}'. Write a polite, empathetic 2-sentence response."
        val aiGeneratedResponse = aiProvider.prompt(ModelType.GPT4O, prompt, emptyMap())
        
        // 3. Chain with the Mail Provider
        sender.from(personal = "Koupper Automations")
              .sendTo(ticket.userEmail)
              .subject("Re: Your Support Ticket ${ticket.ticketId}")
              .withContent(aiGeneratedResponse)
              .send()
              
        log.info { "✅ Magic complete! AI Response emailed to ${ticket.userEmail}" }
        200 // Acknowledges the job completion successfully

    } catch (e: Exception) {
        log.error { "🚨 FAILED to process ticket ${ticket.ticketId}: ${e.message}" }
        500 // Shifts job to Failed list / Dead Letter Queue for retrying
    }
}
