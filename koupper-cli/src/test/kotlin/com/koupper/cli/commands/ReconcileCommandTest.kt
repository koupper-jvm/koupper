package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReconcileCommandTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `reconcile should continue on error when configured`() {
        val command = ReconcileCommand { args, _, _ ->
            val joined = args.joinToString(" ")
            when {
                joined.contains("deploy-step") -> com.koupper.cli.commands.infra.ExecResult(1, "", "deploy failed", false)
                else -> com.koupper.cli.commands.infra.ExecResult(0, "ok", "", false)
            }
        }

        val output = command.execute(
            ".",
            "run",
            "--stages=infra,deploy,smoke",
            "--policy=continue_on_error",
            "--auto-approve",
            "--deploy-command=deploy-step",
            "--smoke-command=smoke-step",
            "--json"
        )

        val node = mapper.readTree(output)
        assertTrue(node.path("artifacts").path("stages").isArray)
        assertEquals("continue_on_error", node.path("artifacts").path("policy").asText())
    }
}
