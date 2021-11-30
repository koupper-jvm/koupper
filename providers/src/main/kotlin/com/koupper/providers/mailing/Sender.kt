package com.koupper.providers.mailing

import com.koupper.providers.logger.Logger
import java.util.*

interface Sender {
    fun configFrom(configPath: String): Sender

    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean

    fun subject(subject: String)

    fun properties(): Properties
}