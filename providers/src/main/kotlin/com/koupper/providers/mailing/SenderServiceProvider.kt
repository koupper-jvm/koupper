package com.koupper.providers.mailing

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class SenderServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerHtmlEmailSender()
    }

    private fun registerHtmlEmailSender() {
        app.bind(Sender::class, {
            SenderHtmlEmail()
        })
    }

    override fun topLevelFunctions(): Map<String, String> = mapOf(
        "mailing" to """
            import com.koupper.providers.mailing.Sender
            fun mailing(): Sender = com.koupper.container.app.getInstance(Sender::class)
        """.trimIndent()
    )
}
