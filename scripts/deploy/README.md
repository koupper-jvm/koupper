# AWS Deploy SP Orchestrator

This folder provides a reference deploy flow that uses `AwsDeployProvider` from the Koupper container.

## Files

- `aws-release-flow.kts`: end-to-end deploy orchestration using the provider.
- `deploy.environments.example.yaml`: example config for environments/modules.

## Usage

```bash
koupper run scripts/deploy/aws-release-flow.kts '{"env":"dev","dryRun":true,"only":"all","skipSmoke":false,"strictPreflight":false,"configFile":"deploy.environments.yaml"}'
```

## Config shape

- `environments.<env>.modules.<name>.type`: `backend_lambda` or `frontend_static`.
- `backend_lambda`: `projectPath`, `buildCommand`, `artifactPath`, `alias`, `lambdas`.
- `frontend_static`: `projectPath`, `buildCommand`, `distPath`, `bucket`, `cloudfrontDistributionId`.
- `smoke.apis[]`: supports `baseUrl` or `apiGatewayId + stage + path`.

## Provider env variables

- `AWS_COMMAND` (default `aws`)
- `AWS_REGION` (default `us-east-1`)
- `AWS_DEPLOY_TIMEOUT_SECONDS` (default `300`)
