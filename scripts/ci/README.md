# Local Quick Checks

Use these scripts before pushing to keep PR validation fast and reliable.

## Targets

- `core`: provider-core targeted tests
- `cli`: provider CLI targeted tests
- `docs`: docs checks and build
- `all`: runs all targets in sequence

## Windows

```powershell
./scripts/ci/local-quick-checks.ps1 -Target all
./scripts/ci/local-quick-checks.ps1 -Target core
```

## Linux/macOS

```bash
chmod +x ./scripts/ci/local-quick-checks.sh
./scripts/ci/local-quick-checks.sh all
./scripts/ci/local-quick-checks.sh docs
```
