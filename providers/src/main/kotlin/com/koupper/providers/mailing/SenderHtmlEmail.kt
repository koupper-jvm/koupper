package com.koupper.providers.mailing

import com.koupper.os.env
import com.koupper.providers.Setup
import java.io.File
import java.util.*
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.*
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

class SenderHtmlEmail : Sender, Setup() {
    private var from: String? = ""
    private var targetEmail: String = ""
    private var subject: String? = ""
    private var message: String = ""
    private lateinit var session: Session
    private var properties: Properties = Properties()
    private val attachments: MutableList<File> = mutableListOf()
    private var content: String = ""
    private var contentType: String = "text/html" // Default content type is HTML

    init {
        this.configMailProperties()
    }

    override fun withContent(content: String) {
        this.content = content
        this.contentType = contentType
    }

    override fun addAttachment(filePath: String) {
        val file = File(filePath)

        if (file.exists()) {
            this.attachments.add(file)
        }
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
        message.setFrom(InternetAddress(env("MAIL_USERNAME")))
        message.setRecipient(Message.RecipientType.TO, InternetAddress(this.targetEmail))
        message.subject = this.subject
        message.sentDate = Date()

        val multipart = MimeMultipart()

        // Add content as the first part
        val contentPart = MimeBodyPart()
        contentPart.setContent(content, contentType)
        multipart.addBodyPart(contentPart)

        // Add attachments
        for (attachment in attachments) {
            val attachmentPart = MimeBodyPart()
            val dataSource = FileDataSource(attachment)
            attachmentPart.dataHandler = DataHandler(dataSource)
            attachmentPart.fileName = attachment.name
            multipart.addBodyPart(attachmentPart)
        }

        message.setContent(multipart)

        return message
    }

    override fun properties(): Properties {
        return this.properties
    }
}
