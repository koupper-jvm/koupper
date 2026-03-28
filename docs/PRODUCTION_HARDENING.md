# Koupper Production Hardening Guide

This guide provides a minimal, practical checklist to run Koupper safely in production.

## 1) Run Octopus with auth enabled

Set a strong token and never expose an unauthenticated daemon:

```bash
export KOUPPER_OCTOPUS_TOKEN="replace-with-long-random-token"
```

`koupper deploy` requires this token on both sides.

## 2) Bind host intentionally

- Default recommended: loopback only (`127.0.0.1`)
- If remote access is needed, bind explicitly and restrict at firewall level:

```bash
export KOUPPER_OCTOPUS_HOST="0.0.0.0"
export KOUPPER_OCTOPUS_PORT="9998"
```

## 3) Restrict network access

Allow inbound port `9998` only from trusted source IPs/VPN ranges.

Example UFW (Linux):

```bash
sudo ufw allow from 10.0.0.0/24 to any port 9998 proto tcp
sudo ufw deny 9998/tcp
```

## 4) Enforce deploy size limits

Limit deploy payload size to reduce abuse and accidental large transfers:

```bash
export KOUPPER_OCTOPUS_DEPLOY_MAX_BYTES="262144"
```

Default is `262144` bytes (256 KB).

## 5) Keep run-from-url disabled unless required

The secure default is disabled:

```bash
export KOUPPER_ENABLE_RUN_FROM_URL="false"
```

If you enable it, prefer HTTPS and a strict allowlist strategy at infrastructure level.

## 6) Run as a managed service

Use `systemd` with restart policy and dedicated user. A starter template is available at:

- `scripts/octopus.service`

Recommended hardening additions:

- dedicated non-root service user
- `NoNewPrivileges=true`
- limited file permissions on `.koupper` directories

## 7) Rotate and protect secrets

- Rotate `KOUPPER_OCTOPUS_TOKEN` regularly.
- Store token in secret manager or protected env file.
- Never hardcode secrets in scripts or repo files.

## 8) Logging and monitoring baseline

Track at minimum:

- daemon startup and bind host/port
- auth rejections (`Unauthorized`)
- deploy failures (hash mismatch, max size exceeded)
- script execution failures and durations

Use this data for alerting and incident response.

## 9) Release hygiene

- Keep CLI/Octopus versions aligned by release notes.
- Prefer tagged releases (`cli-v*`, `octopus-v*`, optional `koupper-v*`).
- Validate with smoke tests after each upgrade.

## 10) Post-deploy smoke checks

Run a quick validation after startup:

```bash
koupper run examples/hello-world.kts "Prod"
koupper deploy examples/hello-world.kts "<host>:9998"
```

If deploy fails, verify token, firewall, and `KOUPPER_OCTOPUS_DEPLOY_MAX_BYTES`.
