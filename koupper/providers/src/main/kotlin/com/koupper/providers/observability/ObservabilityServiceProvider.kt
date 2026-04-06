package com.koupper.providers.observability

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class ObservabilityServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(ObservabilityProvider::class, {
            LocalObservabilityProvider(
                sinkPath = env("OBSERVABILITY_SINK_FILE", required = false, default = ".koupper-observability.jsonl")
            )
        })
    }
}
