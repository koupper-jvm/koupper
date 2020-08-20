package io.kup.providers.despatch

import io.kup.providers.logger.Logger

interface Sender {
    fun configUsing(configPath: String): Sender

    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean

    fun trackUsing(logger: Logger): Boolean
}