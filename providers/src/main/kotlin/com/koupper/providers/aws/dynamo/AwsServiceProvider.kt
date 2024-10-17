package com.koupper.providers.aws.dynamo

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class AwsServiceProvider: ServiceProvider() {
    override fun up() {
        app.bind(
            DynamoClient::class, { DynamoClientImpl() }
        )
    }
}