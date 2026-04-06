package com.koupper.providers.iac

import java.io.File
import java.util.concurrent.TimeUnit

data class IaCResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
)

interface IaCProvider {
    fun terraformPlan(path: String, vars: Map<String, String> = emptyMap()): IaCResult
    fun terraformApply(path: String, vars: Map<String, String> = emptyMap(), approved: Boolean = false): IaCResult
    fun terraformOutput(path: String): IaCResult
    fun driftCheck(path: String): IaCResult
}

class TerraformIaCProvider(
    private val terraformCommand: String = "terraform",
    private val timeoutSeconds: Long = 300
) : IaCProvider {

    override fun terraformPlan(path: String, vars: Map<String, String>): IaCResult {
        init(path)
        val args = mutableListOf(terraformCommand, "plan")
        vars.forEach { (k, v) -> args += listOf("-var", "$k=$v") }
        return run(args, path)
    }

    override fun terraformApply(path: String, vars: Map<String, String>, approved: Boolean): IaCResult {
        if (!approved) {
            error("terraformApply requires approved=true")
        }
        init(path)
        val args = mutableListOf(terraformCommand, "apply", "-auto-approve")
        vars.forEach { (k, v) -> args += listOf("-var", "$k=$v") }
        return run(args, path)
    }

    override fun terraformOutput(path: String): IaCResult {
        return run(listOf(terraformCommand, "output", "-json"), path)
    }

    override fun driftCheck(path: String): IaCResult {
        init(path)
        return run(listOf(terraformCommand, "plan", "-detailed-exitcode"), path)
    }

    private fun init(path: String) {
        run(listOf(terraformCommand, "init", "-input=false"), path)
    }

    private fun run(args: List<String>, path: String): IaCResult {
        val process = try {
            ProcessBuilder(args).directory(File(path)).start()
        } catch (error: Throwable) {
            return IaCResult(
                command = args.joinToString(" "),
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "failed to start terraform process"
            )
        }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            throw IllegalStateException("terraform command timed out: ${args.joinToString(" ")}")
        }
        return IaCResult(
            command = args.joinToString(" "),
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim()
        )
    }
}
