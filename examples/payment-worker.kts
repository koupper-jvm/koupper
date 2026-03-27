/**
 * Payment Processing Worker
 * 
 * Demonstrates Koupper's Event-Driven Background Worker system and strict JSON mapping capabilities.
 * 
 * Features showcased:
 * - @JobsListener: Tells Octopus to register this script as a listener. It sleeps until a Job payload is found in the 'payment-queue'.
 * - @Logger: Ensures robust error tracing in background contexts.
 * - Deep JSON Mapping: By simply declaring a Kotlin Data Class (PaymentPayload), Koupper automatically deserializes raw strings straight into typed POJOs.
 */
import com.koupper.octopus.annotations.Export
import com.koupper.octopus.annotations.Logger
import com.koupper.octopus.annotations.JobsListener
import com.koupper.octopus.process.JobEvent
import com.koupper.logging.GlobalLogger.log

// Strict Type Declaration
data class PaymentPayload(
    val transactionId: String = "",
    val amount: Double = 0.0,
    val currency: String = "USD",
    val customerEmail: String = ""
)

@Export
@Logger(destination = "file:payment-worker-[yyy-MM-dd]", level = "INFO")
@JobsListener(debug = true, configId = "payment-queue")
val processPayment: (JobEvent, PaymentPayload) -> Int = { event, payload ->
    log.info { "💳 Processing payment job [${event.jobId}] originating from context [${event.context}]" }
    
    try {
        if (payload.amount <= 0) {
            throw IllegalArgumentException("Charge amount must be strictly greater than zero.")
        }
        
        log.info { "✅ Successfully charged ${payload.amount} ${payload.currency} for ${payload.customerEmail} (Tx: ${payload.transactionId})" }
        
        200 // Return an HTTP-like success threshold to the Orchestrator
        
    } catch (e: Exception) {
        // Essential: Catching structural exceptions and passing them to the logger explicitly prevents silent worker failures
        log.error { "🚨 Payment failed for Tx ${payload.transactionId}: ${e.message}" }
        
        500 // Return a failure signal to shift the Job into the Dead Letter Queue/Failed Bin
    }
}
