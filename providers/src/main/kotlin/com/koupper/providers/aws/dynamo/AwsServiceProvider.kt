package com.koupper.providers.aws.dynamo

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.aws.s3.S3Client
import com.koupper.providers.aws.s3.S3ClientImpl

class AwsServiceProvider: ServiceProvider() {
    override fun up() {
        app.bind(DynamoLocalAdmin::class, { DynamoLocalAdminImpl(app.getInstance(DynamoClient::class)) })
        app.bind(
            DynamoClient::class, { DynamoClientImpl() }
        )
    }

    override fun externalDependencies() = listOf(
        "software.amazon.awssdk:dynamodb:2.25.10",
        "software.amazon.awssdk:netty-nio-client:2.25.10"
    )

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "dynamo" to """
            import com.koupper.providers.aws.dynamo.DynamoClient
            fun dynamo(): DynamoClient = com.koupper.container.app.getInstance(DynamoClient::class)
        """.trimIndent()
    )
}
