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
- `octopus/build.gradle` `optimized` task filter — replaced `contains('container')` (which captured `jersey-container-*`) with `matches("module-[0-9].*\.jar")` regex. Optimized JAR dropped from ~3.4MB with Grizzly leakage to 1.6MB with zero external class leakage.

### Release alignment
- `octopus 6.5.3` / `koupper-cli 4.8.0`

---

## [6.6.0] - 2026-06-24

### Added
- **SPI-only provider discovery** — `ServiceProviderManager` no longer falls back to a hardcoded list. External providers can now be contributed without editing core framework files. The Gradle task `generateServiceProviderSpi` produces the `META-INF/services` manifest at build time.
- **Compile error source mapping** — when the provider preamble is injected, compilation error line numbers are adjusted by subtracting the preamble offset. Users see line numbers relative to their `.kts` file, not the augmented source.
- **Cross-validation: regex vs reflection signatures** — the `@Export` resolver now compares regex-extracted parameter types with reflection-extracted types and logs a warning on mismatch. Reflection remains the primary source; this adds visibility into regex parsing edge cases.
- **Prometheus `/metrics` endpoint** — lightweight HTTP server (JDK `HttpServer`) exposes `DaemonMetrics` in Prometheus text exposition format at `http://127.0.0.1:9999/metrics`. Eight runtime counters available: uptime, active connections, total commands, total scripts, successful/failed scripts, unauthorized/invalid commands.
- **E2E harness expansion** — `OctopusE2ETest` now covers `@Scheduled` registration (with duplicate-skip guard) and `@Pipeline` execution path.
- **Provider tier system** — ServiceProviders are now classified into `CORE` (>80% test coverage, exception-safe, schema-typed), `COMMUNITY` (default, basic tests), and `EXPERIMENTAL` (no test gate, excluded from fatJar by default). Tier is declared via `ServiceProvider.tier()`. `ServiceProviderManager.listProvidersByTier()` enables CI gates per tier.
- **JWT authentication with scopes** — `security/JwtAuth.kt` supports HMAC256-signed tokens with scope-based access control (`koupper:read`, `koupper:execute`, `koupper:admin`). Backward-compatible with legacy static tokens via auto-detection.
- **HTTP REST API** — JDK `HttpServer` on port 9997 exposes `POST /api/v1/run`, `GET /api/v1/health`, `GET /api/v1/status`, `GET /api/v1/jobs`. CORS + JWT auth integrated.
- **Script sandboxing** — `ScriptSandbox.kt` enforces execution timeout (default 5min), intercepts `System.exit()` via `SecurityManager`, and provides thread isolation. Disabled by default; enable with `koupper.scripting.sandbox=true`.
- **OpenTelemetry tracing** — `KoupperTelemetry.kt` creates spans, propagates W3C context, and instruments `@Export` resolver. Added `io.opentelemetry:opentelemetry-*:1.40.0` dependencies.
- **JWT authentication with scoped authorization** — `OctopusProtocol` now supports JWT tokens (HMAC256) alongside legacy static tokens. Three scopes: `koupper:read` (HEALTH_CHECK), `koupper:execute` (RUN, DEPLOY), `koupper:admin` (WATCH, CANCEL). New `security/JwtAuth.kt` utility for generation, verification, and scope checking.
- **HTTP REST API** — lightweight HTTP server (JDK `HttpServer`) on port 9997 provides REST endpoints: `POST /api/v1/run` (execute script), `GET /api/v1/health` (health check), `GET /api/v1/status` (daemon metrics), `GET /api/v1/jobs` (list queues). CORS-enabled. JWT auth on protected endpoints.
- **OpenTelemetry tracing** — `KoupperTelemetry.kt` provides automatic span creation around script execution with W3C trace context propagation. Configurable via `KOUPPER_TELEMETRY_ENABLED` and `KOUPPER_TELEMETRY_SERVICE`. Exports spans to stdout by default; supports OTLP via standard OTEL environment variables.
- **gRPC bidirectional streaming** — `JobQueueGrpcServer` and `JobQueueGrpcClient` enable real-time job dispatch and status updates over gRPC. Proto definition in `octopus/src/main/proto/job_queue.proto`. Server runs on port 9996. Client features automatic reconnection with 5-second backoff. Port 9997 is REST API, 9998 is Octopus socket, 9999 is Prometheus.

### Changed
- `ServiceProviderManager.listProviders()` now throws `IllegalStateException` with an actionable message when the SPI file is missing or empty, instead of silently falling back to a hardcoded list.

### Release alignment
- `octopus 6.6.0` / `koupper-cli 4.8.0`

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
