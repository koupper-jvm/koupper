import com.koupper.shared.validators.core.VError

fun errorsToMaps(errors: List<VError>): List<Map<String, Any>> =
    errors.map { e ->
        mapOf(
            "field" to e.field,
            "messages" to e.messages
        )
    }
