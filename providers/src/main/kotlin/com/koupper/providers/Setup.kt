package com.koupper.providers

import com.koupper.os.setGlobalConfig
import com.koupper.providers.files.FileHandlerImpl

abstract class Setup {
    val fileHandler = FileHandlerImpl()

    fun configFrom(configPath: String): Setup {
        setGlobalConfig(this.fileHandler.load(configPath).path)

        return this
    }
}