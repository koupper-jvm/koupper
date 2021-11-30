package com.koupper.providers.mailing

import com.koupper.container.app
import com.koupper.container.interfaces.Container
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.AnnotationSpec

class SenderHtmlEmailTest : AnnotationSpec() {
    private lateinit var container: Container

    override fun beforeSpec(spec: Spec) {
        super.beforeSpec(spec)

        this.container = app
    }

    @Test
    fun `should set the sender html email properties using a resource`() {

    }
}