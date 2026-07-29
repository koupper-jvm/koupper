<p align="center">
  <img alt="Koupper Octopus" src="koupper-avatar.svg" width="220">
</p>

# Koupper

<p align="left">
  <a href="https://github.com/koupper-jvm/koupper/blob/develop/LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
  <a href="https://github.com/koupper-jvm/koupper/commits/develop"><img alt="Last commit" src="https://img.shields.io/github/last-commit/koupper-jvm/koupper/develop"></a>
  <img alt="Kotlin-first" src="https://img.shields.io/badge/language-Kotlin-7f52ff">
  <img alt="Runtime" src="https://img.shields.io/badge/runtime-Octopus-0f172a">
  <img alt="Architecture" src="https://img.shields.io/badge/architecture-provider--first-0284c7">
  <a href="https://koupper.com/"><img alt="Docs" src="https://img.shields.io/badge/docs-koupper.com-0ea5e9"></a>
</p>

Koupper is a Kotlin scripting runtime + CLI for teams that want fast iteration and production-grade execution in the same model.

> **Current release:** [v7.2.1](https://github.com/koupper-jvm/koupper/releases/tag/v7.2.1) · **Docs:** [koupper.com](https://koupper.com/)  
> **Distribution:** GitHub Releases (not Maven Central). Install also places `com.koupper:octopus-api` in **mavenLocal** for Gradle modules.

## Quick install (standalone, no repo clone)

Prerequisites: Java 17+ and Kotlin compiler (`kotlinc`) on your `PATH`.

Same command for **first install** and **upgrade** (`--force` replaces jars + republishes mavenLocal):

```bash
curl -L -o install-standalone.kts https://github.com/koupper-jvm/koupper/releases/latest/download/install-standalone.kts
kotlinc -script install-standalone.kts -- --force
```

Windows PowerShell:

```powershell
Invoke-WebRequest -Uri "https://github.com/koupper-jvm/koupper/releases/latest/download/install-standalone.kts" -OutFile "install-standalone.kts"
kotlinc -script .\install-standalone.kts -- --force
```

Health check:

```bash
kotlinc -script install-standalone.kts -- --doctor
```

```powershell
kotlinc -script .\install-standalone.kts -- --doctor
```

The standalone installer downloads signed release assets (`koupper-cli.jar`, `octopus.jar` fat runtime, `octopus-api.jar` light compile jar, `model-project.zip`, `providers.json`) and verifies them with `SHA256SUMS`.

- **Runtime:** `~/.koupper/libs/octopus.jar` (daemon / `koupper run`)
- **Compile (Gradle):** `com.koupper:octopus-api:<version>` via `mavenLocal()` after install

```gradle
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("com.koupper:octopus-api:7.2.1") }
```

If `koupper module <name>` fails with `.../.koupper/helpers/list.kts` on an older local install, create the missing runtime folders once and rerun:

```bash
mkdir -p "$HOME/.koupper/helpers" "$HOME/.koupper/logs"
```

Windows PowerShell:

```powershell
New-Item -ItemType Directory -Force "$env:USERPROFILE\.koupper\helpers" | Out-Null
New-Item -ItemType Directory -Force "$env:USERPROFILE\.koupper\logs" | Out-Null
```

Why it matters:

- write small Kotlin scripts,
- execute through a stable Octopus runtime contract,
- scale capabilities through Service Providers,
- move from local flows to production without rewriting your model.

Tech tags:

`kotlin` `scripting` `automation` `octopus-runtime` `provider-first` `jobs` `deploy`

## Start here

- Public docs site: https://koupper.com/
- Getting started: https://koupper.com/getting-started.html
- Command reference: https://koupper.com/commands/
- Provider catalog: https://koupper.com/providers/
- Agentic Core: https://koupper.com/agentic-core/

## Installation modes

- End user install (`install-standalone.kts`): no repository clone, installs from latest release assets into `~/.koupper`.
- Developer install (`install.kts`): clone the repository and build/install from source in your local workspace.
- Both modes install runtime files under `~/.koupper`; the difference is where binaries/templates come from (release assets vs local source build).

## Developer/maintainer workspace install

```bash
git clone https://github.com/koupper-jvm/koupper-workspace.git "koupper workspace"
cd "koupper workspace"
bash ./scripts/setup/workspace-bootstrap.sh --workspace "$(pwd)" --pull
```

Windows PowerShell:

```powershell
git clone https://github.com/koupper-jvm/koupper-workspace.git "koupper workspace"
cd "koupper workspace"
./scripts/setup/workspace-bootstrap.ps1 -Workspace (Get-Location).Path -Pull
```

## 60-second quick smoke

```bash
koupper help
koupper provider list
koupper provider info command-runner
```

Expected result:

- CLI responds,
- script execution works,
- provider catalog is discoverable.

## Why Koupper vs typical scripting stacks

- **Single runtime contract**: local CLI, worker jobs, and deploy/runtime routes share the same execution rules.
- **Provider-first architecture**: integrations are explicit contracts, not scattered SDK calls.
- **Kotlin type safety**: better maintainability than ad-hoc shell scripts as automation grows.
- **Production path built-in**: auth/checksum guardrails, hardening docs, and release automation scripts.

## Why teams choose Koupper

- **Kotlin-first, type-safe scripts** instead of ad-hoc shell glue.
- **Production-grade Web Engine**: Powered by Grizzly NIO for high-concurrency APIs.
- **Provider-first architecture** with tier classification (CORE / COMMUNITY / EXPERIMENTAL).
- **Declarative Security**: JWT auth with scopes (`koupper:read`, `koupper:execute`, `koupper:admin`) + `@Auth`/`@Authorize` annotations.
- **HTTP REST API** (port 9997): Run scripts, health checks, status, job queues via JDK `HttpServer`.
- **gRPC Bidirectional Streaming** (port 9996): Real-time job dispatch and status updates.
- **Prometheus Metrics** (port 9999): Runtime observability in exposition format.
- **OpenTelemetry Tracing**: Automatic span creation with W3C context propagation.
- **Script Sandboxing**: Process Isolation via JVM ProcessBuilder, timeout enforcement, `System.exit()` interception, thread isolation.
- **Real-Time Streaming**: Server-Sent Events (SSE) out-of-the-box for logging long-running background tasks to frontend clients.
- **Hot-Reloading**: Dynamic plugin and provider reloading via custom `URLClassLoader` and `koupper reload` CLI without Daemon restarts.
- **KSP Annotation Processing**: Compiler-accurate `@Export`/`@Scheduled`/`@Pipeline` extraction (no regex guessing).
- **Local-first developer workflow** with production hardening paths.
- **Predictable runtime contract** (`@Export` single entrypoint + pipeline orchestration).

## Typical use cases

- **High-performance HTTP APIs**: Backed by Grizzly, featuring native Multipart parsing, CORS support, and Global Exception Handling.
- **Script-driven backend workers** and async jobs.
- **Deployment orchestration** and infra workflows.
- **Operational automations** (GitHub, Docker, SSH, notifications, queue ops).
- **AI-Native Agents**: Build 100% local autonomous agents with the built-in **Agentic Core** and ReAct loop.
- **LLM pipelines** with typed script inputs and structured output validation.

## Documentation hierarchy

- Public docs (users): [koupper.com](https://koupper.com/) and [koupper-docs repo](https://github.com/koupper-jvm/koupper-docs)
- Internal docs (maintainers): [koupper-workspace/docs](https://github.com/koupper-jvm/koupper-workspace/tree/develop/docs)
- Runnable references: [koupper-workspace/examples](https://github.com/koupper-jvm/koupper-workspace/tree/develop/examples)

Internal docs are maintainer/operator playbooks; product-facing documentation lives in `koupper-docs`.

Recommended reading path:

1. [Getting Started](https://koupper.com/getting-started.html)
2. [Why Koupper vs Alternatives](https://koupper.com/why-koupper-vs-alternatives.html)
3. [Ideal Customer Profile](https://koupper.com/ideal-customer-profile.html)
4. [Use Cases](https://koupper.com/use-cases.html)
5. [Golden Demo: Worker Flow](https://koupper.com/examples/golden-demo-worker-flow.html)
6. [Command Overview](https://koupper.com/commands/)
7. [Provider Catalog](https://koupper.com/providers/)
8. [Architecture](https://koupper.com/architecture/)
9. [Production](https://koupper.com/production/hardening.html)

## Contributing

- Core contribution flow and maintainer docs: [koupper-workspace/docs/MAINTAINER_GUIDE.md](https://github.com/koupper-jvm/koupper-workspace/blob/develop/docs/MAINTAINER_GUIDE.md)
- Documentation governance rules: [koupper-workspace/docs/DOCUMENTATION_STANDARD.md](https://github.com/koupper-jvm/koupper-workspace/blob/develop/docs/DOCUMENTATION_STANDARD.md)
- Public docs source: [koupper-jvm/koupper-docs](https://github.com/koupper-jvm/koupper-docs)

## Maintainer docs in this repo

- Maintainer index: [koupper-workspace/docs/MAINTAINER_GUIDE.md](https://github.com/koupper-jvm/koupper-workspace/blob/develop/docs/MAINTAINER_GUIDE.md)
- Documentation ownership standard: [koupper-workspace/docs/DOCUMENTATION_STANDARD.md](https://github.com/koupper-jvm/koupper-workspace/blob/develop/docs/DOCUMENTATION_STANDARD.md)

## License

MIT
