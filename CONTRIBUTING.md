# Contributing to Koupper

Thanks for helping. This is the short public contract for users and contributors.

## Repos

| Repo | Purpose |
|------|---------|
| [koupper](https://github.com/koupper-jvm/koupper) | Octopus engine (runtime) |
| [koupper-cli](https://github.com/koupper-jvm/koupper-cli) | CLI |
| [koupper-docs](https://github.com/koupper-jvm/koupper-docs) | Public docs → [koupper.com](https://koupper.com/) |
| [koupper-workspace](https://github.com/koupper-jvm/koupper-workspace) | Maintainer monorepo, examples, release scripts |

## Use Koupper (no contribution required)

1. Install / upgrade from the latest GitHub Release (same command both times):

```bash
curl -L -o install-standalone.kts https://github.com/koupper-jvm/koupper/releases/latest/download/install-standalone.kts
kotlinc -script install-standalone.kts -- --force
```

2. Verify: `koupper -v` and `kotlinc -script install-standalone.kts -- --doctor`
3. In Gradle modules:

```gradle
repositories { mavenLocal(); mavenCentral() }
dependencies { implementation("com.koupper:octopus-api:7.2.1") }
```

Full guide: https://koupper.com/getting-started.html

Distribution is **GitHub Releases + mavenLocal** (not Maven Central).

## Contribute code (engine / CLI)

1. Branch from **`develop`**: `feature/...` or `fix/...`
2. Open a PR **into `develop`**
3. Wait for CI (fast checks on PRs to `develop`)
4. Maintainers merge; public runtime ships when a maintainer cuts a **`v*`** tag on `koupper` (publishes install assets)

Do not target `main` for day-to-day features. `main` is promoted for release alignment.

## Contribute docs

1. Branch from **`develop`** in `koupper-docs`
2. PR → **`develop`**
3. When ready to publish: merge **`develop` → `main`**
4. Push to **`main`** runs **Docs Deploy** (VitePress → S3 → CloudFront → koupper.com)

No docs tags required for each change.

## Releases (maintainers)

- Engine/CLI: semver + annotated tag `vX.Y.Z` on `koupper` → `Publish Install Assets` workflow
- Prefer release scripts in `koupper-workspace` (`scripts/release/`) over ad-hoc git/gh sequences
- Docs: merge to `main` only (auto-deploy)

## Local maintainer workspace

```bash
git clone https://github.com/koupper-jvm/koupper-workspace.git
cd koupper-workspace
# bootstrap clones koupper, koupper-cli, koupper-docs and installs from source
```

See [MAINTAINER_GUIDE.md](https://github.com/koupper-jvm/koupper-workspace/blob/develop/docs/MAINTAINER_GUIDE.md).

## License

By contributing you agree your work is MIT, same as the project.
