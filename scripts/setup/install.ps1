param(
    [switch]$Doctor,
    [switch]$AutoInstallDeps,
    [switch]$Yes
)

$ErrorActionPreference = "Stop"

function Write-Ok([string]$Message) { Write-Host "[OK] $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "[WARN] $Message" -ForegroundColor Yellow }
function Write-Fail([string]$Message) { Write-Host "[FAIL] $Message" -ForegroundColor Red }
function Write-Info([string]$Message) { Write-Host "[*] $Message" -ForegroundColor Cyan }

function Test-Command([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

function Confirm-Action([string]$Prompt) {
    if ($Yes) { return $true }
    $answer = Read-Host "$Prompt [y/N]"
    return $answer -match '^(y|yes)$'
}

function Get-JavaMajorVersion {
    if (-not (Test-Command "java")) { return $null }
    $javaVersionRaw = (& java -version 2>&1 | Select-Object -First 1)
    if ($javaVersionRaw -match 'version "(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-KotlinVersion {
    if (-not (Test-Command "kotlinc")) { return $null }
    $kotlinVersionRaw = (& kotlinc -version 2>&1 | Select-Object -First 1)
    if ($kotlinVersionRaw -match '(\d+\.\d+\.\d+)') {
        return [version]$Matches[1]
    }
    return $null
}

function Install-WithWinget {
    param([string]$Id)
    & winget install --id $Id --accept-package-agreements --accept-source-agreements --silent
    return $LASTEXITCODE -eq 0
}

function Install-WithChoco {
    param([string]$Package)
    & choco install $Package -y
    return $LASTEXITCODE -eq 0
}

function Try-AutoInstallDependencies {
    $javaMajor = Get-JavaMajorVersion
    $kotlinVersion = Get-KotlinVersion

    $javaIssue = $null
    $kotlinIssue = $null

    if ($null -eq $javaMajor -or $javaMajor -lt 17) {
        $javaIssue = "Java 17+ is required"
    }

    if ($null -eq $kotlinVersion -or $kotlinVersion -lt [version]"2.0.0") {
        $kotlinIssue = "Kotlin compiler 2.0.0+ is required"
    }

    if ($null -eq $javaIssue -and $null -eq $kotlinIssue) {
        return
    }

    if (-not $AutoInstallDeps) {
        return
    }

    Write-Warn "Auto-install/update requested for missing or incompatible prerequisites"
    if ($javaIssue) { Write-Warn $javaIssue }
    if ($kotlinIssue) { Write-Warn $kotlinIssue }

    if (-not (Confirm-Action "Proceed with automatic dependency install/update?")) {
        Write-Warn "Skipping auto-install/update by user choice."
        return
    }

    $hasWinget = Test-Command "winget"
    $hasChoco = Test-Command "choco"

    if (-not $hasWinget -and -not $hasChoco) {
        Write-Warn "Neither winget nor choco was found. Cannot auto-install dependencies on this machine."
        return
    }

    if ($javaIssue) {
        Write-Info "Installing/updating Java 17+..."
        $javaOk = $false
        if ($hasWinget) {
            $javaOk = Install-WithWinget -Id "EclipseAdoptium.Temurin.17.JDK"
        }
        if (-not $javaOk -and $hasChoco) {
            $javaOk = Install-WithChoco -Package "temurin17"
        }
        if (-not $javaOk) {
            Write-Warn "Automatic Java install did not complete."
        }
    }

    if ($kotlinIssue) {
        Write-Info "Installing/updating Kotlin compiler..."
        $kotlinOk = $false
        if ($hasWinget) {
            $kotlinOk = Install-WithWinget -Id "JetBrains.KotlinCompiler"
        }
        if (-not $kotlinOk -and $hasChoco) {
            $kotlinOk = Install-WithChoco -Package "kotlin-compiler"
        }
        if (-not $kotlinOk) {
            Write-Warn "Automatic Kotlin install did not complete."
        }
    }
}

Try-AutoInstallDependencies

$hasFail = $false

if (Test-Command "java") {
    $javaVersionRaw = (& java -version 2>&1 | Select-Object -First 1)
    $major = Get-JavaMajorVersion

    if ($major -ge 17) {
        Write-Ok "Java detected ($javaVersionRaw)"
    } else {
        $hasFail = $true
        Write-Fail "Java 17+ is required (detected: $javaVersionRaw)"
    }
} else {
    $hasFail = $true
    Write-Fail "Java is not available in PATH"
}

if (Test-Command "kotlinc") {
    $kotlinVersionRaw = (& kotlinc -version 2>&1 | Select-Object -First 1)
    $kotlinVersion = Get-KotlinVersion
    if ($kotlinVersion -ge [version]"2.0.0") {
        Write-Ok "Kotlin compiler detected ($kotlinVersionRaw)"
    } else {
        $hasFail = $true
        Write-Fail "Kotlin compiler 2.0.0+ is required (detected: $kotlinVersionRaw)"
    }
} else {
    $hasFail = $true
    Write-Fail "kotlinc is not available in PATH"
}

if (Test-Command "git") {
    $gitVersion = (& git --version 2>$null)
    Write-Ok "Git detected ($gitVersion)"
} else {
    Write-Warn "Git not found in PATH (only required if you clone/update repositories from CLI)"
}

if ($hasFail) {
    Write-Host ""
    Write-Host "Missing prerequisites detected." -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Required:" -ForegroundColor White
    Write-Host "- Java 17+" -ForegroundColor White
    Write-Host "  Download: https://adoptium.net/temurin/releases/" -ForegroundColor Gray
    Write-Host "- Kotlin compiler 2.0.0+ (kotlinc)" -ForegroundColor White
    Write-Host "  Download: https://kotlinlang.org/docs/command-line.html" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Optional but recommended:" -ForegroundColor White
    Write-Host "- Git" -ForegroundColor White
    Write-Host "  Download: https://git-scm.com/downloads" -ForegroundColor Gray
    Write-Host ""
    Write-Host "After installing prerequisites, open a new terminal and run again:" -ForegroundColor Yellow
    Write-Host "  .\scripts\setup\install.ps1" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Optional auto mode:" -ForegroundColor Yellow
    Write-Host "  .\scripts\setup\install.ps1 -AutoInstallDeps" -ForegroundColor Cyan
    exit 1
}

if ($Doctor) {
    Write-Info "Prerequisites are healthy. Running Koupper install doctor..."
    & kotlinc -script install.kts -- --doctor
    exit $LASTEXITCODE
}

Write-Info "Prerequisites are healthy. Running Koupper installer..."
& kotlinc -script install.kts -- --force
exit $LASTEXITCODE
