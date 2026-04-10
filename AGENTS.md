# Agent Workflow Rules (Koupper)

This file defines the default automation flow for coding agents working in this repository.

## Branching and Base

- Always start from `develop`.
- Keep feature work in dedicated branches (`feature/*`, `fix/*`, `docs/*`).
- Do not run destructive git commands (`reset --hard`, force-push) unless explicitly requested.

## Release Automation (Mandatory)

Use Koupper release scripts instead of manual git/gh command sequences.

- Preflight only:
  - `koupper run scripts/release/preflight.kts '{"featureBranch":"feature/my-change"}'`
- Full flow dry run:
  - `koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","dryRun":true}'`
- Fast lane (recommended for `develop`):
  - `koupper run scripts/release/fast-lane.kts '{"featureBranch":"feature/my-change","enableAutoMerge":true}'`
- Create PR and wait for CI:
  - `koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","waitForCi":true,"mergeAfterCi":false}'`
- Create PR, wait for CI, then merge:
  - `koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","waitForCi":true,"mergeAfterCi":true,"adminMerge":true}'`

## CI and Merge Policy

- Prefer merge only when CI concludes `success`.
- If CI fails, inspect the failing workflow/job and fix root cause before retry.
- Keep PR titles and bodies concise and action-oriented.
- PRs to `develop` use fast CI gates (compile + targeted consistency checks).
- Heavy validation (full smoke + install/uninstall E2E) runs on `main`/release flows.
- Run local quick checks before push (`scripts/ci/local-quick-checks.ps1|.sh`).

## Safety and Scope

- Keep changes scoped to requested task.
- Avoid unrelated refactors during delivery work.
- Do not include untracked local notes/docs in commits unless explicitly requested.

## Recommended Agent Routine

1. Run preflight/dry-run flow.
2. Implement requested change.
3. Validate locally (compile/tests relevant to scope).
4. Use release flow script to open PR and monitor CI.
5. Merge only under configured policy.
