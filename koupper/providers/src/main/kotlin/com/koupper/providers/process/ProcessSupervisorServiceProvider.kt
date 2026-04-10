package com.koupper.providers.process

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class ProcessSupervisorServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(ProcessSupervisor::class, {
            LocalProcessSupervisor(
                storePath = env(
                    "PROCESS_SUPERVISOR_STORE_PATH",
                    required = false,
                    default = "${System.getProperty("user.home")}/.koupper/processes.json"
                ),
                logsDirectory = env(
                    "PROCESS_SUPERVISOR_LOG_DIR",
                    required = false,
                    default = "${System.getProperty("user.home")}/.koupper/process-logs"
                ),
                windowsShell = env("PROCESS_SUPERVISOR_SHELL_WINDOWS", required = false, default = "pwsh"),
                unixShell = env("PROCESS_SUPERVISOR_SHELL_UNIX", required = false, default = "bash")
            )
        })
    }
}
