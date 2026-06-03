# Koupper Changelog

All notable changes to the Koupper monorepo are documented here.
Versioning follows the Octopus engine version (`build.gradle`).
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

---

## [Unreleased]

### Added
- **Scripting DX**: Native namespaced shortcuts for Service Providers (`koupper.json()`, `koupper.dynamo()`, etc.).
- **Octopus Sentinel**: Background project watcher for automatic dependency management (`koupper watch`).
- **Dependency Contracts**: Service Providers now declare `externalDependencies()` for autonomous resolution.
- `KHandler.execute()` is now a `suspend fun`, enabling native Structured Concurrency support.
- `MCPClientProvider` / `MCPClientServiceProvider` — connects to external MCP servers via HTTP or stdio transport. Handles initialize handshake, tool discovery, and tool calls. Enables agents to use Playwright, GitHub, filesystem, and any MCP-compliant server as tools (prefix: `serverName.toolName`). Registered in container and catalog.
- `LocalMCPServerProvider` rewritten to JSON-RPC 2.0 (MCP spec `2024-11-05`). Primary endpoint `POST /` handles `initialize`, `tools/list`, `tools/call`, `ping`, and notifications. Legacy `/mcp/tools` and `/mcp/call` preserved for backward compatibility.
- `InferenceConfig` data class — configurable `maxTokens`, `temperature`, `topP`, `stream` for `LlamaServerSidecar` and `LlamaCppEngine`.
- `koupper worker` CLI command — file-based job worker daemon. Polls queue directories atomically (POSIX `renameTo` claiming), executes agent scripts via `koupper run`, and streams output to `logs/<queue>/<jobId>.log`. Supports `--queues`, `--concurrency`, `--interval` flags.
- `GrizzlyRuntimeRouterProvider.respond()` — detects HTML strings and serves with `text/html; charset=UTF-8` instead of `application/json`, enabling script-based HTML page serving.
- `examples/agents/CortexAgent.kts` — reference agent using `InferenceEngine` SP with `TokenListener` streaming, MCP tool loop, and external MCP server discovery via `~/.koupper/mcp/servers.json`.
- `examples/mcp/servers.json` — example configuration for external MCP servers (Playwright, GitHub, filesystem, Postgres).

### Changed
- **Logging Standardization**: Replaced all internal `println` and `printStackTrace` with structured `GlobalLogger` calls for professional JSONL observability.
- **Auto-Import**: The `@Export` annotation import is now automatically injected into script preambles.
- **Namespace Configurable**: The Scripting DX namespace is now configurable via `koupper.scripting.namespace` system property (defaults to `koupper`).

### Fixed
- **Windows Support**: Fixed `AgentServiceProvider` hang during startup on Windows machines by making environment profiling OS-aware.
- `EnvironmentProfiler.calculateBudget()` — replaced hard `IllegalStateException` kill switch with graceful `LOW_END` tier degradation and a warning log. Machines without AVX2 or GPU no longer crash Octopus on startup.
- `LlamaServerSidecar` — lazy initialization via `by lazy {}`. `KOUPPER_LLM_MODEL_PATH` is now validated only on first `predict()` call, not at container boot time.
- `LlamaServerSidecar` SSE parser — skips events where the `content` node is `null` or the literal string `"null"`. Eliminates the spurious `null` token emitted at the start of every streaming response.
- `AgentOrchestrator.runAgent()` — replaced hardcoded `ToolCall("hardware-checker", "execute")` stub with real JSON parsing of LLM response (`toolName`, `action`, `arguments` fields).
- `DefaultToolExecutor` — removed fake simulation responses (`ls output: AgenticCore.kt`). Now performs real `java.io.File` operations for `read`, `exists`, and `list` actions.
- `octopus/build.gradle` `optimized` task filter — replaced `contains('container')` (which captured `jersey-container-*`) with `matches("module-[0-9].*\\.jar")` regex. Optimized JAR dropped from ~3.4MB with Grizzly leakage to 1.6MB with zero external class leakage.

### Release alignment
- `octopus 6.5.3` / `koupper-cli 4.8.0`

---

## [6.5.3] - 2026-05-24

### Added
- Migration note for unified `@Export` annotation path in `docs/migrations/2026-05-export-annotation-path.md`.
- Maintenance branches logic to the `develop` workflow for cleaner `gitignore` and IDE state management.

### Changed
- **BREAKING**: Moved `@Export` annotation from `com.koupper.octopus.annotations` to `com.koupper.shared.annotations` to support unified classpath resolution.
- Updated all internal scripts, examples, and CLI templates to use the new `com.koupper.shared.annotations.Export` path.
- Refactored `koupper-cli` command handlers for jobs, modules, and scripts to generate code with the updated annotation path.

### Fixed
- Fixed `gitignore` missing patterns for `bin/` directories in Gradle submodules and template projects.
- Rescued missing bootstrap fixes from `main` back into `develop` in the root workspace repository.

### Release alignment
- `octopus 6.5.0` / `koupper-cli 4.8.0`

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
