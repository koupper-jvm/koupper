package com.koupper.octopus

import com.koupper.logging.LogSpec
import com.koupper.logging.LoggerFactory
import com.koupper.logging.captureLogs
import com.koupper.logging.toStreamRoutingConfig
import com.koupper.logging.withScriptLogger
import com.koupper.octopus.annotations.JobsListenerCall
import com.koupper.octopus.annotations.JobsListenerSetup
import com.koupper.octopus.annotations.ScheduledSetup
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.providers.io.TerminalContext
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.runtime.ScriptingHostBackend
import java.io.File

fun <T> buildSignatureResolvers(): Map<String, UnifiedResolver<T>> = buildMap {
    var finalSpec: LogSpec? = null

    put("Logger") { diParams, _ ->
        val annParams = diParams.annotations["Logger"].orEmpty()
        finalSpec = LogSpec(
            context = diParams.scriptContext,
            level = (annParams["level"] as? String) ?: "DEBUG",
            destination = (annParams["destination"] as? String) ?: "console",
            stdoutLevel = (annParams["stdoutLevel"] as? String) ?: "INFO",
            stderrLevel = (annParams["stderrLevel"] as? String) ?: "ERROR",
            mdc = mapOf(
                "script" to (diParams.scriptPath ?: "unknown"),
                "export" to diParams.functionName,
                "context" to diParams.scriptContext
            ),
            async = when (val a = annParams["async"]) {
                is Boolean -> a
                is String -> a.equals("true", ignoreCase = true)
                else -> false
            }
        )
    }

    put("JobsListener") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(
                context = diParams.scriptContext,
                level = "DEBUG",
                destination = "console",
                mdc = mapOf(
                    "context" to diParams.scriptContext,
                    "script" to (diParams.scriptPath ?: "unknown"),
                    "export" to diParams.functionName
                ),
                async = true
            )
        }

        val spec = finalSpec!!
        JobsListenerSetup.attachLogSpec(spec)

        val functionSignature = extractExportFunctionSignature(diParams.sentence)
        val functionArgTypeNames = functionSignature?.parameterTypes ?: emptyList()
        val paramsJson = buildParamsJson(
            functionArgTypeNames,
            diParams.params?.positionals ?: emptyList(),
            diParams.params?.params ?: emptyMap(),
            diParams.params?.flags ?: emptySet()
        )

        // IMPORTANTE: Aquí NO usamos captureLogs para que el Dispatcher no mate el archivo
        val result = withScriptLogger(
            LoggerFactory.get("Scripts.Dispatcher"),
            spec.mdc,
            spec.toStreamRoutingConfig()
        ) {
            JobsListenerSetup.run(
                JobsListenerCall(
                    scriptContext = diParams.scriptContext,
                    scriptPath = diParams.scriptPath,
                    code = diParams.sentence,
                    functionName = diParams.functionName,
                    paramsJson = paramsJson,
                    argTypes = functionArgTypeNames,
                    annotationParams = diParams.annotations["JobsListener"].orEmpty()
                )
            ) { typeName ->
                when (typeName.normalizeType()) {
                    "Container", "app" -> com.koupper.container.app
                    "JobRunner" -> JobRunner
                    "JobEvent" -> JobEvent()
                    "JobLister" -> JobLister
                    "JobBuilder" -> JobBuilder
                    "JobDisplayer" -> JobDisplayer
                    "RoutesRegistration" -> RoutesRegistration(diParams.scriptContext)
                    "ModuleAnalyzer" -> ModuleAnalyzer(diParams.scriptContext)
                    "ModuleProcessor" -> ModuleProcessor(diParams.scriptContext)
                    else -> null
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }

    put("Scheduled") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        val spec = finalSpec!!
        ScheduledSetup.attachLogSpec(spec)

        val functionSignature = extractExportFunctionSignature(diParams.sentence)
        val functionArgTypeNames = functionSignature?.parameterTypes ?: emptyList()
        val paramsJson = buildParamsJson(functionArgTypeNames, diParams.params?.positionals ?: emptyList(), diParams.params?.params ?: emptyMap(), diParams.params?.flags ?: emptySet())

        val (result, _) = captureLogs<Any?>("Scripts.Dispatcher", spec) { logger ->
            withScriptLogger(logger, spec.mdc, spec.toStreamRoutingConfig()) {
                ScheduledSetup.run(
                    JobsListenerCall(
                        scriptContext = diParams.scriptContext,
                        scriptPath = diParams.scriptPath,
                        code = diParams.sentence,
                        functionName = diParams.functionName,
                        paramsJson = paramsJson,
                        argTypes = functionArgTypeNames,
                        annotationParams = diParams.annotations["Scheduled"].orEmpty()
                    )
                ) { typeName ->
                    when (typeName.normalizeType()) {
                        "Container", "app" -> com.koupper.container.app
                        "TerminalIO" -> TerminalContext.get()
                        "JobRunner" -> JobRunner
                        "JobLister" -> JobLister
                        "JobBuilder" -> JobBuilder
                        "JobDisplayer" -> JobDisplayer
                        "RoutesRegistration" -> RoutesRegistration(diParams.scriptContext)
                        "ModuleAnalyzer" -> ModuleAnalyzer(diParams.scriptContext)
                        "ModuleProcessor" -> ModuleProcessor(diParams.scriptContext)
                        else -> null
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }

    put("Export") { diParams, res ->
        var backend: ScriptingHostBackend? = null
        if (diParams.callable == null) {
            backend = ScriptingHostBackend(extraClasspath = resolveGradleBuildClasspath(File(diParams.scriptContext)))
            backend.eval(diParams.sentence)
        }

        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        val spec = finalSpec!!

        val functionSignature = extractExportFunctionSignature(diParams.sentence)
        val functionArgTypeNames = functionSignature?.parameterTypes ?: emptyList()
        val paramsJson = buildParamsJson(functionArgTypeNames, diParams.params?.positionals ?: emptyList(), diParams.params?.params ?: emptyMap(), diParams.params?.flags ?: emptySet())

        val (result, _) = captureLogs("Scripts.Dispatcher", spec) { logger ->
            withScriptLogger(logger, spec.mdc, spec.toStreamRoutingConfig()) {
                if (diParams.callable != null) {
                    return@captureLogs ScriptRunner.executeFunction(diParams.callable.property, diParams.callable.args.toList()) as T
                }

                ScriptRunner.runScript(
                    ScriptCall(
                        code = diParams.sentence,
                        functionName = diParams.functionName,
                        paramsJson = paramsJson,
                        argTypes = functionArgTypeNames,
                        symbol = backend?.getSymbol(diParams.functionName),
                        annotationParams = emptyMap(),
                        context = diParams.scriptContext,
                        scriptPath = diParams.scriptPath,
                        kind = "KTS",
                        className = backend?.lastScriptClassName
                    )
                ) { typeName ->
                    when (typeName.normalizeType()) {
                        "Container", "app" -> com.koupper.container.app
                        "TerminalIO" -> TerminalContext.get()
                        "JobRunner" -> JobRunner
                        "JobLister" -> JobLister
                        "JobBuilder" -> JobBuilder
                        "JobDisplayer" -> JobDisplayer
                        "RoutesRegistration" -> RoutesRegistration(diParams.scriptContext)
                        "ModuleAnalyzer" -> ModuleAnalyzer(diParams.scriptContext)
                        "ModuleProcessor" -> ModuleProcessor(diParams.scriptContext)
                        else -> null
                    }
                }
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }
}

fun resolveGradleBuildClasspath(projectDir: File = File(".")): List<File> {
    val candidates = listOf(
        // Primero busca el JAR (más estable)
        projectDir.resolve("build/libs").listFiles { f ->
            f.extension == "jar" && !f.name.endsWith("-sources.jar") && !f.name.endsWith("-javadoc.jar")
        }?.toList() ?: emptyList(),

        // Fallback a clases compiladas
        listOf(
            projectDir.resolve("build/classes/kotlin/main"),
            projectDir.resolve("build/classes/java/main"),
            projectDir.resolve("build/resources/main")
        ).filter { it.exists() }
    ).flatten()

    return candidates.filter { it.exists() }
}
