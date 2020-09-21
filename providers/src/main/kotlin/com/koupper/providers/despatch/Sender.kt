package com.koupper.providers.despatch

import com.koupper.providers.logger.Logger

interface Sender {
    fun configFromPath(configPath: String): Sender

    fun configFromUrl(configPath: String): Sender

    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean

    fun trackUsing(logger: Logger): Boolean
}