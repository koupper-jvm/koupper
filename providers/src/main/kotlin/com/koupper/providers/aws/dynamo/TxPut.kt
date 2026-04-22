package com.koupper.providers.aws.dynamo

data class TxPut(
    val tableName: String,
    val item: Map<String, Any?>
)