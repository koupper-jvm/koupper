# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Bootstrap (Mandatory)

Before making any changes, read `docs/AGENT_BOOTSTRAP.md` first. It is the single reception file and defines required follow-up reads based on task scope (release policy, docs governance, framework maturity, etc.).

## Branching Policy

- Base branch: `develop`. Always start feature work from `develop`.
- Branch naming: `feature/*`, `fix/*`, `docs/*`
- Do not run destructive git commands (`reset --hard`, force-push) unless explicitly requested.

## Build Commands

```bash
# Octopus engine (core runtime)
cd koupper && ./gradlew build

# CLI
cd koupper-cli && ./gradlew build

# Docs
cd koupper-document && npm install && npm run docs:build
```

## Test Commands

```bash
# All tests in a module
cd koupper && ./gradlew test
cd koupper-cli && ./gradlew test

# Single test class
./gradlew test --tests "com.koupper.providers.ProviderCatalogConsistencyTest"

# Single test in a specific submodule
./gradlew :providers:test --tests "com.koupper.providers.ProviderCatalogConsistencyTest"
```

All test configs use `failFast = true` — execution stops on first failure.

## Local Pre-Push Checks

Run before every push:

```powershell
# Windows
./scripts/ci/local-quick-checks.ps1 -Target all
```

```bash
# Linux/macOS
./scripts/ci/local-quick-checks.sh all
```

Targets: `core`, `cli`, `docs`, or `all`.

## Release Automation (Mandatory — No Manual git/gh Flows)

```bash
# High-velocity feature delivery (preferred)
koupper run scripts/release/fast-lane.kts '{"featureBranch":"feature/my-change","enableAutoMerge":true}'

# Dry run
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","dryRun":true}'

# Create PR and wait for CI (no auto-merge)
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","waitForCi":true,"mergeAfterCi":false}'

# Create PR, wait for CI, then merge
koupper run scripts/release/release-flow.kts '{"featureBranch":"feature/my-change","waitForCi":true,"mergeAfterCi":true,"adminMerge":true}'
```

See `scripts/release/README.md` for full flag reference.

## CI Gates

| Target | Gates |
|--------|-------|
| PRs → `develop` | `fast-checks-linux`, `Providers core consistency`, `Providers CLI consistency` |
| PRs/push → `main` | `smoke-windows`, `smoke-linux`, `install-uninstall-e2e-windows` |

Merge only when CI concludes `success`. Fix root cause before retrying — do not bypass checks.

## Architecture Overview

Koupper is a **Kotlin scripting runtime + CLI** for automation and infrastructure orchestration. It has three pillars:

### 1. `koupper/` — Octopus Engine (v6.3.1)

A JVM daemon that compiles, routes, and executes `.kts` scripts. Organized as a multi-module Gradle project:

- **octopus** — Main scripting runtime using Kotlin scripting APIs (JSR-223, scripting-jvm-host)
- **bootstrap** — HTTP entry point (Jersey 3.1.6 + Grizzly2 REST API)
- **container** — Custom dependency injection framework (not Spring)
- **providers** — 40+ cloud/infra Service Provider integrations (AWS, GitHub, Docker, SSH, PostgreSQL, Redis, SQLite, SQS, DynamoDB, Email, etc.)
- **orchestrator-core** — Pipeline and job orchestration engine
- **configurations** — Configuration management
- **os** — OS integration layer
- **logging** — Centralized logging
- **shared** — Common utilities and script dependencies

Dependency direction: `bootstrap → octopus → container, shared, providers, configurations, os, orchestrator-core, logging`

### 2. `koupper-cli/` — CLI (v4.7.1)

Terminal interface for script management and dispatch. Entry point: `com.koupper.cli.CommandManagerKt`. Communicates with Octopus via socket protocol. Commands: `run`, `new`, `help`, `provider list/info`, `serve`.

### 3. `koupper-document/` — Public Docs

VitePress-based docs site. Source lives in `koupper-document/docs/`. Validation: `npm run docs:check` (validates provider/CLI catalog sync).

## Script Execution Contract

- Each `.kts` script must have a single `@Export` annotated entrypoint (conventionally named `setup`)
- All cloud/infra actions go through Service Providers — no scattered SDK calls
- Use `dependsOn()` for pipeline orchestration
- JSON input/output supported for CLI integration

See `docs/SCRIPT_EXECUTION_CONTRACT.md` for the full contract.

## Documentation Hierarchy

| Location | Audience |
|----------|----------|
| `koupper-document/docs/` | Public (users) |
| `docs/` | Internal (maintainers) |
| `examples/*.kts` | Runnable reference scripts |

Key internal docs: `docs/MAINTAINER_GUIDE.md`, `docs/DOCUMENTATION_STANDARD.md`, `docs/NEXT_FEATURES_NOTES.md`.

## Key Technology Stack

- **Language:** Kotlin 2.0.20, Java 17+
- **Testing:** JUnit5, Kotest 5.9.1, Mockk 1.13.12
- **Async:** Kotlin Coroutines 1.9.0, Vertx 4.5.8
- **Serialization:** Jackson 2.17.2 with Kotlin module, SnakeYAML 2.2
- **Build:** Gradle with wrapper
