package com.koupper.providers.git

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class GitServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(GitClient::class, {
            GitCliClient(
                command = env("GIT_COMMAND", required = false, default = "git"),
                timeoutSeconds = env("GIT_TIMEOUT_SECONDS", required = false, default = "120").toLong()
            )
        })
    }
}
