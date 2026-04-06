# Scheduled Examples

## 1) Without `schedules.json`

- Script: `examples/scheduled/no-config/logger-scheduled-no-config.kts`
- Uses annotation values directly (`@Scheduled(rate = 7000, debug = true)`).

Run:

```bash
koupper run examples/scheduled/no-config/logger-scheduled-no-config.kts "demo"
```

## 2) With `schedules.json` override

- Script: `examples/scheduled/with-config/logger-scheduled-with-config.kts`
- File: `examples/scheduled/with-config/schedules.json`
- Annotation has `rate = 20000`, but schedules.json overrides to `rateMs = 3000`.

Run:

```bash
koupper run examples/scheduled/with-config/logger-scheduled-with-config.kts "demo"
```

Notes:

- `target.script` is relative to the script context folder.
- If `enabled` is `false`, scheduling is skipped.
