package com.koupper.providers.mailing

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.os.env
import com.koupper.providers.files.TextFileHandler
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import io.kotest.extensions.system.withEnvironment
import java.io.File
import java.net.URL

class SenderHtmlEmailTest : AnnotationSpec() {
    private lateinit var container: Container

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
    }

    private var envs: Map<String, String> = mapOf(
        "MAIL_DRIVER" to "smtp",
        "MAIL_HOST" to "smtpout.secureserver.net",
        "MAIL_PORT" to "587",
        "MAIL_USERNAME" to "contacto@igly.mx",
        "MAIL_PASSWORD" to "=?)&/_187Cvv",
        "ADMIN_EMAIL" to "jacob.gacosta@gmail.com"
    )

    @Ignore
    @Test
    fun `should set the sender html email properties using a resource`() {
        withEnvironment(envs) {
            val message = "Hi"

            val sender = SenderHtmlEmail()
            sender.subject("Verification code")
            sender.withContent(URL("https://igly.s3.us-east-2.amazonaws.com/mailing/welcome/welcome.html").readText())
            sender.sendTo("jacob.gacosta@gmail.com")
        }
    }
}