package com.koupper.providers.aws.dynamo

import com.koupper.os.env
import com.koupper.providers.files.JsonFileHandlerImpl
import com.koupper.providers.files.toType
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.*
import java.net.URI

class DynamoClientImpl : DynamoClient {
    private val credentials: AwsBasicCredentials = AwsBasicCredentials.create("fakeMyKeyId", "fakeSecretAccessKey")

    private val dynamoDbClient: DynamoDbClient = DynamoDbClient.builder()
        .region(Region.US_EAST_2)
        .credentialsProvider(StaticCredentialsProvider.create(credentials))
        .endpointOverride(URI(env("DYNAMO_URL")))
        .build()

    override fun createTable(
        tableName: String,
        keySchema: List<Pair<String, String>>,
        attributeDefinitions: List<Pair<String, String>>,
        globalSecondaryIndexes: List<Map<String, Any>>?
    ) {
        // Convertir el esquema de claves a objetos del SDK de Amazon
        val keySchemaElements = keySchema.map {
            KeySchemaElement.builder()
                .attributeName(it.first) // nombre del atributo
                .keyType(KeyType.fromValue(it.second)) // tipo de clave (HASH o RANGE)
                .build()
        }

        // Convertir las definiciones de atributos a objetos del SDK de Amazon
        val attributeDefinitionElements = attributeDefinitions.map {
            AttributeDefinition.builder()
                .attributeName(it.first) // nombre del atributo
                .attributeType(ScalarAttributeType.fromValue(it.second)) // tipo de atributo (S o N)
                .build()
        }

        // Construir la solicitud de creación de tabla
        val createTableRequestBuilder = CreateTableRequest.builder()
            .tableName(tableName)
            .keySchema(keySchemaElements)
            .attributeDefinitions(attributeDefinitionElements)
            .provisionedThroughput(
                ProvisionedThroughput.builder()
                    .readCapacityUnits(5)
                    .writeCapacityUnits(5)
                    .build()
            )

        // Agregar índices secundarios globales si se proporcionan
        globalSecondaryIndexes?.let {
            val gsiElements = it.map { gsi ->
                // Obtener el esquema de claves y convertirlo correctamente
                val keySchema = (gsi["KeySchema"] as List<Map<String, String>>).map { key ->
                    KeySchemaElement.builder()
                        .attributeName(key["AttributeName"]!!) // nombre del atributo
                        .keyType(KeyType.fromValue(key["KeyType"]!!)) // tipo de clave
                        .build()
                }

                GlobalSecondaryIndex.builder()
                    .indexName(gsi["IndexName"] as String)
                    .keySchema(keySchema)
                    .projection(Projection.builder()
                        .projectionType(ProjectionType.ALL) // O ajusta según tus necesidades
                        .build())
                    .provisionedThroughput(ProvisionedThroughput.builder()
                        .readCapacityUnits(5) // Ajusta según tus necesidades
                        .writeCapacityUnits(5) // Ajusta según tus necesidades
                        .build())
                    .build()
            }
            createTableRequestBuilder.globalSecondaryIndexes(gsiElements)
        }

        // Construir la solicitud final
        val createTableRequest = createTableRequestBuilder.build()

        // Intentar crear la tabla
        try {
            dynamoDbClient.createTable(createTableRequest)
        } catch (e: Exception) {
            println("Error reating table: ${e.message}")
        }
    }

    override fun insertItem(tableName: String, jsonItem: String) {
        val mapper = JsonFileHandlerImpl<Map<String, Any?>>()

        val item: Map<String, Any?> = mapper.read(jsonItem).toType()

        this.insertItem(tableName, item)
    }

    override fun insertItem(tableName: String, item: Map<String, Any?>) {
        val attributeValues = mutableMapOf<String, AttributeValue>()

        item.forEach { (key, value) ->
            if (value != null) {
                attributeValues[key] = processValue(value)
            } else {
                println("Warning: Skipping key '$key' due to null value.")
            }
        }

        val putItemRequest = software.amazon.awssdk.services.dynamodb.model.PutItemRequest.builder()
            .tableName(tableName)
            .item(attributeValues)
            .build()

        dynamoDbClient.putItem(putItemRequest)
    }

