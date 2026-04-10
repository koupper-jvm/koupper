package com.koupper.providers.locale2e

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.aws.dynamo.DynamoLocalAdmin
import com.koupper.providers.jobops.JobOps
import com.koupper.providers.process.ProcessSupervisor

class LocalE2EServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(LocalE2E::class, {
            DefaultLocalE2E(
                processSupervisor = app.getInstance(ProcessSupervisor::class),
                jobOps = app.getInstance(JobOps::class),
                dynamoLocalAdmin = app.getInstance(DynamoLocalAdmin::class)
            )
        })
    }
}
