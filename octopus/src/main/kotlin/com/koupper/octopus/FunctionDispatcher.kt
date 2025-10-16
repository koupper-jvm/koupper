package com.koupper.octopus

private enum class Kind { TERMINAL, SIDE_EFFECT }
private data class FamilyMeta(val kind: Kind, val priority: Int)

private val annotationsPriority: Map<String, FamilyMeta> = mapOf(
    "Logger"       to FamilyMeta(Kind.SIDE_EFFECT, 10),
    "JobsListener" to FamilyMeta(Kind.TERMINAL, 50),
    "Export"       to FamilyMeta(Kind.TERMINAL,   100)
)

data class DispatcherInputParams(
    val scriptContext: String,
    val scriptPath: String?,
    val annotations: Map<String, Map<String, Any?>> = emptyMap(),
    val functionName: String,
    val params: ParsedParams?,
    val sentence: String
)

typealias UnifiedResolver<T> = (DispatcherInputParams, (T) -> Unit) -> Unit

object FunctionDispatcher {
    private fun <T> getAnnotationsByHierarchy(
        scannedAnnotations: Map<String, Map<String, Any?>>
    ): LinkedHashMap<String, UnifiedResolver<T>> {
        val registry: Map<String, UnifiedResolver<T>> = buildSignatureResolvers()

        val orderedAnnoNames: List<String> = scannedAnnotations.keys
            .sortedBy { annotationsPriority[it]?.priority ?: Int.MAX_VALUE }

        val resolvedPairs: List<Pair<String, UnifiedResolver<T>>> =
            orderedAnnoNames.mapNotNull { ann ->
                registry[ann]?.let { ann to it }
            }

        val (sideEffects, terminals) = resolvedPairs.partition { (ann, _) ->
            annotationsPriority[ann]?.kind != Kind.TERMINAL
        }

        return LinkedHashMap<String, UnifiedResolver<T>>().apply {
            sideEffects.forEach { (ann, resolver) -> put(ann, resolver) }

            terminals.forEach { (ann, resolver) -> put(ann, resolver) }
        }
    }

    fun <T> dispatch(inputParams: DispatcherInputParams, result: (value: T) -> Unit) {
        val orderedByHierarchy: LinkedHashMap<String, UnifiedResolver<T>> =
            getAnnotationsByHierarchy(inputParams.annotations)

        var emitted: T? = null

        for ((ann, resolver) in orderedByHierarchy) {
            resolver(inputParams) { value ->
                if (emitted == null) emitted = value
            }

            if (emitted != null && annotationsPriority[ann]?.kind == Kind.TERMINAL) {
                break
            }
        }

        emitted?.let { result(it) }
            ?: error("No function annotated with @Export was found.")
    }
}