    private fun processValue(value: Any): AttributeValue {
        return when (value) {
            is String -> AttributeValue.builder().s(value).build()
            is Number -> AttributeValue.builder().n(value.toString()).build()
            is Boolean -> AttributeValue.builder().bool(value).build()
            is List<*> -> AttributeValue.builder()
                .l(value.mapNotNull { it?.let { processValue(it) } }) // Procesa la lista, ignorando nulos
                .build()
            is Map<*, *> -> AttributeValue.builder()
                .m(processMap(value as Map<String, Any>)) // Asegúrate de que la clave sea String
                .build()
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.java.name}")
        }
    }

    private fun processMap(value: Map<String, Any>): Map<String, AttributeValue> {
        return value.mapValues { entry ->
            processValue(entry.value!!)
        }.filterValues { it != null }
    }

    override fun getItem(
        tableName: String,
        partitionKeyName: String?,
        partitionKeyValue: String?,
        sortKeyName: String?,
        sortKeyValue: String?,
    ): Map<String, Any>? {
        if (partitionKeyName == null || partitionKeyValue == null || sortKeyName == null || sortKeyValue == null) {
            throw IllegalArgumentException("partitionKey, sortKey, and its values are required for this query.")
        }

        val getItemRequest = GetItemRequest.builder()
            .tableName(tableName)
            .key(mapOf(
                partitionKeyName to AttributeValue.builder().s(partitionKeyValue).build(),
                sortKeyName to AttributeValue.builder().s(sortKeyValue).build()
            ))
            .build()

        val getItemResult = dynamoDbClient.getItem(getItemRequest)

        return if (getItemResult.hasItem()) {
            getItemResult.item()?.mapValues { convertAttributeValue(it.value) ?: "" }
        } else {
            null
        }
    }

    override fun getItems(
        tableName: String?,
        partitionKeyName: String?,
        partitionKeyValue: String?,
        gsiName: String?,
        filterExpression: String?,
        operator: String?,
        comparisonValue: String?
    ): List<Map<String, Any>>? {
        if (gsiName.isNullOrEmpty()) {
            throw IllegalArgumentException("The GSI name is required.")
        }

        // Construcción de la expresión de condición de clave
        val keyConditionExpression = if (operator != null && comparisonValue != null) {
            "$partitionKeyName $operator :pk"
        } else {
            "$partitionKeyName = :pk"
        }

        // Mapa para los valores de expresión de atributo
        val expressionAttributeValues = mutableMapOf(":pk" to AttributeValue.builder().s(partitionKeyValue).build())

        // Añadir el valor de comparación si se proporciona
        if (!comparisonValue.isNullOrEmpty()) {
            expressionAttributeValues[":filterValue"] = AttributeValue.builder().s(comparisonValue).build()
        }

        // Construcción del QueryRequest
        val queryRequestBuilder = QueryRequest.builder()
            .tableName(tableName)
            .indexName(gsiName)
            .keyConditionExpression(keyConditionExpression)
            .expressionAttributeValues(expressionAttributeValues)

        // Añadir la expresión de filtro si se proporciona
        if (!filterExpression.isNullOrEmpty()) {
            queryRequestBuilder.filterExpression(filterExpression)
        }

        val queryRequest = queryRequestBuilder.build()

        // Ejecución de la consulta
        val result = dynamoDbClient.query(queryRequest)

        return if (result.items().isNotEmpty()) {
            result.items().map { item ->
                item.mapValues { convertAttributeValue(it.value) ?: "" }
            }
        } else {
            null
        }
    }

    private fun convertAttributeValue(attributeValue: AttributeValue): Any? {
        return when {
            attributeValue.s() != null -> attributeValue.s() // String
            attributeValue.n() != null -> attributeValue.n() // Number
            attributeValue.bool() != null -> attributeValue.bool() // Boolean
            attributeValue.m() != null && attributeValue.m().isNotEmpty() -> attributeValue.m().mapValues { convertAttributeValue(it.value) } // Mapa
            attributeValue.l() != null && attributeValue.l().isNotEmpty() -> attributeValue.l().map { convertAttributeValue(it) } // Lista
            attributeValue.nul() -> null // Null
            else -> throw IllegalArgumentException("Tipo de valor no soportado: $attributeValue")
        }
    }

    override fun updateItem(
        tableName: String,
        key: Map<String, Any>,
        updateExpression: String,
        expressionAttributeValues: Map<String, Any>
    ) {
        TODO("Not yet implemented")
    }

    override fun deleteItem(tableName: String, key: Map<String, Any>) {
        TODO("Not yet implemented")
    }

    override fun doesTableExist(tableName: String): Boolean {
        return try {
            val request = DescribeTableRequest.builder()
                .tableName(tableName)
                .build()

            dynamoDbClient.describeTable(request)
            true
        } catch (e: DynamoDbException) {
            false
        }
    }

    override fun listTables(): List<String> {
        TODO("Not yet implemented")
    }

    override fun queryItems(
        tableName: String,
        keyConditionExpression: String,
        expressionAttributeValues: Map<String, Any>
    ): List<Map<String, Any>> {
        TODO("Not yet implemented")
    }

    override fun scanItems(tableName: String): List<Map<String, Any>> {
        TODO("Not yet implemented")
    }
}

