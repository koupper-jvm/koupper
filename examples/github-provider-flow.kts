import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.github.GitHubClient
import com.koupper.providers.github.GitHubIssueRequest
import com.koupper.providers.github.GitHubPullRequestRequest
import com.koupper.providers.github.GitHubWorkflowDispatchRequest

data class Input(
    val action: String,
    val owner: String? = null,
    val repo: String? = null,
    val title: String? = null,
    val body: String? = null,
    val head: String? = null,
    val base: String? = null,
    val workflowId: String? = null,
    val ref: String? = null,
    val runId: Long? = null
)

@Export
val githubOps: (Input) -> Map<String, Any?> = { input ->
    val github = app.getInstance(GitHubClient::class)

    when (input.action.lowercase()) {
        "create-issue" -> {
            val issue = github.createIssue(
                GitHubIssueRequest(
                    title = input.title ?: error("title is required for create-issue"),
                    body = input.body ?: "Created from Koupper GitHub provider script",
                    owner = input.owner,
                    repo = input.repo
                )
            )

            mapOf(
                "ok" to true,
                "type" to "issue",
                "number" to issue.number,
                "url" to issue.url,
                "state" to issue.state
            )
        }

        "create-pr" -> {
            val pull = github.createPullRequest(
                GitHubPullRequestRequest(
                    title = input.title ?: error("title is required for create-pr"),
                    body = input.body ?: "Created from Koupper GitHub provider script",
                    head = input.head ?: error("head is required for create-pr"),
                    base = input.base ?: "develop",
                    owner = input.owner,
                    repo = input.repo
                )
            )

            mapOf(
                "ok" to true,
                "type" to "pull_request",
                "number" to pull.number,
                "url" to pull.url,
                "state" to pull.state
            )
        }

        "dispatch-workflow" -> {
            github.dispatchWorkflow(
                GitHubWorkflowDispatchRequest(
                    workflowId = input.workflowId ?: error("workflowId is required for dispatch-workflow"),
                    ref = input.ref ?: "develop",
                    owner = input.owner,
                    repo = input.repo
                )
            )

            mapOf(
                "ok" to true,
                "type" to "workflow_dispatch",
                "workflowId" to input.workflowId,
                "ref" to (input.ref ?: "develop")
            )
        }

        "get-run" -> {
            val run = github.getWorkflowRun(
                runId = input.runId ?: error("runId is required for get-run"),
                owner = input.owner,
                repo = input.repo
            )

            mapOf(
                "ok" to true,
                "type" to "workflow_run",
                "id" to run.id,
                "name" to run.name,
                "status" to run.status,
                "conclusion" to run.conclusion,
                "url" to run.htmlUrl
            )
        }

        else -> error("Unsupported action '${input.action}'. Use create-issue, create-pr, dispatch-workflow, get-run.")
    }
}
