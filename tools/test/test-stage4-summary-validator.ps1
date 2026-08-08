param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }

    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

function Write-TestSummary {
    param(
        [string]$Path,
        [hashtable]$Overrides
    )

    $startedAt = (Get-Date).ToUniversalTime().AddSeconds(-1).ToString("o")
    $finishedAt = (Get-Date).ToUniversalTime().ToString("o")

    $summary = [ordered]@{
        schema_name = "stage4_gate_summary"
        schema_version = 1
        stage = 4
        mode = "Release"
        result = "planned"
        repo_revision = "test-revision"
        repo_branch = "test-branch"
        repo_dirty = $false
        generated_at = $finishedAt
        gate_started_at = $startedAt
        gate_finished_at = $finishedAt
        duration_ms = $null
        total_steps = 1
        run_steps = 1
        skipped_steps = 0
        skip_docker = $false
        skip_openapi = $false
        skip_boot_jar = $false
        skip_prod_smoke = $false
        skip_mtls_smoke = $false
        skip_load_smoke = $false
        skip_backup_restore_drill = $false
        skip_reason = ""
        error_message = ""
        steps = @(
            [ordered]@{
                name = "fast gate: Gradle tests and OpenAPI contract verification"
                status = "run"
                started_at = $null
                finished_at = $null
                duration_ms = $null
            }
        )
    }

    foreach ($key in $Overrides.Keys) {
        $summary[$key] = $Overrides[$key]
    }

    $summary | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $Path -Encoding UTF8
}

function Assert-ValidatorFails {
    param(
        [string]$CaseName,
        [hashtable]$Overrides,
        [string]$ExpectedMessage
    )

    $summaryPath = Join-Path $tempDir "$CaseName.json"
    Write-TestSummary -Path $summaryPath -Overrides $Overrides

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & powershell -NoProfile -ExecutionPolicy Bypass -File $validator -SummaryPath $summaryPath 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -eq 0) {
        throw "Expected validator to fail for $CaseName"
    }

    $outputText = ($output | Out-String)
    if (-not $outputText.Contains($ExpectedMessage)) {
        throw "Expected validator failure for $CaseName to contain '$ExpectedMessage'. Actual output: $outputText"
    }
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$validator = Join-Path $root "tools\test\validate-stage4-summary.ps1"
if (-not (Test-Path -LiteralPath $validator)) {
    throw "Stage 4 summary validator was not found: $validator"
}

$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("stage4-summary-validator-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

try {
    Assert-ValidatorFails `
        -CaseName "wrong-schema" `
        -Overrides @{ schema_name = "wrong_schema" } `
        -ExpectedMessage "Unexpected schema_name"

    Assert-ValidatorFails `
        -CaseName "bad-counter" `
        -Overrides @{ run_steps = 0 } `
        -ExpectedMessage "run_steps and skipped_steps must add up to total_steps"

    Assert-ValidatorFails `
        -CaseName "bad-backup-drill-skip" `
        -Overrides @{ skip_backup_restore_drill = "false" } `
        -ExpectedMessage "skip_backup_restore_drill must be boolean"

    Assert-ValidatorFails `
        -CaseName "bad-timing-order" `
        -Overrides @{
            gate_started_at = (Get-Date).ToUniversalTime().ToString("o")
            gate_finished_at = (Get-Date).ToUniversalTime().AddSeconds(-1).ToString("o")
        } `
        -ExpectedMessage "gate_finished_at must be greater than or equal to gate_started_at"
} finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force
}

Write-Host "Stage 4 summary validator negative tests passed." -ForegroundColor Green
