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

    override fun add(request: GitAddRequest): GitCommandResult {
        val args = mutableListOf(command, "add")
        if (request.files.isEmpty()) {
            args += "-A"
        } else {
            args += "--"
            args += request.files
        }
        return run(args, request.repoPath)
    }

    override fun blame(request: GitBlameRequest): GitCommandResult {
        val args = mutableListOf(command, "blame")
        val start = request.lineStart
        val end = request.lineEnd
        if (start != null) {
            args += "-L"
            args += "${start},${end ?: start}"
        }
        args += request.file
        return run(args, request.repoPath)
    }

    override fun currentBranch(repoPath: String): GitCommandResult {
        return run(listOf(command, "rev-parse", "--abbrev-ref", "HEAD"), repoPath)
    }

    override fun listBranches(repoPath: String, all: Boolean): GitCommandResult {
        val args = mutableListOf(command, "branch")
        if (all) args += "--all"
        return run(args, repoPath)
    }

    override fun deleteBranch(repoPath: String, name: String, force: Boolean): GitCommandResult {
        val flag = if (force) "-D" else "-d"
        return run(listOf(command, "branch", flag, name), repoPath)
    }

    override fun push(request: GitPushRequest): GitCommandResult {
        val args = mutableListOf(command, "push")
        if (request.setUpstream) args += "-u"
        if (request.force) args += "--force"
        args += request.remote
        if (request.branch != null) args += request.branch
        return run(args, request.repoPath)
    }

    override fun pull(request: GitPullRequest): GitCommandResult {
        val args = mutableListOf(command, "pull")
        if (request.rebase) args += "--rebase"
        args += request.remote
        if (request.branch != null) args += request.branch
        return run(args, request.repoPath)
    }

    override fun fetch(request: GitFetchRequest): GitCommandResult {
        val args = mutableListOf(command, "fetch")
        if (request.prune) args += "--prune"
        args += request.remote
        return run(args, request.repoPath)
    }

    override fun reset(request: GitResetRequest): GitCommandResult {
        val modeFlag = when (request.mode) {
            ResetMode.SOFT -> "--soft"
            ResetMode.MIXED -> "--mixed"
            ResetMode.HARD -> "--hard"
        }
        return run(listOf(command, "reset", modeFlag, request.ref), request.repoPath)
    }

    override fun stash(request: GitStashRequest): GitCommandResult {
        val args = mutableListOf(command, "stash", "push")
        if (request.includeUntracked) args += "-u"
        if (request.message != null) {
            args += "-m"
            args += request.message
        }
        return run(args, request.repoPath)
    }

    override fun stashPop(repoPath: String): GitCommandResult {
        return run(listOf(command, "stash", "pop"), repoPath)
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
