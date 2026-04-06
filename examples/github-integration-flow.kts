/**
 * GitHub Integration Flow Demo
 *
 * Purpose:
 * - Orchestrate end-to-end repository automation in one flow payload.
 *
 * Flow capabilities:
 * - create pull request
 * - wait for required check runs
 * - dispatch workflow and optionally wait for completion
 * - optional PR merge
 * - optional follow-up issue creation
 *
 * Safety:
 * - supports dryRun=true to print intended actions without mutating GitHub state.
 *
 * Typical run:
 * - koupper run examples/github-integration-flow.kts --json-file examples/github-integration-flow.sample.json
 */
import com.koupper.container.app
import com.koupper.octopus.annotations.Export
import com.koupper.providers.github.GitHubCheckRun
import com.koupper.providers.github.GitHubClient
import com.koupper.providers.github.GitHubIssueRequest
import com.koupper.providers.github.GitHubMergeRequest
import com.koupper.providers.github.GitHubPullRequestRequest
import com.koupper.providers.github.GitHubWorkflowDispatchRequest

data class IssuePlan(
    val title: String,
    val body: String,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList()
)

data class PullRequestPlan(
    val create: Boolean = false,
    val title: String = "",
    val body: String = "",
    val head: String = "",
    val base: String = "develop",
    val number: Int? = null,
    val merge: Boolean = false,
    val mergeMethod: String = "merge"
)

data class ChecksPlan(
    val wait: Boolean = false,
    val ref: String = "",
    val required: List<String> = emptyList(),
    val timeoutSeconds: Long = 900,
    val pollIntervalSeconds: Long = 15
)

data class WorkflowPlan(
    val dispatch: Boolean = false,
    val workflowId: String = "",
    val ref: String = "develop",
    val inputs: Map<String, String> = emptyMap(),
    val wait: Boolean = false,
    val timeoutSeconds: Long = 1800,
    val pollIntervalSeconds: Long = 15
)

data class IntegrationFlowInput(
    val dryRun: Boolean = true,
    val owner: String,
    val repo: String,
    val pullRequest: PullRequestPlan = PullRequestPlan(),
    val checks: ChecksPlan = ChecksPlan(),
    val workflow: WorkflowPlan = WorkflowPlan(),
    val issues: List<IssuePlan> = emptyList()
)

private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

private fun isTerminal(status: String): Boolean = status.equals("completed", ignoreCase = true)

private fun summarizeChecks(checks: List<GitHubCheckRun>): String {
    if (checks.isEmpty()) return "no check runs found"
    return checks.joinToString(" | ") { "${it.name}:${it.status}/${it.conclusion ?: "n/a"}" }
}

