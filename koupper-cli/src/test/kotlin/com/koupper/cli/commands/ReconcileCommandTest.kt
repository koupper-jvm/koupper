package com.koupper.cli.commands

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.CopyOnWriteArrayList
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

    @Test
    fun `reconcile should export aws deploy controls into stage command`() {
        val executed = CopyOnWriteArrayList<String>()
        val command = ReconcileCommand { args, _, _ ->
            executed += args.joinToString(" ")
            com.koupper.cli.commands.infra.ExecResult(0, "ok", "", false)
        }

        command.execute(
            ".",
            "run",
            "--stages=deploy",
            "--deploy-command=deploy-step",
            "--aws-timeout-seconds=900",
            "--aws-retry-count=4",
            "--aws-retry-backoff-ms=800",
            "--frontend-backup-mode=incremental",
            "--json"
        )

        val fullCommand = executed.joinToString("\n")
        assertTrue(fullCommand.contains("AWS_DEPLOY_TIMEOUT_SECONDS"))
        assertTrue(fullCommand.contains("AWS_DEPLOY_RETRY_COUNT"))
        assertTrue(fullCommand.contains("AWS_DEPLOY_RETRY_BACKOFF_MS"))
        assertTrue(fullCommand.contains("AWS_FRONTEND_BACKUP_MODE"))
    }
}
