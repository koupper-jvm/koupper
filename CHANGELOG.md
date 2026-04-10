# 🐙 Koupper Framework - Global Changelog

**Koupper** is an agile, scripting-first ecosystem for Kotlin. It is designed to act as a Modular Backend Platform where you can deploy reactive daemons, HTTP APIs, and asynchronous Job Workers using plain `.kts` or `.kt` scripts empowered by annotations.

This repository serves as the **Monorepo** orchestrating three core pillars:
1. **`koupper` (Octopus Engine):** The JVM-based daemon that compiles, routes, and executes your scripts.
2. **`koupper-cli`:** The terminal interface to manage, dispatch, and configure your modules.
3. **`.koupper`:** The boilerplate runtime folder that resides in `$KOUPPER_HOME`.

All notable changes to the entire Monorepo infrastructure will be documented here.

---

## [1.2.1-monorepo] - 2026-03-28

### 🔐 Deploy Security Hardening
- Remote deploy now enforces token-authenticated execution and payload checksum verification.
- Runtime deploy guardrails were added to reject oversized payloads with explicit limits.

### 🧪 Stability
- Added DEPLOY-focused socket integration coverage for success/failure auth and hash paths.
- Added production hardening guidance document and linked it from the root README.

### 📦 Release Alignment
- Monorepo stable snapshot aligned to:
  - `octopus 6.3.1`
  - `koupper-cli 4.7.1`

## [6.4.0-monorepo] - 2026-04-10

### 🏗️ Infrastructure & Reconcile Framework
- Added `koupper infra init|validate|plan|apply|drift|output` command suite for Terraform-backed infrastructure lifecycle management.
- Added `koupper reconcile run` with stage policies and structured JSON output contracts.
- Added `IaCProvider` with full retry/timeout/backoff controls, `evaluateDriftSpec` for drift spec v1 validation, and deprecated legacy `terraformPlan/Apply/Output/driftCheck` methods.
- Added drift spec documentation and reconcile runbook (`docs/production/drift-spec-v1.md`, `reconcile-runbook.md`).

### 🔐 AWS Deploy Hardening
- Added timeout/retry/backoff controls and Lambda waiter support to `AwsDeployProvider`.
- Added frontend backup modes (`full|incremental|disabled`) and structured per-action result metadata.
- Added `preflight`, `smokeTestApis`, and `callerIdentity` operations to the AWS deploy contract.

### 🔒 Contract Versioning & Governance
- Added `docs/CONTRACT_VERSIONING_POLICY.md`: defines additive/behavior/breaking change taxonomy, deprecation annotation format, migration note format, and provider interface addition rules.
- Added `docs/PROVIDER_AUTHORING_CHECKLIST.md`: four-surface checklist (register + catalog + docs + tests) for every new service provider.
- Added `docs/migrations/` directory with structured migration notes for behavior changes.
- Added `docs/KOUPPER_FRAMEWORK_MATURITY_PLAYBOOK.md`: strategic execution plan for enterprise hardening and agent onboarding.

### 🔑 Secrets Provider
- Added `SecretsClient.delete(key)` and `SecretsClient.list()` to complete the secrets contract.
- `LocalSecretsClient` persists deletes to file when `persistWrites=true`.

### 🐳 K8s Provider Fix
- `KubectlK8sProvider` timeout now returns structured `K8sResult(exitCode=124, timedOut=true)` instead of throwing `IllegalStateException`. Launch failures return `exitCode=127`. Migration note in `docs/migrations/`.

### 🧪 Tests
- Added `SecretsClientTest` (9 cases) and `K8sProviderTest` (3 cases).

### 📦 Release Alignment
- Monorepo stable snapshot aligned to:
  - `octopus 6.4.0`
  - `koupper-cli 4.7.1`

---

## [Unreleased]

### 🧰 Scaffolding and Tooling
- Installer now provisions a local versioned module template in `~/.koupper/templates/model-project` for local-first module generation.
- `new` command help now documents parameter and script import flag usage more explicitly.
- Installer now supports `--force` reinstall cleanup and `--doctor` verification mode.
- Uninstaller now supports CLI flags `--force` and `--purge` for non-interactive cleanup workflows.
- Installer now provisions a providers catalog at `~/.koupper/catalog/providers.json` for CLI provider discovery.
- Added setup helper scripts (`scripts/setup/install.sh`, `scripts/setup/install.ps1`) for one-command prerequisite check + install flow.
- Setup helpers now support optional dependency auto-install/update mode (`--auto-install-deps` / `-AutoInstallDeps`) with confirmation and non-interactive mode.

### ▶️ Live Run Mode
- Added `koupper run --serve` for long-running script sessions (local servers/listeners) with attached CLI output.
- Added daemon-side cancellation command support so `Ctrl+C` in serve mode can interrupt active execution by request id.
- Added runtime-router live example script: `examples/runtime-router-live-server.kts`.

### 🧪 CI Policy
- Added `PR Fast Checks` workflow for quick compile validation on every PR.
- Added `Provider Consistency` workflow for fast provider/core/CLI parity checks on PRs to `develop`.
- `Full Smoke Suite` now acts as heavy validation for `main`/release flows (PR to `main`, push to `main`, tags, manual dispatch, nightly schedule).
- Added `install-uninstall-e2e-windows` heavy gate to validate installer/uninstaller lifecycle before release merges.

