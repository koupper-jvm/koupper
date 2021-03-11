package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.files.FileHandler
import com.koupper.providers.files.FileHandlerImpl
import com.koupper.providers.files.FileServiceProvider
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class FileServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind file handler impl`() {
        FileServiceProvider().up()

        assertTrue {
            app.createInstanceOf(FileHandler::class) is FileHandlerImpl
        }
    }
}