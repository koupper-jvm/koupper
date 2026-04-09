<p align="center">
  <img alt="Koupper Octopus" src="koupper-avatar.svg" width="220">
</p>

# Koupper

Koupper is a Kotlin scripting runtime + CLI for teams that want to ship automation quickly without sacrificing production discipline.

Why it matters:

- write small Kotlin scripts,
- execute through a stable Octopus runtime contract,
- scale capabilities through Service Providers,
- move from local flows to production without rewriting your model.

## Start here

- Public docs site: https://koupper.com/docs
- Getting started: https://koupper.com/docs/getting-started
- Command reference: https://koupper.com/docs/commands/
- Provider catalog: https://koupper.com/docs/providers/

## Quick install

```bash
git clone https://github.com/koupper-jvm/koupper.git
cd koupper
kotlinc -script install.kts -- --force
koupper -v
```

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

## Why teams choose Koupper

- Kotlin-first, type-safe scripts instead of ad-hoc shell glue.
- Provider-first architecture for cloud, infra, and workflow capabilities.
- Local-first developer workflow with production hardening paths.
- Predictable runtime contract (`@Export` single entrypoint + pipeline orchestration).

## Documentation hierarchy

- Public docs (users): `koupper-document/docs`
- Internal docs (maintainers): `docs/`
- Runnable references: `examples/`

Recommended reading path:

1. [Getting Started](https://koupper.com/docs/getting-started)
2. [Command Overview](https://koupper.com/docs/commands/)
3. [Provider Catalog](https://koupper.com/docs/providers/)
4. [Architecture](https://koupper.com/docs/architecture/)
5. [Production](https://koupper.com/docs/production/hardening)

## Maintainer docs in this repo

- Maintainer index: `docs/MAINTAINER_GUIDE.md`
- Documentation ownership standard: `docs/DOCUMENTATION_STANDARD.md`

## License

MIT
