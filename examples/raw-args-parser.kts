/**
 * Raw Arguments Parser
 * 
 * Demonstrates how Koupper handles raw scalar types and multiple arguments linearly 
 * without necessarily requiring a complex JSON Data Class mapping.
 * 
 * Command:
 *   koupper run examples/raw-args-parser.kts '{"arg0": 404, "arg1": "NOT_FOUND"}'
 */
import com.koupper.octopus.annotations.Export

@Export
val logRawNetworkStatus: (Int, String) -> Unit = { statusCode, statusMessage ->
     println("🌐 Validating Network Packet...")
     
     when (statusCode) {
         in 200..299 -> println("✅ Response: $statusCode OK - $statusMessage")
         in 400..499 -> println("⚠️ Client Error: $statusCode - $statusMessage")
         in 500..599 -> println("🚨 Server Crash: $statusCode - $statusMessage")
         else -> println("❓ Unknown Format: $statusCode")
     }
}
