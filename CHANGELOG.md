# Koupper Changelog

All notable changes to the Koupper monorepo are documented here.
Versioning follows the Octopus engine version (`koupper/build.gradle`).
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

---

## [6.4.0] - 2026-04-10

### Added
- `koupper infra init|validate|plan|apply|drift|output` — Terraform-backed infrastructure lifecycle suite with retry/timeout/backoff controls and drift spec v1 evaluation.
- `koupper reconcile run` — reconcile command with stage policies and stable JSON output contracts.
- AWS deploy hardening: Lambda waiter support, timeout/retry/backoff per action, frontend backup modes (`full|incremental|disabled`), structured per-action result metadata, `preflight`, `smokeTestApis`, and `callerIdentity` operations.
- `docs/CONTRACT_VERSIONING_POLICY.md` — governs additive/behavior/breaking change taxonomy, deprecation lifecycle, and migration note format.
- `docs/PROVIDER_AUTHORING_CHECKLIST.md` — four-surface checklist (register + catalog + docs + tests) for every new service provider.
- `docs/migrations/` — directory for per-change migration notes on behavior changes.
- `docs/KOUPPER_FRAMEWORK_MATURITY_PLAYBOOK.md` — strategic enterprise hardening execution plan.
- `SecretsClient.delete(key)` and `SecretsClient.list()` — completes the secrets contract.
- `ObservabilityExecutionMonitor` — wires runtime script execution lifecycle (trace, metric, failure event) to `ObservabilityProvider` via the existing `CompositeExecutionMonitor` chain.
- `CLAUDE.md` — Claude Code guidance file for AI-assisted development sessions.

### Fixed
- `KubectlK8sProvider` timeout now returns `K8sResult(exitCode=124, timedOut=true)` instead of throwing `IllegalStateException`. Launch failures return `exitCode=127`. Migration note in `docs/migrations/`.
- `MCPServerProvider` — replaced `com.sun.net.httpserver` (internal JDK API) with `ServerSocket` + `CachedThreadPool` using only `java.net` standard library.

### Release alignment
- `octopus 6.4.0` / `koupper-cli 4.7.1`

---

## [6.3.1] - 2026-03-28

### Added
- `koupper run --serve` for long-running script sessions with attached CLI output and daemon-side cancellation via `Ctrl+C`.
- `koupper provider list` and `koupper provider info <name>` — provider discoverability from installed catalog.
- `process-supervisor` provider for detached local long-running process management with persisted metadata and per-process logs.
- GitHub provider (`GitHubServiceProvider`) with `GitHubClient` operations: issues, pull requests, workflow dispatch/runs, and check-runs.
- Terminal runtime demo and interactive prompt visibility fix for PowerShell.
- Setup helpers (`scripts/setup/install.sh`, `scripts/setup/install.ps1`) with optional `--auto-install-deps` mode.
- `--force` reinstall and `--doctor` verification mode in installer.
- `--force` and `--purge` flags in uninstaller.
- Installer provisions providers catalog at `~/.koupper/catalog/providers.json`.
- `install-uninstall-e2e-windows` heavy CI gate added to `full-smoke-suite.yml`.
- `PR Fast Checks` and `Provider Consistency` workflows for fast CI on `develop` PRs.
- Remote deploy token authentication and payload checksum verification.
- Deploy payload size limits with explicit rejection for oversized payloads.

### Release alignment
- `octopus 6.3.1` / `koupper-cli 4.7.1`

---

## [6.0.0] - 2026-03-26

### Added
- Monorepo migration: consolidated `koupper`, `koupper-cli`, and `.koupper` template into a single repository for version parity.
- Advanced JSON mapping for CLI socket dispatcher — raw JSON string injection with deep PowerShell quote cleanup and permissive Jackson deserialization into nested Kotlin POJOs.
- Event-driven background worker logging — deprecated untraceable `println` usage across async tasks; injected `GlobalLogger` lifecycle tracking with rolling log files.
- Socket exception bubbling — fatal Jackson/mapper errors now flush upstream via `<ERROR::>` marker instead of failing silently.
- UTF-8 byte preservation across TCP socket streams — emoji and multi-byte characters survive the CLI rendering pipeline cross-OS.
- Release governance: semver policy, stable tagging convention (`octopus-v*`, `cli-v*`), and independent artifact versioning.

---
