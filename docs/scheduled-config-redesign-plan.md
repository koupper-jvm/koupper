# Scheduled Config Redesign Plan

Goal: make `@Scheduled` truly configurable via `schedules.json`, without forcing the file when annotation data is sufficient.

## Current pain points

- `@Scheduled` fails hard when `schedules.json` does not exist.
- `schedules.json` is loaded but most fields are not used in `ScheduledSetup` execution.
- No explicit override precedence between annotation and config file.

## Design rules

1. If `schedules.json` is missing, `@Scheduled` must still run using annotation values.
2. If `schedules.json` exists and matches target/config, it must override annotation values.
3. `enabled=false` in config must disable job scheduling.
4. `driver/queue` must be consumed by runtime logic when relevant.

## Proposed schedules.json shape

```json
[
  {
    "id": "default",
    "enabled": true,
    "driver": "file",
    "queue": "default",
    "ignore-on-processing": false,
    "for-all-projects": true,
    "target": {
      "script": "src/main/kotlin/com/example/scripts/myjob.kts",
      "export": "setup"
    },
    "schedule": {
      "mode": "rate",
      "rateMs": 5000,
      "cron": null,
      "delayMs": null,
      "at": null,
      "timezone": "UTC"
    },
    "retry": {
      "maxAttempts": 3,
      "backoffMs": 2000
    },
    "logging": {
      "level": "INFO",
      "debug": false
    }
  }
]
```

## Precedence

1. `schedules.json` matched entry
2. `@Scheduled(...)` annotation params
3. framework defaults

## Acceptance criteria

- `@Scheduled(rate=...)` works without `schedules.json`.
- With matching config entry, `mode/rate/cron/delay/at/debug` are overridden from file.
- `enabled=false` returns disabled status and does not schedule.
- Existing minimal config remains backward compatible.
