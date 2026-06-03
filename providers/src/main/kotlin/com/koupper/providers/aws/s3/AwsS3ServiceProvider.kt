package com.koupper.providers.aws.s3

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class AwsS3ServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(S3Client::class, { S3ClientImpl() })
    }

    override fun externalDependencies() = listOf(
        "software.amazon.awssdk:s3:2.25.10",
        "software.amazon.awssdk:s3-presigner:2.25.10"
    )

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "s3" to """
            import com.koupper.providers.aws.s3.S3Client
            fun s3(): S3Client = com.koupper.container.app.getInstance(S3Client::class)
        """.trimIndent()
    )
}
