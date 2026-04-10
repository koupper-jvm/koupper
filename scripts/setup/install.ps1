param(
    [switch]$Doctor
)

$ErrorActionPreference = "Stop"

function Write-Ok([string]$Message) { Write-Host "[OK] $Message" -ForegroundColor Green }
function Write-Warn([string]$Message) { Write-Host "[WARN] $Message" -ForegroundColor Yellow }
function Write-Fail([string]$Message) { Write-Host "[FAIL] $Message" -ForegroundColor Red }
function Write-Info([string]$Message) { Write-Host "[*] $Message" -ForegroundColor Cyan }

function Test-Command([string]$Name) {
    return [bool](Get-Command $Name -ErrorAction SilentlyContinue)
}

$hasFail = $false

if (Test-Command "java") {
    $javaVersionRaw = (& java -version 2>&1 | Select-Object -First 1)
    $major = $null
    if ($javaVersionRaw -match 'version "(\d+)') {
        $major = [int]$Matches[1]
    }

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
    Write-Ok "Kotlin compiler detected ($kotlinVersionRaw)"
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
    Write-Host "- Kotlin compiler (kotlinc)" -ForegroundColor White
    Write-Host "  Download: https://kotlinlang.org/docs/command-line.html" -ForegroundColor Gray
    Write-Host ""
    Write-Host "Optional but recommended:" -ForegroundColor White
    Write-Host "- Git" -ForegroundColor White
    Write-Host "  Download: https://git-scm.com/downloads" -ForegroundColor Gray
    Write-Host ""
    Write-Host "After installing prerequisites, open a new terminal and run again:" -ForegroundColor Yellow
    Write-Host "  .\scripts\setup\install.ps1" -ForegroundColor Cyan
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
