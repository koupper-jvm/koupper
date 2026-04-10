# Setup Helpers

These scripts provide one-command prerequisite checks + installer execution.

## Linux / macOS

```bash
chmod +x ./scripts/setup/install.sh
./scripts/setup/install.sh
```

Auto-install/update missing dependencies (when supported):

```bash
./scripts/setup/install.sh --auto-install-deps
```

Non-interactive auto mode:

```bash
./scripts/setup/install.sh --auto-install-deps --yes
```

Doctor mode:

```bash
./scripts/setup/install.sh --doctor
```

## Windows (PowerShell)

```powershell
./scripts/setup/install.ps1
```

Auto-install/update missing dependencies:

```powershell
./scripts/setup/install.ps1 -AutoInstallDeps
```

Non-interactive auto mode:

```powershell
./scripts/setup/install.ps1 -AutoInstallDeps -Yes
```

Doctor mode:

```powershell
./scripts/setup/install.ps1 -Doctor
```

## What it checks

- Java 17+
- Kotlin compiler 2.0.0+ (`kotlinc`)
- Git (recommended)

If something is missing, the script prints exact requirements and download links.

When `--auto-install-deps`/`-AutoInstallDeps` is enabled, the script first validates installed versions and then asks for confirmation before updating incompatible dependencies.
