package com.koupper.providers.despatch

import com.koupper.providers.logger.Logger
import com.koupper.providers.parsing.TextParserEnvPropertiesTemplate
import com.koupper.providers.parsing.extensions.splitKeyValue
import java.net.URL
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class SenderHtmlEmail() : Sender {
    var host: String? = ""
    var port: String? = ""
    var userName: String? = ""
    var password: String? = ""
    var targetEmail: String = ""
    var subject: String = ""
    var message: String = ""
    lateinit var session: Session
    var properties: Properties = Properties()

    override fun configFromPath(configPath: String): Sender {
        val parserEnvProperties = TextParserEnvPropertiesTemplate()
        val content = parserEnvProperties.readFromPath(configPath)

        val properties: Map<String?, String?> = TextParserEnvPropertiesTemplate(content.toString()).splitKeyValue("=".toRegex())

        this.setupPropertiesFrom(properties)

        return this
    }

    override fun configFromUrl(configPath: String): Sender {
        val content = URL(configPath).readText()

        val properties: Map<String?, String?> = TextParserEnvPropertiesTemplate(content).splitKeyValue("=".toRegex())

        this.setupPropertiesFrom(properties)

        return this
    }

    private fun setupPropertiesFrom(properties: Map<String?, String?>) {
        this.host = properties["MAIL_HOST"]
        this.port = properties["MAIL_PORT"]
        this.userName = properties["MAIL_USERNAME"]
        this.password = properties["MAIL_PASSWORD"]
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
        properties["mail.smtp.host"] = this.host
        properties["mail.smtp.port"] = this.port
        properties["mail.smtp.auth"] = "true"
        properties["mail.smtp.starttls.enable"] = "true"
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
}
