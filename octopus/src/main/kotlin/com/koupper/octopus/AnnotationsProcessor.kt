package com.koupper.octopus

import com.koupper.logging.LogSpec
import com.koupper.logging.captureLogs
import com.koupper.logging.withScriptLogger
import com.koupper.octopus.annotations.JobsListenerSetup
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature

fun <T> buildSignatureResolvers(): Map<String, UnifiedResolver<T>> = buildMap {
    var finalSpec: LogSpec? = null

    put("Logger") { diParams, _ ->
        val annParams = diParams.annotations["Logger"].orEmpty()

        finalSpec = LogSpec(
            level = (annParams["level"] as? String) ?: "DEBUG",
            destination = (annParams["destination"] as? String) ?: "console",
            mdc = mapOf(
                "script"      to (diParams.scriptPath ?: "unknown"),
                "export"      to diParams.functionName,
                "context"     to diParams.scriptContext
            ),
            async = when (val a = annParams["async"]) {
                is Boolean -> a
                is String  -> a.equals("true", ignoreCase = true)
                else       -> true
            }
        )
    }

    put("JobsListener") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(
                level       = "DEBUG",
                destination = "console",
                mdc         = mapOf(
                    "context"      to diParams.scriptContext,
                    "script"       to (diParams.scriptPath ?: "unknown"),
                    "functionName" to diParams.functionName
                ),
                async       = true
            )
        }

        val spec = finalSpec!!

        JobsListenerSetup.attachLogSpec(spec)

        val (result, _) = captureLogs<Any?>("Scripts.Dispatcher", spec) { logger ->
            withScriptLogger(logger, spec.mdc) {
                JobsListenerSetup.run(diParams)
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }

    put("Export") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(
                level       = "DEBUG",
                destination = "console",
                mdc         = mapOf(
                    "context"      to diParams.scriptContext,
                    "script"       to (diParams.scriptPath ?: "unknown"),
                    "functionName" to diParams.functionName
                ),
                async       = true
            )
        }

        val spec = finalSpec!!

        val functionSignature = extractExportFunctionSignature(diParams.sentence)

        val functionNameAndSignature = if (diParams.functionName.isNotEmpty() && functionSignature != null) {
            mapOf(diParams.functionName to functionSignature)
        } else emptyMap()

        val functionArgTypeNames: List<String> = functionNameAndSignature[diParams.functionName]?.first ?: emptyList()
        val positionals  = diParams.params?.positionals ?: emptyList()
        val kvParams     = diParams.params?.params ?: emptyMap()

        val paramsJson   = buildParamsJson(functionArgTypeNames, positionals, kvParams)

        val (result, _) = captureLogs<Any?>("Scripts.Dispatcher", spec) { logger ->
            withScriptLogger(logger, spec.mdc) {
                ScriptRunner.runScript(
                    ScriptCall(
                        code         = diParams.sentence,
                        functionName = diParams.functionName,
                        paramsJson   = paramsJson,
                        argTypes     = functionArgTypeNames
                    ),
                    diParams.backend
                ) { typeName ->
                    when (typeName.normalizeType()) {
                        "JobRunner"          -> JobRunner
                        "JobLister"          -> JobLister
                        "JobBuilder"         -> JobBuilder
                        "JobDisplayer"       -> JobDisplayer
                        "RoutesRegistration" -> RoutesRegistration(diParams.scriptContext)
                        "ModuleAnalyzer"     -> ModuleAnalyzer(diParams.scriptContext)
                        "ModuleProcessor"    -> ModuleProcessor(diParams.scriptContext)
                        else -> null
                    }
                }
            }
        }

        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }
}
