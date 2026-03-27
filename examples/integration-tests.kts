/**
 * Koupper Integration Test Runner
 * 
 * Demonstrates how to use a single Master Script to dynamically orchestrate and execute 
 * other sibling scripts within the same environment, acting as an automated Test Suite.
 * 
 * Command:
 *   koupper run examples/integration-tests.kts
 */
import com.koupper.container.app
import com.koupper.octopus.ScriptExecutor
import com.koupper.octopus.annotations.Export
import java.io.File

@Export
val runAllExamples: () -> Unit = {
    println("🧪 Starting Koupper Integration Test Suite...")
    println("--------------------------------------------------")
    
    // 1. We inject the core engine's execution capabilities!
    val executor = app.getInstance(ScriptExecutor::class)
    
    val contextPath = System.getProperty("user.dir")
    
    // We target the synchronous scripts that don't sleep forever (like @Scheduled daemons do)
    val testQueue = mapOf(
        "examples/empty-execution.kts" to "",
        "examples/hello-world.kts" to "\"Integration_Bot\"",
        "examples/raw-args-parser.kts" to "200 \"OK\"",
        "examples/cli-report-generator.kts" to "{\"reportName\": \"Q3_Earnings\", \"region\": \"Americas\", \"items\": [{\"name\": \"Licencia\", \"value\": 99.0}]}"
    )
    
    var passed = 0
    var failed = 0
    
    testQueue.forEach { (scriptPath, args) ->
        val scriptName = File(scriptPath).name
        print("▶️ Executing [ $scriptName ] ... ")
        
        try {
            // 2. We trigger the engine programmatically simulating a CLI run
            executor.runFromScriptFile<Any>(contextPath, scriptPath, args) { result ->
                if (result is String && result.startsWith("Script error:")) {
                    throw RuntimeException(result)
                }
                println("✅ PASS")
                passed++
            }
        } catch (e: Exception) {
            println("❌ FAIL")
            println("   ↳ Exception: ${e.message}")
            failed++
        }
    }
    
    println("--------------------------------------------------")
    println("📊 Test Summary: $passed Passed | $failed Failed")
    
    if (failed > 0) {
        throw RuntimeException("Integration tests failed!") // Makes the master script fail
    }
}
