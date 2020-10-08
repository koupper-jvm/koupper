package com.koupper.providers.dispatch

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
}
