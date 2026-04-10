import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.aws.dynamo.DynamoLocalAdmin
import com.koupper.providers.aws.dynamo.DynamoTableSpec

data class TableInput(
    val tableName: String,
    val keySchema: List<Pair<String, String>>,
    val attributeDefinitions: List<Pair<String, String>>
)

data class Input(
    val tables: List<TableInput> = emptyList()
)

@Export
val bootstrap: (Input) -> Map<String, Any?> = { input ->
    val admin = app.getInstance(DynamoLocalAdmin::class)
    val results = input.tables.map { table ->
        val created = admin.ensureTable(
            DynamoTableSpec(
                tableName = table.tableName,
                keySchema = table.keySchema,
                attributeDefinitions = table.attributeDefinitions
            )
        )
        mapOf(
            "table" to table.tableName,
            "created" to created
        )
    }

    mapOf(
        "ok" to true,
        "results" to results,
        "tables" to admin.listTables()
    )
}
