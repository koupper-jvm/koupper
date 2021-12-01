package com.koupper.providers.mailing

import java.util.*

interface Sender {
    fun withContent(content: String)

    fun sendTo(targetEmail: String): Boolean

    fun subject(subject: String)

    fun properties(): Properties
}