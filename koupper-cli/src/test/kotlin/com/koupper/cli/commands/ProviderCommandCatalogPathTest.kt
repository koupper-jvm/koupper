package com.koupper.cli.commands

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

class ProviderCommandCatalogPathTest {

    @Test
    fun `provider list should read catalog from configured system property path`() {
        val catalogFile = Files.createTempFile("koupper-provider-catalog", ".json")
        catalogFile.writeText(
            """
            {
              "version": "1.0",
              "providers": [
                {
                  "id": "command-runner",
                  "serviceProvider": "CommandRunnerServiceProvider",
                  "description": "Command execution provider",
                  "bindings": [],
                  "env": []
                }
              ]
            }
            """.trimIndent()
        )

        val property = "koupper.providers.catalog.path"
        val previousValue = System.getProperty(property)
        System.setProperty(property, catalogFile.toAbsolutePath().toString())

        try {
            val output = ProviderCommand().execute("koupper", "list")
            assertTrue(output.contains("command-runner (CommandRunnerServiceProvider)"), "Expected command-runner in provider list output")
        } finally {
            if (previousValue == null) {
                System.clearProperty(property)
            } else {
                System.setProperty(property, previousValue)
            }
        }
    }
}
