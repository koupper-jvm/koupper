/**
 * Parameterless Execution Test
 * 
 * Demonstrates the simplest form of execution. This is crucial for verifying that the 
 * Orchestrator routes the script correctly without requiring a parameter payload.
 * 
 * Command:
 *   koupper run examples/empty-execution.kts
 */
import com.koupper.octopus.annotations.Export

@Export
val ping: () -> Unit = {
    println("✅ PONG! The script executed successfully without any arguments.")
    println("This proves the ScriptRunner correctly maps zero-parameter closures.")
}
