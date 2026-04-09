package com.koupper.providers.command

import com.koupper.container.app
import io.kotest.core.spec.style.AnnotationSpec
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class CommandRunnerServiceProviderTest : AnnotationSpec() {
    @Test
    fun `should bind command runner provider`() {
        CommandRunnerServiceProvider().up()

        assertTrue {
            app.getInstance(CommandRunner::class) is DefaultCommandRunner
        }
    }

    @Test
    fun `should support dry run`() {
        val runner = DefaultCommandRunner()

        val result = runner.run(
            CommandRunRequest(
                shellCommand = "echo hello",
                dryRun = true
            )
        )

        assertTrue(result.dryRun)
        assertEquals(0, result.exitCode)
        assertEquals("dry-run", result.stdout)
    }

    @Test
    fun `run checked should fail on non-zero exit`() {
        val runner = DefaultCommandRunner()

        assertFailsWith<IllegalStateException> {
            runner.runChecked(
                CommandRunRequest(
                    shellCommand = "exit 7"
                )
            )
        }
    }
}
