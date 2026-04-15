package com.koupper.providers.command

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class CommandRunnerServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(CommandRunner::class, {
            DefaultCommandRunner(
                defaultTimeoutSeconds = env("COMMAND_RUNNER_TIMEOUT_SECONDS", required = false, default = "300").toLong(),
                windowsShell = env("COMMAND_RUNNER_SHELL_WINDOWS", required = false, default = "pwsh"),
                unixShell = env("COMMAND_RUNNER_SHELL_UNIX", required = false, default = "bash")
            )
        })
    }
}
