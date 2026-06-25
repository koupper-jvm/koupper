package com.koupper.octopus.sandbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.koupper.container.app
import com.koupper.octopus.Octopus
import com.koupper.octopus.createDefaultConfiguration
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.size < 2) {
        System.err.println("Usage: SandboxWorkerKt <scriptPath> <context> [paramsJson]")
        exitProcess(1)
    }

    System.setProperty("koupper.sandbox.enabled", "false")

    val scriptPath = args[0]
    val context = args[1]
    val paramsString = if (args.size > 2) args[2] else "{}"

    createDefaultConfiguration()
    val octopus = Octopus(app)

    val paramsMap = jacksonObjectMapper().readValue<Map<String, Any>>(paramsString.replace("\\\"", "\""))
    val cliArgs = paramsMap.entries.joinToString(" ") { "--${it.key}=${it.value}" }

    octopus.runFromScriptFile<Any?>(
        context = context,
        scriptPath = scriptPath,
        params = cliArgs
    ) { output ->
        println(output ?: "")
        exitProcess(0)
    }
}
