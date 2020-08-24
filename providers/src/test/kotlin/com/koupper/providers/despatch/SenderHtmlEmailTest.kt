package com.koupper.providers.despatch

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.logger.Logger
import io.mockk.*
import kotlin.test.assertTrue

class SenderHtmlEmailTest : AnnotationSpec() {
    @Test
    fun `should prepare the email sending object`() {
        val host = "smtp.mailtrap.io"
        val port = "2525"
        val userName = "76a2d71e7dfc6f"
        val password = "aded3b02e639e5"
        val targetEmail = "dosek17@gmail.com"
        val subject = "IMPORTANT"
        val message = "<i>Greetings!</i><br>"

        mockkStatic("javax.mail.Session")

        val htmlEmailSender = SenderHtmlEmail()
        htmlEmailSender.host = host
        htmlEmailSender.port = port
        htmlEmailSender.userName = userName
        htmlEmailSender.password = password
        htmlEmailSender.subject = subject
        htmlEmailSender.targetEmail = targetEmail
        htmlEmailSender.message = message
        htmlEmailSender.sendTo(targetEmail)

        val properties = htmlEmailSender.properties

        assertTrue {
            properties["mail.smtp.host"]?.equals(host)
            properties["mail.smtp.port"]?.equals(port)
            properties["mail.smtp.auth"]?.equals("true")
            properties["mail.smtp.starttls.enable"]?.equals("true")!!
        }
    }

    @Test
    fun `should set the properties using a file`() {
        val htmlEmailSender = SenderHtmlEmail().configUsing("notifications.env") as SenderHtmlEmail

        val properties = htmlEmailSender.properties

        assertTrue {
            properties["mail.smtp.host"]?.equals("smtp.mailtrap.io")
            properties["mail.smtp.port"]?.equals(2525)
            properties["mail.smtp.auth"]?.equals("true")
            properties["mail.smtp.starttls.enable"]?.equals("true")!!
        }
    }

    @Test
    fun `should invoke the start method for the logger object` () {
        val loggerMock = mockkClass(Logger::class)

        val isTracked = SenderHtmlEmail().trackUsing(loggerMock)

        assertTrue {
            isTracked
        }

        verify {
            loggerMock.log()
        }
    }
}