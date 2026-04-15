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

## Quick install

```bash
git clone https://github.com/koupper-jvm/koupper.git
cd koupper
./scripts/setup/install.sh
koupper -v
```

Windows PowerShell:

```powershell
git clone https://github.com/koupper-jvm/koupper.git
cd koupper
./scripts/setup/install.ps1
koupper -v
```

The setup scripts validate prerequisites first (Java 17+, `kotlinc`, Git) and print exact download links when something is missing.

Optional automatic dependency mode:

- macOS/Linux: `./scripts/setup/install.sh --auto-install-deps`
- Windows: `./scripts/setup/install.ps1 -AutoInstallDeps`

In auto mode, the installer validates current versions first and asks before updating incompatible prerequisites.

## 60-second quick smoke

```bash
koupper help
koupper run examples/hello-world.kts "Smoke"
koupper provider list
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

- Kotlin-first, type-safe scripts instead of ad-hoc shell glue.
- Provider-first architecture for cloud, infra, and workflow capabilities.
- Local-first developer workflow with production hardening paths.
- Predictable runtime contract (`@Export` single entrypoint + pipeline orchestration).

## Typical use cases

- script-driven backend workers and async jobs,
- deployment orchestration and infra workflows,
- runtime-exposed HTTP routes via providers,
- operational automations (GitHub, Docker, SSH, notifications, queue ops),
- AI/LLM pipelines with typed script inputs.

## Documentation hierarchy

- Public docs (users): `koupper-document/docs`
- Internal docs (maintainers): `docs/`
- Runnable references: `examples/`

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

- Core contribution flow and maintainer docs: `docs/MAINTAINER_GUIDE.md`
- Documentation governance rules: `docs/DOCUMENTATION_STANDARD.md`
- Public docs source: `koupper-document/docs`

## Maintainer docs in this repo

- Maintainer index: `docs/MAINTAINER_GUIDE.md`
- Documentation ownership standard: `docs/DOCUMENTATION_STANDARD.md`

## License

MIT
