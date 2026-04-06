/**
 * Git Provider Operations Demo
 *
 * Purpose:
 * - Execute one local Git action per run using the Git provider.
 *
 * Typical runs:
 * - koupper run examples/git-provider-flow.kts '{"action":"status"}'
 * - koupper run examples/git-provider-flow.kts '{"action":"log","limit":5}'
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.git.GitBranchRequest
import com.koupper.providers.git.GitClient
import com.koupper.providers.git.GitCommitRequest
import com.koupper.providers.git.GitDiffRequest
import com.koupper.providers.git.GitLogRequest
import com.koupper.providers.git.GitMergeRequest
import com.koupper.providers.git.GitTagRequest

data class Input(
    val action: String,
    val repoPath: String = ".",
    val branch: String? = null,
    val message: String? = null,
    val limit: Int = 20,
    val staged: Boolean = false,
    val addAll: Boolean = false,
    val annotated: Boolean = false,
    val source: String? = null,
    val noFastForward: Boolean = false
)

@Export
val gitOps: (Input) -> Map<String, Any?> = { input ->
    val git = app.getInstance(GitClient::class)

    val result = when (input.action.lowercase()) {
        "status" -> git.status(input.repoPath)
        "diff" -> git.diff(GitDiffRequest(repoPath = input.repoPath, staged = input.staged))
        "log" -> git.log(GitLogRequest(repoPath = input.repoPath, limit = input.limit, oneline = true))
        "create-branch" -> git.createBranch(
            GitBranchRequest(
                repoPath = input.repoPath,
                name = input.branch ?: error("branch is required for create-branch"),
                checkout = true
            )
        )

        "checkout" -> git.checkout(input.repoPath, input.branch ?: error("branch is required for checkout"))
        "commit" -> git.commit(
            GitCommitRequest(
                repoPath = input.repoPath,
                message = input.message ?: error("message is required for commit"),
                addAll = input.addAll
            )
        )

        "merge" -> git.merge(
            GitMergeRequest(
                repoPath = input.repoPath,
                source = input.source ?: error("source is required for merge"),
                noFastForward = input.noFastForward
            )
        )

        "tag" -> git.tag(
            GitTagRequest(
                repoPath = input.repoPath,
                name = input.branch ?: error("branch field is used as tag name for tag action"),
                annotated = input.annotated,
                message = input.message
            )
        )

        else -> error("Unsupported action '${input.action}'. Use status, diff, log, create-branch, checkout, commit, merge, tag.")
    }

    mapOf(
        "ok" to (result.exitCode == 0),
        "action" to input.action,
        "command" to result.command,
        "exitCode" to result.exitCode,
        "stdout" to result.stdout,
        "stderr" to result.stderr
    )
}
