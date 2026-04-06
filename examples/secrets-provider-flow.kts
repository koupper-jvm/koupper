/**
 * Secrets Provider Operations Demo
 *
 * Purpose:
 * - Execute one secrets operation per run using the Secrets provider.
 *
 * Typical runs:
 * - koupper run examples/secrets-provider-flow.kts '{"action":"put","key":"api-token","value":"abc123"}'
 * - koupper run examples/secrets-provider-flow.kts '{"action":"get","key":"api-token"}'
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.secrets.SecretsClient

data class Input(
    val action: String,
    val key: String,
    val value: String? = null
)

@Export
val secretsOps: (Input) -> Map<String, Any?> = { input ->
    val secrets = app.getInstance(SecretsClient::class)

    when (input.action.lowercase()) {
        "put" -> {
            secrets.put(input.key, input.value ?: error("value is required for put"))
            mapOf("ok" to true, "action" to "put", "key" to input.key)
        }

        "get" -> {
            mapOf("ok" to true, "action" to "get", "key" to input.key, "value" to secrets.get(input.key))
        }

        "get-json" -> {
            mapOf("ok" to true, "action" to "get-json", "key" to input.key, "value" to secrets.getJson(input.key))
        }

        "exists" -> {
            mapOf("ok" to true, "action" to "exists", "key" to input.key, "exists" to secrets.exists(input.key))
        }

        else -> {
            error("Unsupported action '${input.action}'. Use put, get, get-json, exists.")
        }
    }
}