@Export
val runGitHubIntegrationFlow: (IntegrationFlowInput) -> Map<String, Any?> = { input ->
    val gh = app.getInstance(GitHubClient::class)
    val steps = mutableListOf<String>()
    val result = linkedMapOf<String, Any?>(
        "dryRun" to input.dryRun,
        "owner" to input.owner,
        "repo" to input.repo,
        "steps" to steps
    )

    fun step(message: String) {
        steps.add(message)
        println(message)
    }

    var pullNumber = input.pullRequest.number

    if (input.pullRequest.create) {
        if (input.dryRun) {
            step("[dry-run] create PR '${input.pullRequest.title}' from ${input.pullRequest.head} to ${input.pullRequest.base}")
            pullNumber = pullNumber ?: -1
        } else {
            val pr = gh.createPullRequest(
                GitHubPullRequestRequest(
                    title = input.pullRequest.title,
                    body = input.pullRequest.body,
                    head = input.pullRequest.head,
                    base = input.pullRequest.base,
                    owner = input.owner,
                    repo = input.repo
                )
            )
            pullNumber = pr.number
            result["pullRequestUrl"] = pr.url
            step("[ok] PR created: #${pr.number} ${pr.url}")
        }
    }

    if (input.checks.wait) {
        val ref = if (input.checks.ref.isNotBlank()) {
            input.checks.ref
        } else {
            "refs/heads/${input.pullRequest.head}"
        }

        if (input.dryRun) {
            step("[dry-run] wait checks for ref '$ref' required=${input.checks.required}")
        } else {
            val deadline = nowSeconds() + input.checks.timeoutSeconds
            var passed = false

            while (nowSeconds() < deadline) {
                val checks = gh.listCheckRuns(ref, owner = input.owner, repo = input.repo)
                step("[checks] ${summarizeChecks(checks)}")

                val requiredChecks = if (input.checks.required.isEmpty()) checks else {
                    checks.filter { run -> input.checks.required.any { it.equals(run.name, ignoreCase = true) } }
                }

                if (requiredChecks.isNotEmpty() && requiredChecks.all { isTerminal(it.status) }) {
                    val anyFailure = requiredChecks.any {
                        val conclusion = it.conclusion?.lowercase()
                        conclusion == "failure" || conclusion == "cancelled" || conclusion == "timed_out" || conclusion == "startup_failure"
                    }

                    if (anyFailure) {
                        error("Required checks failed for ref '$ref'")
                    }

                    passed = true
                    break
                }

                Thread.sleep(input.checks.pollIntervalSeconds * 1000)
            }

            if (!passed) {
                error("Timed out waiting for checks on '$ref'")
            }

            step("[ok] required checks completed successfully")
        }
    }

    if (input.workflow.dispatch) {
        if (input.dryRun) {
            step("[dry-run] dispatch workflow ${input.workflow.workflowId} on ref ${input.workflow.ref}")
        } else {
            gh.dispatchWorkflow(
                GitHubWorkflowDispatchRequest(
                    workflowId = input.workflow.workflowId,
                    ref = input.workflow.ref,
                    inputs = input.workflow.inputs,
                    owner = input.owner,
                    repo = input.repo
                )
            )
            step("[ok] workflow dispatch requested for ${input.workflow.workflowId}")
        }
    }

    if (input.workflow.wait) {
        if (input.dryRun) {
            step("[dry-run] wait latest workflow run for ${input.workflow.workflowId} on ${input.workflow.ref}")
        } else {
            val deadline = nowSeconds() + input.workflow.timeoutSeconds
            var targetRunId: Long? = null

            while (nowSeconds() < deadline) {
                val runs = gh.listWorkflowRuns(
                    workflowId = input.workflow.workflowId,
                    branch = input.workflow.ref,
                    owner = input.owner,
                    repo = input.repo,
                    perPage = 10
                )

                val first = runs.firstOrNull()
                if (first != null) {
                    targetRunId = first.id
                    val run = gh.getWorkflowRun(targetRunId, owner = input.owner, repo = input.repo)
                    step("[workflow] run=${run.id} status=${run.status} conclusion=${run.conclusion}")

                    if (isTerminal(run.status)) {
                        if (!run.conclusion.equals("success", ignoreCase = true)) {
                            error("Workflow ${run.id} finished with conclusion=${run.conclusion}")
                        }

                        result["workflowRunId"] = run.id
                        result["workflowRunUrl"] = run.htmlUrl
                        step("[ok] workflow run ${run.id} succeeded")
                        break
                    }
                }

                Thread.sleep(input.workflow.pollIntervalSeconds * 1000)
            }

            if (targetRunId == null) {
                error("No workflow run found for workflow=${input.workflow.workflowId} ref=${input.workflow.ref}")
            }
        }
    }

    if (input.pullRequest.merge) {
        if (pullNumber == null || pullNumber <= 0) {
            error("pullRequest.merge=true requires a valid pull request number")
        }

        if (input.dryRun) {
            step("[dry-run] merge PR #$pullNumber using ${input.pullRequest.mergeMethod}")
        } else {
            val merged = gh.mergePullRequest(
                GitHubMergeRequest(
                    pullNumber = pullNumber,
                    mergeMethod = input.pullRequest.mergeMethod,
                    owner = input.owner,
                    repo = input.repo
                )
            )

            if (!merged.merged) {
                error("Pull request #$pullNumber was not merged")
            }

            step("[ok] PR #$pullNumber merged")
        }
    }

    if (input.issues.isNotEmpty()) {
        if (input.dryRun) {
            step("[dry-run] create ${input.issues.size} issues")
        } else {
            val created = mutableListOf<Map<String, Any?>>()

            input.issues.forEach { issue ->
                val createdIssue = gh.createIssue(
                    GitHubIssueRequest(
                        title = issue.title,
                        body = issue.body,
                        labels = issue.labels,
                        assignees = issue.assignees,
                        owner = input.owner,
                        repo = input.repo
                    )
                )

                created += mapOf(
                    "number" to createdIssue.number,
                    "url" to createdIssue.url,
                    "title" to createdIssue.title
                )
            }

            result["issues"] = created
            step("[ok] created ${created.size} issues")
        }
    }

    result
}
