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

data class GitAddRequest(
    val repoPath: String = ".",
    val files: List<String> = emptyList()
)

data class GitBlameRequest(
    val repoPath: String = ".",
    val file: String,
    val lineStart: Int? = null,
    val lineEnd: Int? = null
)

data class GitPushRequest(
    val repoPath: String = ".",
    val remote: String = "origin",
    val branch: String? = null,
    val setUpstream: Boolean = false,
    val force: Boolean = false
)

data class GitPullRequest(
    val repoPath: String = ".",
    val remote: String = "origin",
    val branch: String? = null,
    val rebase: Boolean = false
)

data class GitFetchRequest(
    val repoPath: String = ".",
    val remote: String = "origin",
    val prune: Boolean = false
)

enum class ResetMode { SOFT, MIXED, HARD }

data class GitResetRequest(
    val repoPath: String = ".",
    val mode: ResetMode = ResetMode.MIXED,
    val ref: String = "HEAD"
)

data class GitStashRequest(
    val repoPath: String = ".",
    val message: String? = null,
    val includeUntracked: Boolean = true
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
    fun add(request: GitAddRequest = GitAddRequest()): GitCommandResult
    fun blame(request: GitBlameRequest): GitCommandResult
    fun currentBranch(repoPath: String = "."): GitCommandResult
    fun listBranches(repoPath: String = ".", all: Boolean = false): GitCommandResult
    fun deleteBranch(repoPath: String = ".", name: String, force: Boolean = false): GitCommandResult
    fun push(request: GitPushRequest = GitPushRequest()): GitCommandResult
    fun pull(request: GitPullRequest = GitPullRequest()): GitCommandResult
    fun fetch(request: GitFetchRequest = GitFetchRequest()): GitCommandResult
    fun reset(request: GitResetRequest = GitResetRequest()): GitCommandResult
    fun stash(request: GitStashRequest = GitStashRequest()): GitCommandResult
    fun stashPop(repoPath: String = "."): GitCommandResult
}
