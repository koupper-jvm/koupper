import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.aws.dynamo.DynamoLocalAdmin

data class Input(
    val table: String,
    val email: String? = null,
    val truncate: Boolean = false,
    val keySchema: List<String> = listOf("id")
)

@Export
val check: (Input) -> Map<String, Any?> = { input ->
    val admin = app.getInstance(DynamoLocalAdmin::class)

    val exists = admin.tableExists(input.table)
    val emailCount = if (!input.email.isNullOrBlank()) admin.countByEmail(input.table, input.email) else null
    val total = admin.scanCount(input.table)
    val truncated = if (input.truncate) admin.truncateTable(input.table, input.keySchema) else null

    mapOf(
        "ok" to true,
        "table" to input.table,
        "exists" to exists,
        "emailCount" to emailCount,
        "total" to total,
        "truncated" to truncated
    )
}
