# Koupper Maintainer Guide

This folder stores maintainer-focused documentation. Public user documentation lives in `koupper-docs/docs`.

## Documentation map

| Surface | Path | Audience | Purpose |
| --- | --- | --- | --- |
| Public docs | `koupper-docs/docs` | Framework users | Product usage, command reference, providers, architecture, production guides |
| Internal docs | `docs/` | Maintainers | Release routines, governance rules, internal checklists |
| Examples | `examples/` | Users + maintainers | Runnable reference scripts and smoke assets |

## Core internal docs

- `DOCUMENTATION_STANDARD.md`: source-of-truth and placement rules for docs.
- `ICP_PRODUCTIZATION_V1.md`: target customer profile, KPIs, and monetization direction.
- `SCRIPT_EXECUTION_CONTRACT.md`: runtime contract for script entrypoints and pipeline usage.
- `PRODUCTION_HARDENING.md`: production baseline for Octopus runtime operations.
- `CLI_COMMAND_CHECKLIST.md`: release-time manual CLI validation checklist.
- `release-versioning.md`: version bump and tagging policy.
- `NEXT_FEATURES_NOTES.md`: short-term backlog and guardrails for upcoming feature waves.
- `KOUPPER_FRAMEWORK_MATURITY_PLAYBOOK.md`: strategic execution plan for enterprise hardening and new-agent onboarding.
- `CONTRACT_VERSIONING_POLICY.md`: governs additive vs breaking API changes and deprecation lifecycle across CLI, providers, and script execution contract.
- `PROVIDER_AUTHORING_CHECKLIST.md`: four-surface checklist (register + catalog + docs + tests) for every new provider.
- `migrations/`: per-change migration notes for behavior or default changes that affect existing usage.

## Archive

- `archive/`: historical plans and implementation briefs kept for reference.

## Rule of thumb

- If users should read it, move it to `koupper-docs/docs`.
- If maintainers operate the repo with it, keep it here.
- If documentation references commands/scripts, point to runnable assets in `examples/`.

## CI policy snapshot

- `develop` PRs: fast checks only (`pr-fast-checks.yml`, `provider-consistency.yml`).
- `main`/release: heavy validation (`full-smoke-suite.yml`, including install/uninstall E2E).
- Local pre-push quick checks: `scripts/ci/local-quick-checks.ps1` and `scripts/ci/local-quick-checks.sh`.
