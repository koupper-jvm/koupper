package com.koupper.providers.mailing

import com.koupper.providers.logger.Logger
import java.util.*

interface Sender {
    fun configFromPath(configPath: String): Sender

    fun configFromUrl(configPath: String): Sender

    fun configFromResource(configPath: String): Sender

    fun configFromEnvs(): Sender

    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean

    fun subject(subject: String)

    fun trackUsing(logger: Logger): Boolean

    fun properties(): Properties
}