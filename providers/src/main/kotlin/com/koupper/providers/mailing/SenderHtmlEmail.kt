package com.koupper.providers.mailing

import com.koupper.os.env
import com.koupper.providers.Setup
import java.io.File
import java.util.*
import jakarta.activation.DataHandler
import jakarta.activation.FileDataSource
import jakarta.mail.*
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart

class SenderHtmlEmail : Sender, Setup() {
    private var fromAddress: String? = null
    private var fromPersonal: String? = null
    private var targetEmail: String? = null
    private var subject: String? = null
    private lateinit var session: Session
    private var properties: Properties = Properties()
    private val attachments: MutableList<File> = mutableListOf()
    private var content: String = ""
    private var contentType: String = "text/html"

    init { this.configMailProperties() }

    override fun from(address: String?, personal: String?): Sender {
        this.fromAddress = address ?: env("MAIL_USERNAME")
        this.fromPersonal = personal ?: env("EMAIL_FROM_NAME", "")
        return this
    }

    override fun sendTo(email: String?): Sender {
        this.targetEmail = email ?: env("DEFAULT_TARGET_EMAIL")
        return this
    }

    override fun subject(subject: String?): Sender {
        this.subject = subject ?: env("DEFAULT_SUBJECT", "noreply")
        return this
    }

    override fun withContent(content: String, type: String): Sender {
        this.content = content
        this.contentType = type
        return this
    }

    override fun addAttachment(filePath: String): Sender {
        val file = File(filePath)
        if (file.exists()) this.attachments.add(file)
        return this
    }

    override fun send(): Boolean {
        this.createSession()
        val message = this.buildMessage()
        Transport.send(message)
        return true
    }

    override fun properties(): Properties = this.properties

    private fun configMailProperties() {
        this.properties["mail.smtp.host"] = env("MAIL_HOST")
        this.properties["mail.smtp.port"] = env("MAIL_PORT")
        this.properties["mail.smtp.auth"] = "true"
        this.properties["mail.smtp.starttls.enable"] = "true"
        this.properties["mail.smtp.ssl.protocols"] = "TLSv1.2"
    }

    private fun createSession() {
        val authenticator = object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication {
                return PasswordAuthentication(env("MAIL_USERNAME"), env("MAIL_PASSWORD"))
            }
        }
        this.session = Session.getInstance(this.properties, authenticator)
    }

    private fun buildMessage(): MimeMessage {
        val message = MimeMessage(this.session)

        val fromEmail = fromAddress ?: env("MAIL_USERNAME")
        val fromName = fromPersonal ?: env("EMAIL_FROM_NAME", "")
        val from = if (fromName.isNotBlank()) {
            InternetAddress(fromEmail, fromName)
        } else {
            InternetAddress(fromEmail)
        }
        message.setFrom(from)

        val toEmail = targetEmail ?: env("DEFAULT_TARGET_EMAIL")
        message.setRecipient(Message.RecipientType.TO, InternetAddress(toEmail))

        message.subject = subject ?: env("DEFAULT_SUBJECT", "Nuevo mensaje")
        message.sentDate = Date()

        if (attachments.isEmpty()) {
            message.setContent(content, "$contentType; charset=UTF-8")
        } else {
            val multipart = MimeMultipart()

            val contentPart = MimeBodyPart()
            contentPart.setContent(content, "$contentType; charset=UTF-8")
            multipart.addBodyPart(contentPart)

            for (attachment in attachments) {
                val attachmentPart = MimeBodyPart()
                val dataSource = FileDataSource(attachment)
                attachmentPart.dataHandler = DataHandler(dataSource)
                attachmentPart.fileName = attachment.name
                multipart.addBodyPart(attachmentPart)
            }
            message.setContent(multipart)
        }

        return message
    }
}
