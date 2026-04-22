package com.koupper.providers.docker

data class DockerCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

data class DockerBuildRequest(
    val contextPath: String,
    val dockerfile: String? = null,
    val tag: String? = null,
    val buildArgs: Map<String, String> = emptyMap(),
    val noCache: Boolean = false
)

data class DockerRunRequest(
    val image: String,
    val name: String? = null,
    val detach: Boolean = true,
    val removeAfterExit: Boolean = false,
    val command: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val ports: List<String> = emptyList(),
    val volumes: List<String> = emptyList(),
    val workdir: String? = null
)

data class DockerComposeRequest(
    val file: String = "docker-compose.yml",
    val project: String? = null,
    val profiles: List<String> = emptyList(),
    val services: List<String> = emptyList(),
    val detached: Boolean = true
)

data class DockerExecRequest(
    val container: String,
    val command: List<String>,
    val workdir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val interactive: Boolean = false,
    val tty: Boolean = false
)

interface DockerClient {
    fun build(request: DockerBuildRequest): DockerCommandResult
    fun run(request: DockerRunRequest): DockerCommandResult
    fun stop(container: String): DockerCommandResult
    fun logs(container: String, tail: Int = 200): DockerCommandResult
    fun exec(request: DockerExecRequest): DockerCommandResult
    fun composeUp(request: DockerComposeRequest): DockerCommandResult
    fun composeDown(request: DockerComposeRequest): DockerCommandResult
    fun listContainers(all: Boolean = false): DockerCommandResult
}
