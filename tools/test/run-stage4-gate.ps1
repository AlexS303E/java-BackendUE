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
    [switch]$SkipLoadSmoke,
    [switch]$ListSteps,
    [string]$SummaryPath = "artifacts/stage4/stage4-gate-summary.json",
    [switch]$NoSummary,
    [string]$SkipReason = ""
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
        [scriptblock]$Action,
        [pscustomobject]$Step = $null
    )

    Write-Host ""
    Write-Host "==> $Name" -ForegroundColor Cyan
    $global:LASTEXITCODE = 0
    $startedAt = (Get-Date).ToUniversalTime()
    if ($null -ne $Step) {
        $Step.started_at = $startedAt.ToString("o")
    }
    try {
        & $Action
    } finally {
        $finishedAt = (Get-Date).ToUniversalTime()
        if ($null -ne $Step) {
            $Step.finished_at = $finishedAt.ToString("o")
            $Step.duration_ms = [int][Math]::Round(($finishedAt - $startedAt).TotalMilliseconds)
        }
    }

    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE"
    }
}

function Add-GateStep {
    param(
        [System.Collections.Generic.List[object]]$Steps,
        [string]$Name,
        [bool]$Enabled
    )

    $status = if ($Enabled) {
        "run"
    } else {
        "skip"
    }

    $Steps.Add([pscustomobject]@{
        name = $Name
        status = $status
        started_at = $null
        finished_at = $null
        duration_ms = $null
    }) | Out-Null
}

function Test-ReleaseGateHasSkippedChecks {
    return [bool]($SkipOpenApi -or $SkipBootJar -or $SkipProdSmoke -or $SkipMtlsSmoke -or $SkipLoadSmoke)
}

function Resolve-RepoRevision {
    param([string]$RepositoryRoot)

    $revision = (& git -C $RepositoryRoot rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        return "unknown"
    }

    return $revision.Trim()
}

function Write-GateSummary {
    param(
        [string]$Path,
        [string]$GateMode,
        [string]$Result,
        [System.Collections.Generic.List[object]]$Steps,
        [string]$ErrorMessage = "",
        [Nullable[int]]$DurationMs = $null
    )

    if ($NoSummary -or [string]::IsNullOrWhiteSpace($Path)) {
        return
    }

    $resolvedPath = if ([IO.Path]::IsPathRooted($Path)) {
        $Path
    } else {
        Join-Path $root $Path
    }
    $summaryDir = Split-Path -Parent $resolvedPath
    if (-not [string]::IsNullOrWhiteSpace($summaryDir)) {
        New-Item -ItemType Directory -Path $summaryDir -Force | Out-Null
    }

    [pscustomobject]@{
        schema_name = "stage4_gate_summary"
        schema_version = 1
        stage = 4
        mode = $GateMode
        result = $Result
        repo_revision = Resolve-RepoRevision -RepositoryRoot $root
        generated_at = (Get-Date).ToUniversalTime().ToString("o")
        duration_ms = $DurationMs
        skip_docker = [bool]$SkipDocker
        skip_openapi = [bool]$SkipOpenApi
        skip_boot_jar = [bool]$SkipBootJar
        skip_prod_smoke = [bool]$SkipProdSmoke
        skip_mtls_smoke = [bool]$SkipMtlsSmoke
        skip_load_smoke = [bool]$SkipLoadSmoke
        skip_reason = $SkipReason
        error_message = $ErrorMessage
        steps = $Steps
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $resolvedPath -Encoding UTF8

    Write-Host "Stage 4 gate summary written to $resolvedPath"
    & powershell -NoProfile -ExecutionPolicy Bypass -File $summaryValidator -SummaryPath $resolvedPath
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$runAllTests = Join-Path $root "tools\test\run-all-tests.ps1"
$summaryValidator = Join-Path $root "tools\test\validate-stage4-summary.ps1"
$prodSmoke = Join-Path $root "tools\smoke\prod-profile-smoke.ps1"
$mtlsSmoke = Join-Path $root "tools\mtls\run-mtls-smoke.ps1"
$loadSmoke = Join-Path $root "tools\load\run-load-smoke.ps1"
$backendDir = Join-Path $root "backend"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"

foreach ($script in @($runAllTests, $summaryValidator, $prodSmoke, $mtlsSmoke, $loadSmoke)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Required Stage 4 gate script was not found: $script"
    }
}

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper was not found: $gradleWrapper"
}

