package io.kup.providers.despatch

import io.kup.container.app
import io.kup.providers.ServiceProvider

class SenderServiceProvider : ServiceProvider() {
    override fun up() {
        this.registerHtmlEmailSender()
    }

    private fun registerHtmlEmailSender() {
        app.bind(Sender::class, {
            SenderHtmlEmail()
        })
    }
}
