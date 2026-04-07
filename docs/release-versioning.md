# Release Versioning

This project uses `develop` as the integration branch and `main/master` as stable release branches.

## Recommended Policy

- `develop`
  - integrate features and fixes continuously.
  - run fast checks on pull requests.
- `main/master`
  - receive only validated release-ready merges from `develop`.
  - run full smoke and release-critical workflows.
- tags (`v*`)
  - represent immutable released versions.

## Version Source of Truth

- Current version is defined in `koupper/build.gradle` as:
  - `version = 'x.y.z'`

## SemVer Bump Rule

- `patch`: backward-compatible fixes.
- `minor`: backward-compatible features.
- `major`: breaking changes.

## Koupper Release Scripts

- Dry-run next patch bump:

```bash
koupper run scripts/release/version-bump.kts '{"bump":"patch","dryRun":true}'
```

- Apply exact version bump:

```bash
koupper run scripts/release/version-bump.kts '{"targetVersion":"6.4.0","dryRun":false}'
```

- Dry-run tag creation:

```bash
koupper run scripts/release/tag-release.kts '{"version":"6.4.0","dryRun":true}'
```

- Create and push release tag:

```bash
koupper run scripts/release/tag-release.kts '{"version":"6.4.0","push":true,"dryRun":false}'
```
