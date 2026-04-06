package com.koupper.providers.queueops

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class QueueOpsServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(QueueOpsProvider::class, {
            LocalQueueOpsProvider(
                storePath = env("QUEUE_OPS_STORE_FILE", required = false, default = ".koupper-queue-ops.json")
            )
        })
    }
}
