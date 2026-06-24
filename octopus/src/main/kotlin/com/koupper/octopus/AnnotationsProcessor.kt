package com.koupper.octopus

import com.koupper.logging.LogSpec
import com.koupper.logging.LoggerFactory
import com.koupper.logging.captureLogs
import com.koupper.logging.toStreamRoutingConfig
import com.koupper.logging.withScriptLogger
import com.koupper.octopus.annotations.JobsListenerCall
import com.koupper.octopus.annotations.JobsListenerSetup
import com.koupper.octopus.annotations.ReactiveSetup
import com.koupper.octopus.annotations.ScheduledSetup
import com.koupper.octopus.annotations.TimerSetup
import com.koupper.octopus.process.JobEvent
import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.*
import com.koupper.providers.io.TerminalContext
import com.koupper.shared.normalizeType
import com.koupper.shared.octopus.extractExportFunctionSignature
import com.koupper.shared.octopus.reflectExportSignature
import com.koupper.shared.octopus.validateAnnotationsViaReflection
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

    put("Scheduled") { diParams, _ ->
        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        val spec = finalSpec!!
        ScheduledSetup.attachLogSpec(spec)

        val functionSignature = extractExportFunctionSignature(diParams.sentence)
        val functionArgTypeNames = functionSignature?.parameterTypes ?: emptyList()
        val paramsJson = buildParamsJson(functionArgTypeNames, diParams.params?.positionals ?: emptyList(), diParams.params?.params ?: emptyMap(), diParams.params?.flags ?: emptySet())

        captureLogs<Any?>("Scripts.Dispatcher", spec) { logger ->
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
    }

    put("Pipeline") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        val spec = finalSpec!!
        ScheduledSetup.attachLogSpec(spec)

        val (result, _) = captureLogs<Any?>("Scripts.Dispatcher", spec) { logger ->
            withScriptLogger(logger, spec.mdc, spec.toStreamRoutingConfig()) {
                ScheduledSetup.run(
                    JobsListenerCall(
                        scriptContext = diParams.scriptContext,
                        scriptPath = diParams.scriptPath,
                        code = diParams.sentence,
                        functionName = diParams.functionName,
                        paramsJson = emptyMap(),
                        argTypes = emptyList(),
                        annotationParams = diParams.annotations["Pipeline"].orEmpty()
                    )
                ) { null }
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }

    put("OnQueueEmpty") { diParams, res ->
        if (finalSpec == null) finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        ReactiveSetup.attachLogSpec(finalSpec!!)
        val result = ReactiveSetup.runOnQueueEmpty(JobsListenerCall(
            scriptContext = diParams.scriptContext, scriptPath = diParams.scriptPath,
            code = diParams.sentence, functionName = diParams.functionName,
            paramsJson = emptyMap(), argTypes = emptyList(),
            annotationParams = diParams.annotations["OnQueueEmpty"].orEmpty()
        ))
        @Suppress("UNCHECKED_CAST") res(result as T)
    }

    put("OnJobFailed") { diParams, res ->
        if (finalSpec == null) finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        ReactiveSetup.attachLogSpec(finalSpec!!)
        val result = ReactiveSetup.runOnJobFailed(JobsListenerCall(
            scriptContext = diParams.scriptContext, scriptPath = diParams.scriptPath,
            code = diParams.sentence, functionName = diParams.functionName,
            paramsJson = emptyMap(), argTypes = emptyList(),
            annotationParams = diParams.annotations["OnJobFailed"].orEmpty()
        ))
        @Suppress("UNCHECKED_CAST") res(result as T)
    }

    put("OnFileCreated") { diParams, res ->
        if (finalSpec == null) finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        ReactiveSetup.attachLogSpec(finalSpec!!)
        val result = ReactiveSetup.runOnFileCreated(JobsListenerCall(
            scriptContext = diParams.scriptContext, scriptPath = diParams.scriptPath,
            code = diParams.sentence, functionName = diParams.functionName,
            paramsJson = emptyMap(), argTypes = emptyList(),
            annotationParams = diParams.annotations["OnFileCreated"].orEmpty()
        ))
        @Suppress("UNCHECKED_CAST") res(result as T)
    }

    put("OnAgentDown") { diParams, res ->
        if (finalSpec == null) finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        ReactiveSetup.attachLogSpec(finalSpec!!)
        val result = ReactiveSetup.runOnAgentDown(JobsListenerCall(
            scriptContext = diParams.scriptContext, scriptPath = diParams.scriptPath,
            code = diParams.sentence, functionName = diParams.functionName,
            paramsJson = emptyMap(), argTypes = emptyList(),
            annotationParams = diParams.annotations["OnAgentDown"].orEmpty()
        ))
        @Suppress("UNCHECKED_CAST") res(result as T)
    }

    put("Timer") { diParams, res ->
        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        TimerSetup.attachLogSpec(finalSpec!!)

        val result = TimerSetup.run(
            JobsListenerCall(
                scriptContext = diParams.scriptContext,
                scriptPath = diParams.scriptPath,
                code = diParams.sentence,
                functionName = diParams.functionName,
                paramsJson = emptyMap(),
                argTypes = emptyList(),
                annotationParams = diParams.annotations["Timer"].orEmpty()
            )
        ) { typeName ->
            when (typeName) {
                else -> null
            }
        }
        @Suppress("UNCHECKED_CAST")
        res(result as T)
    }

    put("Export") { diParams, res ->
        // Check @KoupperVersion before compilation
        val declaredVersion = diParams.annotations["KoupperVersion"]?.get("value") as? String
        if (declaredVersion != null && declaredVersion.isNotBlank()) {
            val current = Octopus.providerPreambleVersion
            if (!current.startsWith(declaredVersion)) {
                @Suppress("UNCHECKED_CAST")
                res("[ERR_VERSION_MISMATCH] Script expects v$declaredVersion but runtime is v$current. Update the script's @KoupperVersion or downgrade Koupper." as T)
                return@put
            }
        }

        var backend: ScriptingHostBackend? = null
        if (diParams.callable == null) {
            backend = ScriptingHostBackend(extraClasspath = resolveGradleBuildClasspath(File(diParams.scriptContext)))
            
            val preamble = Octopus.providerPreamble
            // Injecting the preamble into scripts that don't use provider shortcuts triggers a
            // K2/FIR NPE (source must not be null) in FirJvmModuleAccessibilityTypeChecker.
            // Only inject when the script actually references provider symbols.
            // Only inject preamble when the script uses provider shortcuts.
            // Precise check: "koupper.<identifier>" but NOT inside import/package lines
            // or path strings (e.g. import com.koupper.*, "/path/.koupper/...").
            val usesProviders = diParams.sentence.let { src ->
                Regex("""(?:^|[^./\w])koupper\.\w""").containsMatchIn(src) ||
                Regex("""\blog\.""").containsMatchIn(src) ||
                Regex("""\benv\(""").containsMatchIn(src) ||
                Regex("""\bemit\(""").containsMatchIn(src) ||
                Regex("""\bKOUPPER_VERSION\b""").containsMatchIn(src)
            }
            val (finalPreamble, cleanSentence) = if (preamble.isNotBlank() && usesProviders) {
                val scriptImports = mutableSetOf<String>()
                val scriptLines = mutableListOf<String>()
                diParams.sentence.lines().forEach { line ->
                    if (line.trim().startsWith("import ")) {
                        scriptImports.add(line.trim())
                    } else {
                        scriptLines.add(line)
                    }
                }
                val allImports = (scriptImports + preamble.lines().filter { it.trim().startsWith("import ") }).sorted().joinToString("\n")
                val preambleBodies = preamble.lines().filter { !it.trim().startsWith("import ") }.joinToString("\n")
                
                allImports + "\n\n" + preambleBodies + "\n\n" to scriptLines.joinToString("\n")
            } else {
                "" to diParams.sentence
            }

            val augmentedScript = if (finalPreamble.isNotBlank()) {
                finalPreamble + "\n" + cleanSentence
            } else {
                diParams.sentence
            }

            val preambleLineCount = if (finalPreamble.isNotBlank()) {
                finalPreamble.lines().size + 1 // +1 for the "\n" separator
            } else 0
            
            backend.eval(augmentedScript, null, preambleLineCount)

            // Validate annotations via reflection (post-compile)
            backend.compiledClass?.let { cls ->
                val validation = validateAnnotationsViaReflection(cls, diParams.annotations)
                if (validation.warnings.isNotEmpty()) {
                    com.koupper.logging.GlobalLogger.log.warn {
                        "[ReflectionValidator] Warnings for ${diParams.scriptPath ?: diParams.functionName}: ${validation.warnings}"
                    }
                }
                if (validation.exportCount > 1) {
                    @Suppress("UNCHECKED_CAST")
                    res("[ERR_EXPORT_MULTIPLE] Multiple @Export fields detected via reflection: ${validation.exportNames.joinToString(", ")}. Use exactly one @Export entrypoint." as T)
                    return@put
                }
            }
        }

        if (finalSpec == null) {
            finalSpec = LogSpec(context = diParams.scriptContext, level = "DEBUG", destination = "console")
        }
        val spec = finalSpec!!

        val functionSignature = extractExportFunctionSignature(diParams.sentence)
        val reflectedSig = backend?.compiledClass?.let { cls ->
            reflectExportSignature(cls, diParams.functionName)
        }

        // Cross-validate regex vs reflection signatures. If they differ, prefer reflection
        // (it's reading actual compiled types) but log a warning so we can fix the regex.
        if (reflectedSig != null && functionSignature != null) {
            if (reflectedSig.parameterTypes != functionSignature.parameterTypes) {
                com.koupper.logging.GlobalLogger.log.warn {
                    "[SchemaExtractor] Regex/Reflection mismatch for ${diParams.functionName}: " +
                    "regex=${functionSignature.parameterTypes} reflection=${reflectedSig.parameterTypes}. " +
                    "Using reflection (more reliable)."
                }
            }
        }

        val effectiveSig = reflectedSig ?: functionSignature
        val functionArgTypeNames = effectiveSig?.parameterTypes ?: emptyList()
        val paramsJson = buildParamsJson(functionArgTypeNames, diParams.params?.positionals ?: emptyList(), diParams.params?.params ?: emptyMap(), diParams.params?.flags ?: emptySet())

        val hasSecret = diParams.annotations.containsKey("Secret")
        if (hasSecret) {
            val values = paramsJson.values.filter { it.isNotBlank() }.toSet()
            SecretRedactor.enable(values)
        }

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

        if (hasSecret) {
            SecretRedactor.disable()
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
