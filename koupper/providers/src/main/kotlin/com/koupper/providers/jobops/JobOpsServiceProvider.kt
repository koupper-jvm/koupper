package com.koupper.providers.jobops

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class JobOpsServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(JobOps::class, { DefaultJobOps() })
    }
}
