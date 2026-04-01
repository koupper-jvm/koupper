package com.koupper.providers.ssh

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class SSHServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(SSHClient::class, {
            OpenSSHClient(
                SSHConnectionConfig(
                    host = env("SSH_HOST"),
                    username = env("SSH_USER"),
                    port = env("SSH_PORT", required = false, default = "22").toInt(),
                    identityFile = env("SSH_IDENTITY_FILE", required = false, default = "").ifBlank { null },
                    strictHostKeyChecking = env("SSH_STRICT_HOST_KEY_CHECKING", required = false, default = "false").equals("true", ignoreCase = true),
                    connectTimeoutSeconds = env("SSH_CONNECT_TIMEOUT_SECONDS", required = false, default = "20").toInt(),
                    commandTimeoutSeconds = env("SSH_COMMAND_TIMEOUT_SECONDS", required = false, default = "120").toLong()
                )
            )
        })
    }
}
