package com.koupper.providers.git

import java.io.File
import java.util.concurrent.TimeUnit

class GitCliClient(
    private val command: String = "git",
    private val timeoutSeconds: Long = 120
) : GitClient {

    override fun status(repoPath: String): GitCommandResult {
        return run(listOf(command, "status", "--short", "--branch"), repoPath)
    }

    override fun diff(request: GitDiffRequest): GitCommandResult {
        val args = mutableListOf(command, "diff")
        if (request.staged) args += "--staged"
        return run(args, request.repoPath)
    }

    override fun log(request: GitLogRequest): GitCommandResult {
        val args = mutableListOf(command, "log", "-n", request.limit.toString())
        if (request.oneline) args += "--oneline"
        return run(args, request.repoPath)
    }

    override fun createBranch(request: GitBranchRequest): GitCommandResult {
        val args = if (request.checkout) {
            listOf(command, "checkout", "-b", request.name)
        } else {
            listOf(command, "branch", request.name)
        }
        return run(args, request.repoPath)
    }

    override fun checkout(repoPath: String, branch: String): GitCommandResult {
        return run(listOf(command, "checkout", branch), repoPath)
    }

    override fun commit(request: GitCommitRequest): GitCommandResult {
        if (request.addAll) {
            ensureOk(run(listOf(command, "add", "-A"), request.repoPath), "git add -A")
        }

        val stagedHasChanges = run(listOf(command, "diff", "--cached", "--quiet"), request.repoPath).exitCode != 0
        val unstagedHasChanges = run(listOf(command, "diff", "--quiet"), request.repoPath).exitCode != 0

        if (!stagedHasChanges && !unstagedHasChanges) {
            error("cannot commit because repository has no changes")
        }

        if (!stagedHasChanges && unstagedHasChanges) {
            error("cannot commit because there are unstaged changes. Stage files or set addAll=true")
        }

        return run(listOf(command, "commit", "-m", request.message), request.repoPath)
    }

    override fun merge(request: GitMergeRequest): GitCommandResult {
        val args = mutableListOf(command, "merge")
        if (request.noFastForward) args += "--no-ff"
        args += request.source
        return run(args, request.repoPath)
    }

    override fun tag(request: GitTagRequest): GitCommandResult {
        val args = mutableListOf(command, "tag")
        if (request.annotated) {
            args += "-a"
            args += request.name
            args += listOf("-m", request.message ?: "tag ${request.name}")
        } else {
            args += request.name
        }
        return run(args, request.repoPath)
    }

    private fun ensureOk(result: GitCommandResult, step: String) {
        if (result.exitCode != 0) {
            error("$step failed: ${result.stderr.ifBlank { result.stdout }}")
        }
    }

    private fun run(args: List<String>, repoPath: String): GitCommandResult {
        val process = try {
            ProcessBuilder(args).directory(File(repoPath)).start()
        } catch (error: Throwable) {
            return GitCommandResult(
                command = args.joinToString(" "),
                exitCode = 127,
                stdout = "",
                stderr = error.message ?: "failed to start git process"
            )
        }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return GitCommandResult(
                command = args.joinToString(" "),
                exitCode = 124,
                stdout = "",
                stderr = "git command timed out after ${timeoutSeconds}s",
                timedOut = true
            )
        }

        return GitCommandResult(
            command = args.joinToString(" "),
            exitCode = process.exitValue(),
            stdout = process.inputStream.bufferedReader().readText().trim(),
            stderr = process.errorStream.bufferedReader().readText().trim()
        )
    }
}
