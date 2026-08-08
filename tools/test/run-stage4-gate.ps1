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
    [switch]$SkipBackupRestoreDrill,
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

function Mark-UnstartedStepsAsSkipped {
    param([System.Collections.Generic.List[object]]$Steps)

    foreach ($step in $Steps) {
        if ($step.status -eq "run" -and [string]::IsNullOrWhiteSpace($step.started_at)) {
            $step.status = "skip"
        }
    }
}

function Test-ReleaseGateHasSkippedChecks {
    return [bool]($SkipOpenApi -or $SkipBootJar -or $SkipProdSmoke -or $SkipMtlsSmoke -or $SkipLoadSmoke -or $SkipBackupRestoreDrill)
}

function Resolve-RepoRevision {
    param([string]$RepositoryRoot)

    $revision = (& git -C $RepositoryRoot rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($revision)) {
        return "unknown"
    }

    return $revision.Trim()
}

function Resolve-RepoBranch {
    param([string]$RepositoryRoot)

    $branch = (& git -C $RepositoryRoot rev-parse --abbrev-ref HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($branch)) {
        return "unknown"
    }

    return $branch.Trim()
}

function Test-RepoHasUncommittedChanges {
    param([string]$RepositoryRoot)

    $status = (& git -C $RepositoryRoot status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0) {
        return $true
    }

    return [bool]$status
}