### 🔎 Provider Discoverability
- Added `koupper provider list` to display registered providers with short descriptions.
- Added `koupper provider info <name>` to display provider contracts, implementations/tags, and env requirements.
- Added `terminal` runtime entry in provider catalog so `koupper provider` commands expose interactive terminal capabilities.

### ⚙️ Process Supervision Provider
- Added `process-supervisor` provider for detached local long-running process management.
- Added persisted process metadata store (`~/.koupper/processes.json`) and per-process logs directory (`~/.koupper/process-logs`).
- Added script examples for local process lifecycle management (`scripts/local-up.kts`, `scripts/local-status.kts`, `scripts/local-down.kts`, `scripts/local-logs.kts`).

### 🐙 GitHub Provider
- Added `GitHubServiceProvider` with `GitHubClient` operations for issues, pull requests, workflow dispatch/runs, and check-runs.
- Added provider catalog metadata for `github` including required/optional environment variables.
- Added runnable example script `examples/github-provider-flow.kts` with JSON input sample.
- Added JSON-driven orchestration example `examples/github-integration-flow.kts` for PR checks, workflow dispatch/wait, merge, and issue creation.
- Added primary multi-repo orchestration script `automation/github-multi-repo-flow.kts` and sample plan `automation/flows/koupper-stack.sample.json`.
- Added issue template pack support in multi-repo flow (`issueTemplatePack.providers`) to auto-generate provider refactor issues.
- Added production-oriented flow templates:
  - `automation/flows/flow-pr-fast.json`
  - `automation/flows/flow-pre-merge-full-smoke.json`
  - `automation/flows/flow-merge-and-followups.json`

### 💬 Terminal Runtime
- Added `examples/terminal-runtime-demo.kts` showing `TerminalIO` prompt/print flow.
- Updated terminal demo to default to non-blocking execution and enable prompt mode via `{"askPrompt":true}`.
- Fixed interactive prompt visibility in `run` command and PowerShell shim so `TerminalIO.prompt` messages render before user input.

## [1.1.0-monorepo] - 2026-03-28

### 🧱 Release Governance
- Introduced explicit release-track guidance for independent artifacts (`koupper` / Octopus and `koupper-cli`) aligned with Semantic Versioning.
- Established stable release tagging convention for production cuts:
  - `octopus-v<version>`
  - `cli-v<version>`
  - optional platform snapshot tag `koupper-v<version>`

### ✅ Stability and Hardening
- Completed a focused hardening wave across socket protocol handling, parser reliability, daemon/client output isolation, and test coverage.
- Added smoke and integration-oriented coverage to reduce regressions in CLI-to-Octopus request flows.
- Stabilized environment-dependent test suites to avoid flaky outcomes in CI and local execution.

## [1.0.0-monorepo] - 2026-03-26

### 🌟 Architectural Milestones
- **Monorepo Migration:** Consolidated `koupper`, `koupper-cli`, and the template folder into a single unified repository (`koupper-infrastructure`) to guarantee version parity between the execution engine, the CLI bindings, and the user template constraints.
- **`.koupper` Boilerplate:** Officially renamed the template copy folder (`koupper-copy`) to `.koupper` to act as the pure system stub.

### 🚀 Core Framework Upgrades (Octopus & CLI)
- **Advanced Native JSON Mapping for CLI**: Completely overhauled the CLI Socket dispatcher to support raw JSON string injection via Terminal (e.g. `{"email": "test@test.com"}`). The CLI now deeply cleans external PowerShell quotes, and the Octopus Engine leverages a highly permissive Jackson configuration to seamlessly deserialize complex JSON structures directly into nested Kotlin POJOs on the fly.
- **Event-Driven Background Workers Logging**: Refactored the internal Daemon logger. Deprecated untraceable standard `println` usages across asynchronous tasks (`@Scheduled`, `@JobsListener`, and `ScriptRunner`). Injected explicit, professional lifecycle logging tracking using `GlobalLogger` to gracefully trace daemon heartbeats in rotated `.log` files cleanly.
- **Socket Exception Bubbling (Anti-Silent Failures)**: Hardened the `Octopus.kt` internal execution loop. If the Jackson engine or Script Mapper encounters a fatal error, the backend now forcefully captures the `Exception` and flushes it upstream via the `<ERROR::>` network marker. The CLI crashes transparently with the stacktrace instead of failing silently.
- **UTF-8 Byte Preservation (Emojis Support)**: Restored a pristine standard character boundary on all TCP Socket Streams. `System.out` capturing now correctly buffers bytes via `ByteArrayOutputStream("UTF-8")` guaranteeing multi-byte characters like emojis (`🚀`, `📍`, `📊`) natively survive through the CLI rendering pipeline cross-OS.
- **CLI Visual Polish**: The CLI tool now injects an elegant line break immediately after transmitting the command to the local server, separating interactive command prompts from real-time script evaluation responses cleanly.

### 🔮 Future Roadmap (Proposed Improvements)
- **REST / gRPC Interop**: Evolve the raw `Socket:9998` TCP communication pipeline into a lightweight Ktor REST API to standardize incoming parameter serialization naturally.
- **Dual SLF4J Conflict Resolution**: Actively exclude the `slf4j-nop` bindings in the Gradle build trees to silence runtime instantiation warnings on startup.
- **Live Job Metrics Database Integration**: Upgrade `koupper job status` to dynamically read from the internal SQLite index showing accurate Real-Time *Completed* & *Failed* counts instead of basic queue capacity checks.
