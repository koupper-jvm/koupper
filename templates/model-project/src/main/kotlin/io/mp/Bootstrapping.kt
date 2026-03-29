package io.mp

import com.koupper.octopus.createDefaultConfiguration

private val processManager = createDefaultConfiguration()

fun main() {
    // Use the processManager to use default configurations and execute script files.

    /*
    #SCRIPT_EXAMPLE
    val out: Output = processManager.call(myScript, Input(payload = "any value"))
    println("OK script finished: ${out.message}")
    */

    /*
    #JOB_EXAMPLE
    ::myScript.asJob(Input("content")).dispatchToQueue()
    */

    /*
    #PIPELINE_EXAMPLE
    ScriptExecutor.runPipeline(
        listOf(
            ::script1,
            ::script2.dependsOn(::script1),
        ),
        async = false
    ) { result ->
        println("OK pipeline finished: $result")
    }
    */
}
