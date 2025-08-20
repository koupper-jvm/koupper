package com.koupper.octopus

import com.koupper.octopus.process.ModuleAnalyzer
import com.koupper.octopus.process.ModuleProcessor
import com.koupper.octopus.process.RoutesRegistration
import com.koupper.orchestrator.JobRunner
import com.koupper.shared.octopus.extractExportFunctionName
import javax.script.ScriptEngine

val signatureResolvers: Map<List<String>, (String, ScriptEngine, Map<String, Any>) -> Any> = mapOf(
    listOf("Map<String,Any>") to { sentence, engine, params ->
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (Map<String, Any>) -> Any
        fn(params)
    },
    listOf("ModuleProcessor") to { sentence, engine, params ->
        val processor = ModuleProcessor(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (com.koupper.octopus.process.Process) -> Any
        fn(processor)
    },
    listOf("ModuleProcessor", "Map<String,Any>") to { sentence, engine, params ->
        val processor = ModuleProcessor(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (com.koupper.octopus.process.Process, Map<String, Any>) -> Any
        fn(processor, params)
    },
    listOf("ModuleAnalyzer") to { sentence, engine, params ->
        val analyzer = ModuleAnalyzer(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (com.koupper.octopus.process.Process) -> Any
        fn(analyzer)
    },
    listOf("ModuleAnalyzer", "Map<String,Any>") to { sentence, engine, params ->
        val analyzer = ModuleAnalyzer(params["context"] as String, *(params["flags"] as Array<String>))
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (com.koupper.octopus.process.Process, Map<String, Any>) -> Any
        fn(analyzer, params)
    },
    listOf("RoutesRegistration") to { sentence, engine, params ->
        val routesRegistration = RoutesRegistration(params["context"] as String)
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (RoutesRegistration) -> Any
        fn(routesRegistration)
    },
    listOf("JobRunner") to { sentence, engine, params ->
        val runner = JobRunner
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as (JobRunner) -> Any
        fn(runner)
    },
    emptyList<String>() to { sentence, engine, _ ->
        engine.eval(sentence)
        val fn = engine.eval(extractExportFunctionName(sentence)!!) as () -> Any
        fn()
    }
)

