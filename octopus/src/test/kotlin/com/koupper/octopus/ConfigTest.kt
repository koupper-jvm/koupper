package com.koupper.octopus

import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals

class ConfigTest : AnnotationSpec() {
    @Test
    fun `should add script for execution`() {
        val config = Config()

        config.runScript("/Users/jacobacosta/Code/koupper/octopus/src/test/resources/init.kts")

        assertEquals("/Users/jacobacosta/Code/koupper/octopus/src/test/resources/example.kts", config.listScripts()[0])
    }
}