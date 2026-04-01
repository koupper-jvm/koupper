package com.koupper.providers.aws.dynamo

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.aws.s3.S3Client
import com.koupper.providers.aws.s3.S3ClientImpl

class AwsServiceProvider: ServiceProvider() {
    override fun up() {
        app.bind(
            DynamoClient::class, { DynamoClientImpl() }
        )
    }
}