package com.koupper.providers.docker

import java.io.File
import java.util.concurrent.TimeUnit

class DockerCliClient(
    private val command: String = "docker",
    private val host: String? = null,
    private val dockerContext: String? = null,
    private val timeoutSeconds: Long = 300,
    private val workingDirectory: String = "."
) : DockerClient {

    override fun build(request: DockerBuildRequest): DockerCommandResult {
        val args = mutableListOf(command, "build")
        if (request.noCache) args += "--no-cache"
        if (!request.dockerfile.isNullOrBlank()) {
            args += listOf("-f", request.dockerfile)
        }
        if (!request.tag.isNullOrBlank()) {
            args += listOf("-t", request.tag)
        }
        request.buildArgs.forEach { (k, v) ->
            args += listOf("--build-arg", "$k=$v")
        }
        args += request.contextPath
        return run(args)
    }

    override fun run(request: DockerRunRequest): DockerCommandResult {
        val args = mutableListOf(command, "run")
        if (request.detach) args += "-d"
        if (request.removeAfterExit) args += "--rm"
        if (!request.name.isNullOrBlank()) args += listOf("--name", request.name)
        if (!request.workdir.isNullOrBlank()) args += listOf("-w", request.workdir)
        request.env.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
        request.ports.forEach { port -> args += listOf("-p", port) }
        request.volumes.forEach { volume -> args += listOf("-v", volume) }
        args += request.image
        args += request.command
        return run(args)
    }

    override fun stop(container: String): DockerCommandResult {
        return run(listOf(command, "stop", container))
    }

    override fun logs(container: String, tail: Int): DockerCommandResult {
        return run(listOf(command, "logs", "--tail", tail.toString(), container))
    }

    override fun exec(request: DockerExecRequest): DockerCommandResult {
        val args = mutableListOf(command, "exec")
        if (request.interactive) args += "-i"
        if (request.tty) args += "-t"
        if (!request.workdir.isNullOrBlank()) args += listOf("-w", request.workdir)
        request.env.forEach { (k, v) -> args += listOf("-e", "$k=$v") }
        args += request.container
        args += request.command
        return run(args)
    }

    override fun composeUp(request: DockerComposeRequest): DockerCommandResult {
        val args = composeBaseArgs(request)
        args += "up"
        if (request.detached) args += "-d"
        args += request.services
        return run(args)
    }

    override fun composeDown(request: DockerComposeRequest): DockerCommandResult {
        val args = composeBaseArgs(request)
        args += "down"
        args += request.services
        return run(args)
    }

    override fun listContainers(all: Boolean): DockerCommandResult {
        val args = mutableListOf(command, "ps")
        if (all) args += "-a"
        args += listOf("--format", "table {{.ID}}\t{{.Image}}\t{{.Names}}\t{{.Status}}")
        return run(args)
    }

    private fun composeBaseArgs(request: DockerComposeRequest): MutableList<String> {
        val args = mutableListOf(command, "compose", "-f", request.file)
        if (!request.project.isNullOrBlank()) args += listOf("-p", request.project)
        request.profiles.forEach { profile -> args += listOf("--profile", profile) }
        return args
    }

    private fun run(args: List<String>): DockerCommandResult {
        val processBuilder = ProcessBuilder(args)
        processBuilder.directory(File(workingDirectory))

        val env = processBuilder.environment()
        if (!host.isNullOrBlank()) {
            env["DOCKER_HOST"] = host
        }
        if (!dockerContext.isNullOrBlank()) {
            env["DOCKER_CONTEXT"] = dockerContext
        }

        val process = processBuilder.start()

        val stdoutReader = process.inputStream.bufferedReader()
        val stderrReader = process.errorStream.bufferedReader()

        val stdoutFuture = ThreadResult<String>()
        val stderrFuture = ThreadResult<String>()

        Thread { stdoutFuture.value = stdoutReader.readText() }.start()
        Thread { stderrFuture.value = stderrReader.readText() }.start()

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw IllegalStateException("Docker command timed out after ${timeoutSeconds}s: ${args.joinToString(" ")}")
        }

        val exitCode = process.exitValue()
        val stdout = stdoutFuture.await().trim()
        val stderr = stderrFuture.await().trim()

        return DockerCommandResult(
            command = args.joinToString(" "),
            exitCode = exitCode,
            stdout = stdout,
            stderr = stderr
        )
    }
}

private class ThreadResult<T> {
    @Volatile
    var value: T? = null

    fun await(timeoutMs: Long = 30_000): T {
        val start = System.currentTimeMillis()
        while (value == null) {
            if ((System.currentTimeMillis() - start) > timeoutMs) {
                throw IllegalStateException("Timed out waiting for process output")
            }
            Thread.sleep(20)
        }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
