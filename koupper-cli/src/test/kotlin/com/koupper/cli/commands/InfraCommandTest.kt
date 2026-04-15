package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InfraCommandTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `apply should require auto approve`() {
        val command = InfraCommand { _, _, _ -> error("executor should not run") }

        val output = command.execute(".", "apply", "--json")
        val node = mapper.readTree(output)

        assertTrue(node.path("ok").asBoolean().not())
        assertEquals(2, node.path("exitCode").asInt())
    }

    @Test
    fun `drift should evaluate required only spec mismatches`() {
        val tempDir = Files.createTempDirectory("infra-command-test").toFile()
        val specFile = File(tempDir, "drift-spec.json")
        val observedFile = File(tempDir, "observed.json")

        specFile.writeText(
            """
            {
              "version": "1",
              "mode": "required_only",
              "checks": {
                "dynamo": { "tables": [ { "name": "users", "gsis": ["email-index"] } ] }
              }
            }
            """.trimIndent()
        )

        observedFile.writeText(
            """
            {
              "checks": {
                "dynamo": { "tables": [ { "name": "users", "gsis": [] } ] }
              }
            }
            """.trimIndent()
        )

        val command = InfraCommand { args, _, _ ->
            if (args.contains("-detailed-exitcode")) {
                com.koupper.cli.commands.infra.ExecResult(0, "", "", false)
            } else {
                com.koupper.cli.commands.infra.ExecResult(0, "", "", false)
            }
        }

        val output = command.execute(
            ".",
            "drift",
            "--spec=${specFile.absolutePath}",
            "--observed-file=${observedFile.absolutePath}",
            "--json"
        )

        val node = mapper.readTree(output)
        assertEquals(2, node.path("exitCode").asInt())
        assertTrue(node.path("artifacts").path("driftSpec").path("missing").isArray)
    }

    @Test
    fun `reconcile flag parser should reject invalid frontend backup mode`() {
        val command = ReconcileCommand { _, _, _ -> com.koupper.cli.commands.infra.ExecResult(0, "", "", false) }

        val output = command.execute(
            ".",
            "run",
            "--frontend-backup-mode=unknown",
            "--json"
        )

        val node = mapper.readTree(output)
        assertTrue(node.path("ok").asBoolean().not())
        assertEquals(2, node.path("exitCode").asInt())
    }
}
