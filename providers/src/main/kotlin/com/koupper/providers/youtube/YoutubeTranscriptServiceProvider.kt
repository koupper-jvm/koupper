package com.koupper.providers.youtube

import com.koupper.container.app
import com.koupper.providers.ProviderTier
import com.koupper.providers.ServiceProvider

class YoutubeTranscriptServiceProvider : ServiceProvider() {
    override fun tier() = ProviderTier.EXPERIMENTAL

    override fun up() {
        app.bind(YoutubeTranscriptProvider::class, {
            YoutubeTimedTextClient()
        })
    }
}
