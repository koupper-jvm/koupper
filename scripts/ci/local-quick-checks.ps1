param(
    [ValidateSet("core", "cli", "docs", "all")]
    [string]$Target = "all"
)

$ErrorActionPreference = "Stop"

function Run-Core {
    Write-Host "[ci] Running core/providers targeted checks"
    Push-Location "koupper"
    try {
        .\gradlew.bat :providers:test --tests "com.koupper.providers.ProviderCatalogConsistencyTest" --tests "com.koupper.providers.command.CommandRunnerServiceProviderTest"
    }
    finally {
        Pop-Location
    }
}

function Run-Cli {
    Write-Host "[ci] Running CLI targeted checks"
    Push-Location "koupper-cli"
    try {
        .\gradlew.bat test --tests "com.koupper.cli.commands.ProviderCommandCatalogPathTest"
    }
    finally {
        Pop-Location
    }
}

function Run-Docs {
    Write-Host "[ci] Running docs checks/build"
    Push-Location "koupper-document"
    try {
        npm run docs:check
        npm run docs:build
    }
    finally {
        Pop-Location
    }
}

switch ($Target) {
    "core" { Run-Core }
    "cli" { Run-Cli }
    "docs" { Run-Docs }
    "all" {
        Run-Core
        Run-Cli
        Run-Docs
    }
}

Write-Host "[ci] Quick checks completed for target: $Target"
