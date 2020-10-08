package com.koupper.providers.dispatch

import com.koupper.providers.logger.Logger
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.extensions.splitKeyValue
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class SenderHtmlEmail : Sender {
    private var host: String? = ""
    private var port: String? = ""
    private var userName: String? = ""
    private var password: String? = ""
    private var targetEmail: String = ""
    private var subject: String = ""
    private var message: String = ""
    private lateinit var session: Session
    private var properties: Properties = Properties()
    private val parserEnvProperties = TextParserEnvPropertiesTemplate()
    private lateinit var text: String

    override fun configFromPath(configPath: String): Sender {
        this.parserEnvProperties.readFromPath(configPath)

        this.setup()

        return this
    }

    override fun configFromUrl(configPath: String): Sender {
        this.parserEnvProperties.readFromURL(configPath)

        this.setup()

        return this
    }

    override fun configFromResource(configPath: String): Sender {
        this.parserEnvProperties.readFromResource(configPath)

        this.setup()

        return this
    }

    private fun setup() {
        val values: Map<String?, String?> = this.parserEnvProperties.splitKeyValue("=".toRegex())

        this.host = values["MAIL_HOST"]
        this.port = values["MAIL_PORT"]
        this.userName = values["MAIL_USERNAME"]
        this.password = values["MAIL_PASSWORD"]
    }

    override fun withContent(content: String) {
        this.message = content
    }

    override fun sendTo(targetEmail: String): Boolean {
        this.targetEmail = targetEmail

        this.configMailProperties()

        this.createSession()

        val message = this.buildMessage()

        Transport.send(message)

        return true
    }

    override fun trackUsing(logger: Logger): Boolean {
        logger.log()

        return true
    }

    private fun configMailProperties() {
        this.properties["mail.smtp.host"] = this.host
        this.properties["mail.smtp.port"] = this.port
        this.properties["mail.smtp.auth"] = "true"
        this.properties["mail.smtp.starttls.enable"] = "true"
    }

    private fun createSession() {
        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(userName, password)
            }
        }

        this.session = Session.getInstance(this.properties, authenticator)
    }

    private fun buildMessage(): MimeMessage {
        val message = MimeMessage(this.session)
        message.setFrom(InternetAddress(this.userName))
        message.setRecipients(Message.RecipientType.TO, arrayOf(InternetAddress(this.targetEmail)))
        message.subject = this.subject
        message.sentDate = Date()
        message.setContent(this.message, "text/html")

        return message
    }

    override fun properties(): Properties {
        return this.properties
    }
}