Write-Host "Stage 4 gate mode: $Mode"

if ($Mode -eq "Release" -and (Test-ReleaseGateHasSkippedChecks) -and [string]::IsNullOrWhiteSpace($SkipReason)) {
    throw "Stage 4 Release gate skip switches require -SkipReason so release evidence explains the omission."
}

$plannedSteps = New-Object 'System.Collections.Generic.List[object]'
Add-GateStep $plannedSteps "fast gate: Gradle tests and OpenAPI contract verification" $true
if ($Mode -eq "Release") {
    Add-GateStep $plannedSteps "release gate: bootJar" (-not $SkipBootJar)
    Add-GateStep $plannedSteps "release gate: production profile smoke" (-not $SkipProdSmoke)
    Add-GateStep $plannedSteps "release gate: mTLS smoke" (-not $SkipMtlsSmoke)
    Add-GateStep $plannedSteps "release gate: load smoke" (-not $SkipLoadSmoke)
}

if ($ListSteps) {
    Write-Host ""
    Write-Host "Planned Stage 4 gate steps:"
    foreach ($step in $plannedSteps) {
        Write-Host "- [$($step.status)] $($step.name)"
    }
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "planned" -Steps $plannedSteps
    exit 0
}

try {
    $gateStartedAt = (Get-Date).ToUniversalTime()
    Invoke-CheckedStep "Stage 4 fast gate: Gradle tests and OpenAPI contract verification" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $runAllTests `
            -RepoRoot $root `
            -JavaHome $JavaHome `
            -SkipDocker:$SkipDocker `
            -SkipOpenApi:$SkipOpenApi
    } -Step $plannedSteps[0]

    if ($Mode -eq "Release") {
        $stepIndex = 1
        if (-not $SkipBootJar) {
            Invoke-CheckedStep "Stage 4 release gate: bootJar" {
                Push-Location $backendDir
                try {
                    & $gradleWrapper --no-daemon bootJar
                } finally {
                    Pop-Location
                }
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipProdSmoke) {
            Invoke-CheckedStep "Stage 4 release gate: production profile smoke" {
                & powershell -NoProfile -ExecutionPolicy Bypass -File $prodSmoke -RepoRoot $root -SkipDocker:$SkipDocker
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipMtlsSmoke) {
            Invoke-CheckedStep "Stage 4 release gate: mTLS smoke" {
                & powershell -NoProfile -ExecutionPolicy Bypass -File $mtlsSmoke -RepoRoot $root -SkipDocker:$SkipDocker
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipLoadSmoke) {
            Invoke-CheckedStep "Stage 4 release gate: load smoke" {
                & powershell -NoProfile -ExecutionPolicy Bypass -File $loadSmoke
            } -Step $plannedSteps[$stepIndex]
        }
    }

    $gateFinishedAt = (Get-Date).ToUniversalTime()
    $gateDurationMs = [int][Math]::Round(($gateFinishedAt - $gateStartedAt).TotalMilliseconds)
    Write-Host ""
    Write-Host "Stage 4 gate passed." -ForegroundColor Green
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "passed" -Steps $plannedSteps -DurationMs $gateDurationMs
} catch {
    $gateFinishedAt = (Get-Date).ToUniversalTime()
    $gateDurationMs = if ($null -ne $gateStartedAt) {
        [int][Math]::Round(($gateFinishedAt - $gateStartedAt).TotalMilliseconds)
    } else {
        $null
    }
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "failed" -Steps $plannedSteps -ErrorMessage $_.Exception.Message -DurationMs $gateDurationMs
    throw
}
