<p align="center">
  <img alt="Koupper Octopus" src="koupper-avatar.svg" width="220">
</p>

# Koupper

Koupper is a Kotlin scripting runtime + CLI built for teams that want fast script iteration with production-grade execution.

Core promise:

- write small Kotlin scripts,
- execute through a stable Octopus runtime contract,
- scale behavior with Service Providers,
- move from local flows to production without rewriting the architecture.

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

## Quick smoke

```bash
koupper help
koupper run examples/hello-world.kts "Smoke"
koupper provider list
```

## Why teams choose Koupper

- Kotlin-first, type-safe scripts instead of ad-hoc shell glue.
- Provider-first architecture for cloud, infra, and workflow capabilities.
- Local-first developer workflow with production hardening paths.
- Predictable runtime contract (`@Export` single entrypoint + pipeline orchestration).

## Maintainer docs in this repo

- Maintainer index: `docs/MAINTAINER_GUIDE.md`
- Documentation ownership standard: `docs/DOCUMENTATION_STANDARD.md`

## License

MIT
