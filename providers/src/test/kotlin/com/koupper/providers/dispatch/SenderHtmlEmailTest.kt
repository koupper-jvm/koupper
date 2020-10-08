package com.koupper.providers.dispatch

import io.kotest.core.spec.style.AnnotationSpec
import com.koupper.providers.logger.Logger
import io.mockk.*
import kotlin.test.assertTrue

class SenderHtmlEmailTest : AnnotationSpec() {
    @Ignore
    @Test
    fun `should set the sender html email properties using a resource`() {
        val htmlEmailSender = SenderHtmlEmail().configFromResource("resourceOfEnv")

        val properties = htmlEmailSender.properties()

        assertTrue {
            properties["mail.smtp.host"]?.equals("host")
            properties["mail.smtp.port"]?.equals("port")
            properties["mail.smtp.auth"]?.equals("true")
            properties["mail.smtp.starttls.enable"]?.equals("true")!!
        }
    }

    @Ignore
    @Test
    fun `should set the sender html email properties using a file`() {
        val htmlEmailSender = SenderHtmlEmail().configFromPath("pathOfEnv")

        val properties = htmlEmailSender.properties()

        assertTrue {
            properties["mail.smtp.host"]?.equals("smtp.mailtrap.io")
            properties["mail.smtp.port"]?.equals(2525)
            properties["mail.smtp.auth"]?.equals("true")
            properties["mail.smtp.starttls.enable"]?.equals("true")!!
        }
    }

    @Ignore
    @Test
    fun `should set the sender html email properties using a url`() {
        val htmlEmailSender = SenderHtmlEmail().configFromUrl("urlOfEnv")

        val properties = htmlEmailSender.properties()

        assertTrue {
            properties["mail.smtp.host"]?.equals("smtp.mailtrap.io")
            properties["mail.smtp.port"]?.equals(2525)
            properties["mail.smtp.auth"]?.equals("true")
            properties["mail.smtp.starttls.enable"]?.equals("true")!!
        }
    }

    @Test
    fun `should invoke the log method for the logger object`() {
        val loggerMock = mockkClass(Logger::class)

        every {
            loggerMock.log()
        } just Runs

        val isTracked = SenderHtmlEmail().trackUsing(loggerMock)

        assertTrue {
            isTracked
        }

        verify { loggerMock.log() }
    }
}