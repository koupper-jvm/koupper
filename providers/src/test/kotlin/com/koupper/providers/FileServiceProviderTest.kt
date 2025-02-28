package com.koupper.providers

import com.koupper.container.app
import com.koupper.providers.files.*
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertTrue

class FileServiceProviderTest : AnnotationSpec() {
    init {
        FileServiceProvider().up()
    }

    @Test
    fun `should bind the text file handler impl`() {
        assertTrue {
            app.getInstance(TextFileHandler::class, "TextFileHandlerImpl") is TextFileHandlerImpl
        }
    }

    @Test
    fun `should bind the file handler impl`() {
        assertTrue {
            app.getInstance(FileHandler::class, "FileHandlerImpl") is FileHandlerImpl
        }
    }

    @Test
    fun `should bind the json file  handler impl`() {
        assertTrue {
            app.getInstance(JSONFileHandler::class) is JSONFileHandlerImpl<*>
        }
    }
}