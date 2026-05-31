package com.koupper.providers.pdf

import com.koupper.container.app
import com.koupper.providers.ServiceProvider

class PDFReaderServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(PDFReaderProvider::class, {
            PDFBoxReaderProvider()
        })
    }
}