function Write-GateSummary {
    param(
        [string]$Path,
        [string]$GateMode,
        [string]$Result,
        [System.Collections.Generic.List[object]]$Steps,
        [string]$ErrorMessage = "",
        [Nullable[int]]$DurationMs = $null,
        [object]$GateStartedAt = $null,
        [object]$GateFinishedAt = $null
    )

    if ($NoSummary -or [string]::IsNullOrWhiteSpace($Path)) {
        return
    }

    $summaryFinishedAt = if ($null -ne $GateFinishedAt) {
        $GateFinishedAt
    } else {
        (Get-Date).ToUniversalTime()
    }
    $gateStartedAtText = if ($null -ne $GateStartedAt) {
        $GateStartedAt.ToUniversalTime().ToString("o")
    } else {
        $null
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

    $runStepCount = @($Steps | Where-Object { $_.status -eq "run" }).Count
    $skippedStepCount = @($Steps | Where-Object { $_.status -eq "skip" }).Count

    [pscustomobject]@{
        schema_name = "stage4_gate_summary"
        schema_version = 1
        stage = 4
        mode = $GateMode
        result = $Result
        repo_revision = Resolve-RepoRevision -RepositoryRoot $root
        repo_branch = Resolve-RepoBranch -RepositoryRoot $root
        repo_dirty = Test-RepoHasUncommittedChanges -RepositoryRoot $root
        generated_at = (Get-Date).ToUniversalTime().ToString("o")
        gate_started_at = $gateStartedAtText
        gate_finished_at = $summaryFinishedAt.ToUniversalTime().ToString("o")
        duration_ms = $DurationMs
        total_steps = $Steps.Count
        run_steps = $runStepCount
        skipped_steps = $skippedStepCount
        skip_docker = [bool]$SkipDocker
        skip_openapi = [bool]$SkipOpenApi
        skip_boot_jar = [bool]$SkipBootJar
        skip_prod_smoke = [bool]$SkipProdSmoke
        skip_mtls_smoke = [bool]$SkipMtlsSmoke
        skip_load_smoke = [bool]$SkipLoadSmoke
        skip_backup_restore_drill = [bool]$SkipBackupRestoreDrill
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
$backupRestoreDrill = Join-Path $root "tools\backup\run-backup-restore-drill.ps1"
$backendDir = Join-Path $root "backend"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"

foreach ($script in @($runAllTests, $summaryValidator, $prodSmoke, $mtlsSmoke, $loadSmoke, $backupRestoreDrill)) {
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

$gateStartedAt = (Get-Date).ToUniversalTime()
$plannedSteps = New-Object 'System.Collections.Generic.List[object]'
Add-GateStep $plannedSteps "fast gate: Gradle tests and OpenAPI contract verification" $true
if ($Mode -eq "Release") {
    Add-GateStep $plannedSteps "release gate: bootJar" (-not $SkipBootJar)
    Add-GateStep $plannedSteps "release gate: production profile smoke" (-not $SkipProdSmoke)
    Add-GateStep $plannedSteps "release gate: mTLS smoke" (-not $SkipMtlsSmoke)
    Add-GateStep $plannedSteps "release gate: load smoke" (-not $SkipLoadSmoke)
    Add-GateStep $plannedSteps "release gate: backup and restore drill" (-not $SkipBackupRestoreDrill)
}

if ($ListSteps) {
    Write-Host ""
    Write-Host "Planned Stage 4 gate steps:"
    foreach ($step in $plannedSteps) {
        Write-Host "- [$($step.status)] $($step.name)"
    }
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "planned" -Steps $plannedSteps -GateStartedAt $gateStartedAt
    exit 0
}

try {
    Invoke-CheckedStep "Stage 4 fast gate: Gradle tests and OpenAPI contract verification" {
        $runAllTestsParameters = @{
            RepoRoot = $root
            JavaHome = $JavaHome
        }
        if ($SkipDocker) {
            $runAllTestsParameters.SkipDocker = $true
        }
        if ($SkipOpenApi) {
            $runAllTestsParameters.SkipOpenApi = $true
        }
        & $runAllTests @runAllTestsParameters
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
                $prodSmokeParameters = @{ RepoRoot = $root; VerifyRedisOutage = $true }
                if ($SkipDocker) {
                    $prodSmokeParameters.SkipDocker = $true
                }
                & $prodSmoke @prodSmokeParameters
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipMtlsSmoke) {
            Invoke-CheckedStep "Stage 4 release gate: mTLS smoke" {
                $mtlsSmokeParameters = @{ RepoRoot = $root }
                if ($SkipDocker) {
                    $mtlsSmokeParameters.SkipDocker = $true
                }
                & $mtlsSmoke @mtlsSmokeParameters
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipLoadSmoke) {
            Invoke-CheckedStep "Stage 4 release gate: load smoke" {
                $loadSmokeParameters = @{
                    RepoRoot = $root
                    JavaHome = $JavaHome
                    StartBackend = $true
                }
                if ($SkipDocker) {
                    $loadSmokeParameters.SkipDocker = $true
                }
                & $loadSmoke @loadSmokeParameters
            } -Step $plannedSteps[$stepIndex]
        }
        $stepIndex++

        if (-not $SkipBackupRestoreDrill) {
            Invoke-CheckedStep "Stage 4 release gate: backup and restore drill" {
                $backupDrillParameters = @{ RepoRoot = $root }
                if ($SkipDocker) {
                    $backupDrillParameters.SkipDocker = $true
                }
                & $backupRestoreDrill @backupDrillParameters
            } -Step $plannedSteps[$stepIndex]
        }
    }

    $gateFinishedAt = (Get-Date).ToUniversalTime()
    $gateDurationMs = [int][Math]::Round(($gateFinishedAt - $gateStartedAt).TotalMilliseconds)
    Write-Host ""
    Write-Host "Stage 4 gate passed." -ForegroundColor Green
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "passed" -Steps $plannedSteps -DurationMs $gateDurationMs -GateStartedAt $gateStartedAt -GateFinishedAt $gateFinishedAt
} catch {
    $gateFinishedAt = (Get-Date).ToUniversalTime()
    $gateDurationMs = if ($null -ne $gateStartedAt) {
        [int][Math]::Round(($gateFinishedAt - $gateStartedAt).TotalMilliseconds)
    } else {
        $null
    }
    Mark-UnstartedStepsAsSkipped -Steps $plannedSteps
    Write-GateSummary -Path $SummaryPath -GateMode $Mode -Result "failed" -Steps $plannedSteps -ErrorMessage $_.Exception.Message -DurationMs $gateDurationMs -GateStartedAt $gateStartedAt -GateFinishedAt $gateFinishedAt
    throw
}
