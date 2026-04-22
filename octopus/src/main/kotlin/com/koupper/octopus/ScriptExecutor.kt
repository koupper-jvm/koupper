package com.koupper.octopus

import com.koupper.container.app
import com.koupper.octopus.entities.PipelineResult
import com.koupper.orchestrator.KouTask
import com.koupper.orchestrator.ScriptRunner
import com.koupper.shared.octopus.DependentFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.reflect.KFunction
import kotlin.reflect.KProperty0

data class PipelineStepReport(
    val index: Int,
    val script: Any,
    val ok: Boolean,
    val value: Any? = null,
    val error: Throwable? = null,
    val durationMs: Long
)

data class PipelineExecutionReport(
    val async: Boolean,
    val steps: List<PipelineStepReport>
) {
    val totalMs: Long = steps.sumOf { it.durationMs }
    val okCount: Int = steps.count { it.ok }
    val errorCount: Int = steps.count { !it.ok }
}

data class Callable(
    val property: KProperty0<*>,
    val args: Array<out Any?> = emptyArray()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Callable

        if (property != other.property) return false
        if (!args.contentEquals(other.args)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = property.hashCode()
        result = 31 * result + args.contentHashCode()
        return result
    }
}

/**
 * ScriptExecutor centraliza ejecución de scripts y funciones exportadas.
 *
 * NOTA IMPORTANTE:
 * - NO declares `call(() -> Unit)` junto con `call(() -> T)` en la misma interfaz sin @JvmName,
 *   porque en JVM chocan (type erasure). Por eso aquí solo dejamos el genérico.
 */
interface ScriptExecutor {

    companion object {

        @JvmStatic
        fun runPipeline(
            scripts: List<Any>,
            async: Boolean = false,
            onResult: (PipelineResult<Any?>) -> Unit
        ) {
            val executed = mutableMapOf<Any, Any?>()
            val pending = scripts.toMutableList()

            if (async && pending.any { it is DependentFunction }) {
                error("❌ Cannot run async pipeline with dependencies. Set async = false.")
            }

            val executor = app.getInstance(ScriptExecutor::class)

            // -------------------------
            // Async pipeline (no deps)
            // -------------------------
            if (async) {
                runBlocking {
                    val jobs = pending.mapIndexed { idx, script ->
                        async(Dispatchers.Default + SupervisorJob()) {
                            val start = System.nanoTime()
                            try {
                                val value = when (script) {
                                    is DependentFunction -> {
                                        val inputs = script.dependencies.map { executed[it] }
                                        executor.call(script.fn, listOf(inputs.lastOrNull()))
                                    }
                                    is KProperty0<*> -> {
                                        executor.call(script, emptyList<String>())
                                    }
                                    else -> script
                                }

                                PipelineStepReport(
                                    index = idx,
                                    script = script,
                                    ok = true,
                                    value = value,
                                    durationMs = (System.nanoTime() - start) / 1_000_000
                                )
                            } catch (t: Throwable) {
                                PipelineStepReport(
                                    index = idx,
                                    script = script,
                                    ok = false,
                                    error = t,
                                    durationMs = (System.nanoTime() - start) / 1_000_000
                                )
                            }
                        }
                    }

                    val steps = jobs.awaitAll()
                    onResult(PipelineResult.Ok(PipelineExecutionReport(async = true, steps = steps)))
                }
                return
            }

            // -------------------------
            // Sync pipeline (deps ok)
            // -------------------------
            val steps = mutableListOf<PipelineStepReport>()
            var idx = 0

            while (pending.isNotEmpty()) {
                val ready = pending.filter { s ->
                    s !is DependentFunction || s.dependencies.all { it in executed.keys }
                }

                if (ready.isEmpty()) {
                    val unresolved = pending.joinToString(", ") { it.toString() }
                    steps += PipelineStepReport(
                        index = idx++,
                        script = "DEPENDENCY_RESOLUTION",
                        ok = false,
                        error = IllegalStateException("⚠️ Unresolved dependencies: $unresolved"),
                        durationMs = 0
                    )
                    onResult(PipelineResult.Ok(PipelineExecutionReport(async = false, steps = steps)))
                    return
                }

                ready.forEach { script ->
                    val start = System.nanoTime()
                    try {
                        val value = when (script) {
                            is DependentFunction -> {
                                val inputs = script.dependencies.map { executed[it] }
                                executor.call(script.fn, inputs.lastOrNull())
                            }
                            is KProperty0<*> -> {
                                executor.call(script)
                            }

                            else -> {
                                script
                            }
                        }

                        executed[if (script is DependentFunction) script.fn else script] = value
                        pending.remove(script)

                        steps += PipelineStepReport(
                            index = idx++,
                            script = script,
                            ok = true,
                            value = value,
                            durationMs = (System.nanoTime() - start) / 1_000_000
                        )
                    } catch (t: Throwable) {
                        steps += PipelineStepReport(
                            index = idx++,
                            script = script,
                            ok = false,
                            error = t,
                            durationMs = (System.nanoTime() - start) / 1_000_000
                        )

                        // en sync: cortas al primer error (como tú lo traías)
                        onResult(PipelineResult.Ok(PipelineExecutionReport(async = false, steps = steps)))
                        return
                    }
                }
            }

            onResult(PipelineResult.Ok(PipelineExecutionReport(async = false, steps = steps)))
        }
    }

    // -------------------------------------------------------------------------
    // Engine-based execution (file/url/sentence)
    // -------------------------------------------------------------------------

    fun <T> runFromScriptFile(
        context: String,
        scriptPath: String,
        params: String = "",
        result: (value: T) -> Unit
    )

    fun <T> runFromCallback(
        callable: Callable,
        koTask: KouTask,
        result: (value: T) -> Unit
    )

    fun <T> runFromUrl(
        context: String,
        scriptUrl: String = "undefined",
        params: String = "",
        result: (value: T) -> Unit
    )

    fun <T> runScriptFiles(
        context: String,
        scripts: MutableMap<String, Map<String, Any>>,
        result: (value: T, scriptName: String) -> Unit
    )

    fun <T> run(
        context: String,
        scriptPath: String? = null,
        sentence: String,
        params: ParsedParams?,
        callable: Callable? = null,
        result: (value: T) -> Unit
    )

    fun <O> call(
        callable: KProperty0<*>,
        vararg args: Any?
    ): O
}
