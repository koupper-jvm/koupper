package com.koupper.shared.octopus

import com.koupper.shared.annotations.Export

data class ReflectionValidation(
    val exportCount: Int,
    val exportNames: List<String>,
    val annotations: Map<String, Map<String, String>>,
    val warnings: List<String> = emptyList()
)

fun validateAnnotationsViaReflection(compiledClass: Class<*>, regexAnnotations: Map<String, Map<String, Any?>>): ReflectionValidation {
    val warnings = mutableListOf<String>()

    val exportFields = compiledClass.declaredFields.filter { field ->
        field.isAnnotationPresent(Export::class.java)
    }
    val exportNames = exportFields.map { it.name }

    if (exportNames.size > 1) {
        warnings.add("[REFLECTION] Multiple @Export fields detected: ${exportNames.joinToString(", ")}. Regex reported: ${regexAnnotations.size} annotations.")
    } else if (exportNames.isEmpty()) {
        warnings.add("[REFLECTION] No @Export field found in compiled class. Regex may have misidentified the entrypoint.")
    }

    val annotationMap = mutableMapOf<String, Map<String, String>>()

    if (exportNames.size == 1) {
        val field = exportFields.first()
        for (ann in field.declaredAnnotations) {
            val name = ann.annotationClass.simpleName ?: continue
            if (name == "Export") continue

            val params = mutableMapOf<String, String>()
            try {
                for (method in ann.annotationClass.java.declaredMethods) {
                    if (method.parameterCount == 0 && method.name != "equals" && method.name != "hashCode" && method.name != "toString" && method.name != "annotationType") {
                        val value = method.invoke(ann)
                        if (value != null) {
                            params[method.name] = value.toString()
                        }
                    }
                }
            } catch (_: Exception) {
                // Skip unreadable annotation
            }
            if (params.isNotEmpty()) {
                annotationMap[name] = params
            }
        }

        // Cross-check regex annotations vs reflection
        for ((regexKey, regexParams) in regexAnnotations) {
            val reflParams = annotationMap[regexKey]
            if (reflParams == null) {
                warnings.add("[REFLECTION] Annotation @$regexKey found by regex but not present in compiled class.")
            } else {
                for ((k, v) in regexParams) {
                    val reflVal = reflParams[k]
                    if (reflVal != null && reflVal != v.toString()) {
                        warnings.add("[REFLECTION] @$regexKey.$k mismatch: regex='$v' reflection='$reflVal'")
                    }
                }
            }
        }
    }

    return ReflectionValidation(
        exportCount = exportNames.size,
        exportNames = exportNames,
        annotations = annotationMap,
        warnings = warnings
    )
}
