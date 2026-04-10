# Next Features Notes

This note keeps the next implementation waves scoped and aligned with current `develop` CI/release policy.

## Current operating baseline

- Branch from `develop`.
- Run local quick checks before push (`scripts/ci/local-quick-checks.ps1|.sh`).
- Use fast lane for high-velocity PR flow:
  - `koupper run scripts/release/fast-lane.kts '{"featureBranch":"feature/<name>","enableAutoMerge":true}'`
- Keep heavy validation for `main`/release.

## Near-term priorities

1. **Provider developer experience**
   - Add a provider authoring checklist template (`register + catalog + docs + tests`).
   - Add provider scaffold command or script for consistent starter files.

2. **Installer lifecycle hardening**
   - Add Linux/macOS uninstall E2E parity to heavy workflow.
   - Add a lightweight health command (`koupper doctor`) smoke to release checks.

3. **Release script ergonomics**
   - Add a `--no-auto-merge` fallback mode message in fast lane output.
   - Emit explicit PR URL + next actions at end of every release script.

4. **Observability for delivery speed**
   - Track median CI duration for `develop` and `main` checks.
   - Add a monthly CI duration review note in maintainer docs.

## Scope guardrails

- Do not mix provider feature code with unrelated refactors.
- Keep docs and catalog updates in the same delivery wave as provider changes.
- If a change touches release scripts, update `scripts/release/README.md` in the same PR.
