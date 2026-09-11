package com.koupper.providers.hailovision

import com.koupper.container.app
import com.koupper.providers.ServiceProvider
import com.koupper.providers.ProviderTier

class HailoVisionServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.EXPERIMENTAL

    override fun up() {
        app.bind(HailoVisionProvider::class, { CliHailoVisionProvider() })
    }
}
