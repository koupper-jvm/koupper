# Setup Helpers

These scripts provide one-command prerequisite checks + installer execution.

## Linux / macOS

```bash
chmod +x ./scripts/setup/install.sh
./scripts/setup/install.sh
```

Doctor mode:

```bash
./scripts/setup/install.sh --doctor
```

## Windows (PowerShell)

```powershell
./scripts/setup/install.ps1
```

Doctor mode:

```powershell
./scripts/setup/install.ps1 -Doctor
```

## What it checks

- Java 17+
- Kotlin compiler (`kotlinc`)
- Git (recommended)

If something is missing, the script prints exact requirements and download links.
