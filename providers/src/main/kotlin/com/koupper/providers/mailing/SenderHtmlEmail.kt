package com.koupper.providers.mailing

import com.koupper.os.env
import com.koupper.providers.files.FileHandlerImpl
import java.io.File
import java.util.*
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

class SenderHtmlEmail : Sender {
    private var from: String? = ""
    private var targetEmail: String = ""
    private var subject: String? = ""
    private var message: String = ""
    private lateinit var session: Session
    private var properties: Properties = Properties()
    private val fileHandler = FileHandlerImpl()
    private lateinit var configs: File

    init {
        this.configMailProperties()
    }

    override fun configFrom(configPath: String): Sender {
        this.configs = this.fileHandler.load(configPath)

        return this
    }

    override fun withContent(content: String) {
        this.message = content
    }

    override fun sendTo(targetEmail: String): Boolean {
        this.targetEmail = targetEmail

        this.createSession()

        val message = this.buildMessage()

        Transport.send(message)

        return true
    }

    override fun subject(subject: String) {
        this.subject = subject
    }

    private fun configMailProperties() {
        this.properties["mail.smtp.host"] = env("MAIL_HOST")
        this.properties["mail.smtp.port"] = env("MAIL_PORT")
        this.properties["mail.smtp.ssl.enable"] = "true"
        this.properties["mail.smtp.auth"] = "true"
        this.properties["mail.smtp.starttls.enable"] = "true"
    }

    private fun createSession() {
        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(env("MAIL_USERNAME"), env("PASSWORD"))
            }
        }

        this.session = Session.getInstance(this.properties, authenticator)
    }

    private fun buildMessage(): MimeMessage {
        val message = MimeMessage(this.session)
        message.setFrom(InternetAddress(env("MAIL_USERNAME")))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(this.targetEmail))
        message.subject = this.subject
        message.sentDate = Date()
        message.setContent(this.message, "text/html")

        return message
    }

    override fun properties(): Properties {
        return this.properties
    }
}
