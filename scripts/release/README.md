# Release Flow Scripts

Purpose: automate the GitHub release lifecycle from Koupper scripts using sequential gates and parallel checks.

## Scripts

- `scripts/release/preflight.kts`
  - validates `git`/`gh`, checks clean tree, syncs base branch, and checks out/creates feature branch.
- `scripts/release/pr-create.kts`
  - pushes branch, auto-builds PR title/body when missing, and creates PR with `gh`.
- `scripts/release/ci-watch.kts`
  - polls a workflow until completion and fails fast when CI conclusion is not successful.
- `scripts/release/merge-sync.kts`
  - merges PR using selected strategy and syncs local base branch.
- `scripts/release/release-flow.kts`
  - orchestrates all previous scripts in one command.
- `scripts/release/version-bump.kts`
  - computes/applies semver bump on `koupper/build.gradle`.
- `scripts/release/tag-release.kts`
  - creates/pushes annotated `v*` release tags.

## Quick Usage

Dry-run orchestration:

```bash
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-next-change","dryRun":true}'
```

Create PR and wait for smoke workflow:

```bash
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-next-change","waitForCi":true,"mergeAfterCi":false}'
```

Create PR, wait CI, and merge automatically:

```bash
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-next-change","waitForCi":true,"mergeAfterCi":true,"adminMerge":true}'
```

## Notes

- Default base branch is `develop`.
- Run local quick checks before push: `scripts/ci/local-quick-checks.ps1 -Target all` or `scripts/ci/local-quick-checks.sh all`.
- `preflight.kts` allows `docs/future-providers-brief.md` as untracked by default.
- `preflight.kts` can auto-clean untracked generated helper scripts (`job-runner.kts`, `job-list.kts`, `worker-builder.kts`).
- All scripts support command timeout/retry controls to reduce transient command failures.
- `ci-watch.kts` can pin to a specific `expectedHeadSha` to avoid reading stale workflow runs.
- These scripts call `git` and `gh`; make sure both commands are available on your PATH.
