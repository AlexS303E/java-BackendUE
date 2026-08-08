param(
    [string]$RepoRoot,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$SkipDocker,
    [switch]$SkipOpenApi
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ExplicitRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($ExplicitRepoRoot)) {
        return (Resolve-Path -LiteralPath $ExplicitRepoRoot).Path
    }

    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path -LiteralPath (Join-Path $scriptDir "..\..")).Path
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

function Use-JavaHome {
    param([string]$RequestedJavaHome)

    $resolvedJavaHome = $RequestedJavaHome
    if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
        $localDefault = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
        if (Test-Path -LiteralPath (Join-Path $localDefault "bin\java.exe")) {
            $resolvedJavaHome = $localDefault
        }
    }

    if ([string]::IsNullOrWhiteSpace($resolvedJavaHome)) {
        Write-Host "JAVA_HOME is not set; using java from PATH." -ForegroundColor Yellow
        return
    }

    $javaExecutableName = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        "java.exe"
    } else {
        "java"
    }
    $javaExe = Join-Path $resolvedJavaHome (Join-Path "bin" $javaExecutableName)
    if (-not (Test-Path -LiteralPath $javaExe)) {
        throw "JAVA_HOME does not contain bin\${javaExecutableName}: $resolvedJavaHome"
    }

    $env:JAVA_HOME = $resolvedJavaHome
    $env:Path = (Join-Path $resolvedJavaHome "bin") + [IO.Path]::PathSeparator + $env:Path
    Write-Host "Using JAVA_HOME=$resolvedJavaHome"
}

function Resolve-PowerShellExecutable {
    if ($null -ne (Get-Command pwsh -ErrorAction SilentlyContinue)) {
        return "pwsh"
    }
    return "powershell"
}

function Resolve-GradleWrapper {
    param([string]$BackendDirectory)

    $wrapperName = if ([System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT) {
        "gradlew.bat"
    } else {
        "gradlew"
    }
    return (Join-Path $BackendDirectory $wrapperName)
}

$root = Resolve-RepoRoot -ExplicitRepoRoot $RepoRoot
$backendDir = Join-Path $root "backend"
$gradleWrapper = Resolve-GradleWrapper -BackendDirectory $backendDir
$powerShellExecutable = Resolve-PowerShellExecutable
$openApiScript = Join-Path $root "tools\openapi\verify-openapi-stage3.ps1"
$secretScanner = Join-Path $root "tools\security\scan-secrets.ps1"
$secretScannerTests = Join-Path $root "tools\security\test-secret-scan.ps1"
$backupParameterTests = Join-Path $root "tools\backup\test-backup-parameter-validation.ps1"
$tankArtifactHygieneTests = Join-Path $root "tools\load\tank\test-yandex-tank-artifact-hygiene.ps1"

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper was not found: $gradleWrapper"
}

foreach ($script in @($secretScanner, $secretScannerTests, $backupParameterTests, $tankArtifactHygieneTests)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Secret scanning script was not found: $script"
    }
}

Use-JavaHome -RequestedJavaHome $JavaHome

Invoke-CheckedStep "Scan tracked source and configuration for secrets" {
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $secretScanner -RepoRoot $root
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $secretScannerTests -RepoRoot $root
}

Invoke-CheckedStep "Validate backup and restore command parameters" {
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $backupParameterTests -RepoRoot $root
}

Invoke-CheckedStep "Validate load-test artifact hygiene" {
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $tankArtifactHygieneTests -RepoRoot $root
}

if (-not $SkipDocker) {
    Invoke-CheckedStep "Start docker test dependencies" {
        Push-Location $root
        try {
            & docker compose up -d postgres redis
        } finally {
            Pop-Location
        }
    }
}

Invoke-CheckedStep "Run backend Gradle tests" {
    Push-Location $backendDir
    try {
        & $gradleWrapper --no-daemon --dependency-verification=strict test
    } finally {
        Pop-Location
    }
}

if (-not $SkipOpenApi) {
    if (-not (Test-Path -LiteralPath $openApiScript)) {
        throw "OpenAPI verification script was not found: $openApiScript"
    }

    Invoke-CheckedStep "Verify OpenAPI contract" {
        & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $openApiScript -RepoRoot $root
    }
}

Write-Host ""
Write-Host "All tests/checks passed." -ForegroundColor Green
