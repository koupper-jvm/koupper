package com.koupper.providers.mailing

import java.util.*

interface Sender {
    fun from(address: String? = null, personal: String? = null): Sender
    fun sendTo(email: String? = null): Sender
    fun subject(subject: String? = null): Sender
    fun withContent(content: String, type: String = "text/html"): Sender
    fun addAttachment(filePath: String): Sender
    fun send(): Boolean
    fun properties(): Properties
}
