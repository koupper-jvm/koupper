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

## [Unreleased]

### 🧰 Scaffolding and Tooling
- Installer now provisions a local versioned module template in `~/.koupper/templates/model-project` for local-first module generation.
- `new` command help now documents parameter and script import flag usage more explicitly.
- Installer now supports `--force` reinstall cleanup and `--doctor` verification mode.
- Uninstaller now supports CLI flags `--force` and `--purge` for non-interactive cleanup workflows.
- Installer now provisions a providers catalog at `~/.koupper/catalog/providers.json` for CLI provider discovery.

### 🧪 CI Policy
- Added `PR Fast Checks` workflow for quick compile validation on every PR.
- `Full Smoke Suite` now runs on PRs only when label `run-full-ci` is present, and always runs on `push` to `develop` or manual dispatch.

### 🔎 Provider Discoverability
- Added `koupper provider list` to display registered providers with short descriptions.
- Added `koupper provider info <name>` to display provider contracts, implementations/tags, and env requirements.

### 🐙 GitHub Provider
- Added `GitHubServiceProvider` with `GitHubClient` operations for issues, pull requests, workflow dispatch/runs, and check-runs.
- Added provider catalog metadata for `github` including required/optional environment variables.
- Added runnable example script `examples/github-provider-flow.kts` with JSON input sample.
- Added JSON-driven orchestration example `examples/github-integration-flow.kts` for PR checks, workflow dispatch/wait, merge, and issue creation.

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
