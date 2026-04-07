<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="koupper-document/docs/public/koupper-logo.svg">
    <source media="(prefers-color-scheme: light)" srcset="koupper-document/docs/public/koupper-white-mode-logo.svg">
    <img alt="Koupper Mascot" src="koupper-document/docs/public/koupper-logo.svg" width="200">
  </picture>
</p>

<h1 align="center">Koupper</h1>

Koupper is a Kotlin scripting runtime + CLI for local-first automation and production execution.

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

## Contributor docs in this repo

- Internal docs folder: `docs/`
- Production hardening: `docs/PRODUCTION_HARDENING.md`
- CLI checklist: `docs/CLI_COMMAND_CHECKLIST.md`
- Release versioning: `docs/release-versioning.md`
- Documentation ownership standard: `docs/DOCUMENTATION_STANDARD.md`

## License

MIT
