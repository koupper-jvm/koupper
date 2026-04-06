package com.koupper.providers.docker

import com.koupper.container.app
import com.koupper.os.env
import com.koupper.providers.ServiceProvider

class DockerServiceProvider : ServiceProvider() {
    override fun up() {
        app.bind(DockerClient::class, {
            DockerCliClient(
                command = env("DOCKER_COMMAND", required = false, default = "docker"),
                host = env("DOCKER_HOST", required = false, default = "").ifBlank { null },
                dockerContext = env("DOCKER_CONTEXT", required = false, default = "").ifBlank { null },
                timeoutSeconds = env("DOCKER_TIMEOUT_SECONDS", required = false, default = "300").toLong(),
                workingDirectory = env("DOCKER_WORKDIR", required = false, default = ".")
            )
        })
    }
}
