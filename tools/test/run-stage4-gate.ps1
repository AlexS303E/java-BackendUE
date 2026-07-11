param(
    [ValidateSet("Fast", "Release")]
    [string]$Mode = "Fast",
    [string]$RepoRoot = "",
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipDocker,
    [switch]$SkipOpenApi,
    [switch]$SkipBootJar,
    [switch]$SkipProdSmoke,
    [switch]$SkipMtlsSmoke,
    [switch]$SkipLoadSmoke
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }

    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

function Invoke-CheckedStep {
    param(
        [string]$Name,
        [scriptblock]$Action
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    & $Action

    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$runAllTests = Join-Path $root "tools\test\run-all-tests.ps1"
$prodSmoke = Join-Path $root "tools\smoke\prod-profile-smoke.ps1"
$mtlsSmoke = Join-Path $root "tools\mtls\run-mtls-smoke.ps1"
$loadSmoke = Join-Path $root "tools\load\run-load-smoke.ps1"
$backendDir = Join-Path $root "backend"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"

foreach ($script in @($runAllTests, $prodSmoke, $mtlsSmoke, $loadSmoke)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Required Stage 4 gate script was not found: $script"
    }
}

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper was not found: $gradleWrapper"
}

Write-Host "Stage 4 gate mode: $Mode"

Invoke-CheckedStep "Stage 4 fast gate: Gradle tests and OpenAPI contract verification" {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $runAllTests `
        -RepoRoot $root `
        -JavaHome $JavaHome `
        -SkipDocker:$SkipDocker `
        -SkipOpenApi:$SkipOpenApi
}

if ($Mode -eq "Release") {
    if (-not $SkipBootJar) {
        Invoke-CheckedStep "Stage 4 release gate: bootJar" {
            Push-Location $backendDir
            try {
                & $gradleWrapper --no-daemon bootJar
            } finally {
                Pop-Location
            }
        }
    }

    if (-not $SkipProdSmoke) {
        Invoke-CheckedStep "Stage 4 release gate: production profile smoke" {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $prodSmoke -RepoRoot $root -SkipDocker:$SkipDocker
        }
    }

    if (-not $SkipMtlsSmoke) {
        Invoke-CheckedStep "Stage 4 release gate: mTLS smoke" {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $mtlsSmoke -RepoRoot $root -SkipDocker:$SkipDocker
        }
    }

    if (-not $SkipLoadSmoke) {
        Invoke-CheckedStep "Stage 4 release gate: load smoke" {
            & powershell -NoProfile -ExecutionPolicy Bypass -File $loadSmoke
        }
    }
}

Write-Host ""
Write-Host "Stage 4 gate passed." -ForegroundColor Green
