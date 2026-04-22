package com.koupper.providers.git

data class GitCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
)

data class GitDiffRequest(
    val repoPath: String = ".",
    val staged: Boolean = false
)

data class GitLogRequest(
    val repoPath: String = ".",
    val limit: Int = 20,
    val oneline: Boolean = true
)

data class GitCommitRequest(
    val repoPath: String = ".",
    val message: String,
    val addAll: Boolean = false
)

data class GitBranchRequest(
    val repoPath: String = ".",
    val name: String,
    val checkout: Boolean = true
)

data class GitMergeRequest(
    val repoPath: String = ".",
    val source: String,
    val noFastForward: Boolean = false
)

data class GitTagRequest(
    val repoPath: String = ".",
    val name: String,
    val annotated: Boolean = false,
    val message: String? = null
)

interface GitClient {
    fun status(repoPath: String = "."): GitCommandResult
    fun diff(request: GitDiffRequest = GitDiffRequest()): GitCommandResult
    fun log(request: GitLogRequest = GitLogRequest()): GitCommandResult
    fun createBranch(request: GitBranchRequest): GitCommandResult
    fun checkout(repoPath: String = ".", branch: String): GitCommandResult
    fun commit(request: GitCommitRequest): GitCommandResult
    fun merge(request: GitMergeRequest): GitCommandResult
    fun tag(request: GitTagRequest): GitCommandResult
}
