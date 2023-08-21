package com.koupper.providers.mailing

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import com.koupper.os.env
import com.koupper.providers.files.TextFileHandler
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec
import java.net.URL

class SenderHtmlEmailTest : AnnotationSpec() {
    private lateinit var container: Container

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)
    }

    @Ignore
    @Test
    fun `should set the sender html email properties using a resource`() {
        val message = "Hi"

        val sender = SenderHtmlEmail()
        sender.subject("Verification code")
        sender.withContent("ni")
        sender.sendTo("jacob.gacosta@gmail.com")
    }
}