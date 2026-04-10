package com.koupper.providers.k8s

import java.io.File
import java.util.concurrent.TimeUnit

data class K8sResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
)

interface K8sProvider {
    fun apply(manifestPath: String, namespace: String, dryRun: Boolean = true): K8sResult
    fun get(kind: String, name: String, namespace: String): K8sResult
    fun logs(kind: String, name: String, namespace: String, tail: Int = 200): K8sResult
    fun rolloutStatus(deployment: String, namespace: String): K8sResult
    fun rolloutRestart(deployment: String, namespace: String): K8sResult
}

class KubectlK8sProvider(
    private val kubectl: String = "kubectl",
    private val timeoutSeconds: Long = 120
) : K8sProvider {

    override fun apply(manifestPath: String, namespace: String, dryRun: Boolean): K8sResult {
        require(namespace.isNotBlank()) { "namespace is required for apply" }
        val args = mutableListOf(kubectl, "apply", "-f", manifestPath, "-n", namespace)
        if (dryRun) args += listOf("--dry-run=client")
        args += "--validate=false"
        return run(args)
    }

    override fun get(kind: String, name: String, namespace: String): K8sResult {
        return run(listOf(kubectl, "get", kind, name, "-n", namespace, "-o", "yaml"))
    }

    override fun logs(kind: String, name: String, namespace: String, tail: Int): K8sResult {
        return run(listOf(kubectl, "logs", "$kind/$name", "-n", namespace, "--tail", tail.toString()))
    }

    override fun rolloutStatus(deployment: String, namespace: String): K8sResult {
        return run(listOf(kubectl, "rollout", "status", "deployment/$deployment", "-n", namespace))
    }

    override fun rolloutRestart(deployment: String, namespace: String): K8sResult {
        require(namespace.isNotBlank()) { "namespace is required for rollout restart" }
        return run(listOf(kubectl, "rollout", "restart", "deployment/$deployment", "-n", namespace))
    }

    private fun run(args: List<String>): K8sResult {
        val process = try {
            ProcessBuilder(args).directory(File(".")).start()
        } catch (error: Throwable) {
            return K8sResult(
                command = args.joinToString(" "),
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "failed to start kubectl process"
            )
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return K8sResult(
                command = args.joinToString(" "),
                exitCode = 124,
                stdout = "",
                stderr = "kubectl command timed out after ${timeoutSeconds}s",
                timedOut = true
            )
        }
        return K8sResult(
            command = args.joinToString(" "),
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim()
        )
    }
}
