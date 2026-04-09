package com.koupper.providers.aws.deploy

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class AwsDeployServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(AwsDeployProvider::class, {
            AwsCliDeployProvider(
                awsCommand = env("AWS_COMMAND", required = false, default = "aws"),
                defaultRegion = env("AWS_REGION", required = false, default = "us-east-1"),
                timeoutSeconds = env("AWS_DEPLOY_TIMEOUT_SECONDS", required = false, default = "300").toLong()
            )
        })
    }
}
