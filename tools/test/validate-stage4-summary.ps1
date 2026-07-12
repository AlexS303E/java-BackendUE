param(
    [Parameter(Mandatory = $true)]
    [string]$SummaryPath
)

$ErrorActionPreference = "Stop"

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw $Message
    }
}

if (-not (Test-Path -LiteralPath $SummaryPath)) {
    throw "Stage 4 summary was not found: $SummaryPath"
}

$summary = Get-Content -LiteralPath $SummaryPath -Raw | ConvertFrom-Json

Assert-Condition ($summary.schema_name -eq "stage4_gate_summary") "Unexpected schema_name: $($summary.schema_name)"
Assert-Condition ($summary.schema_version -eq 1) "Unexpected schema_version: $($summary.schema_version)"
Assert-Condition ($summary.stage -eq 4) "Unexpected stage: $($summary.stage)"
Assert-Condition (@("Fast", "Release") -contains $summary.mode) "Unexpected mode: $($summary.mode)"
Assert-Condition (@("planned", "passed", "failed") -contains $summary.result) "Unexpected result: $($summary.result)"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($summary.repo_revision)) "repo_revision is required"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($summary.repo_branch)) "repo_branch is required"
Assert-Condition ($null -ne $summary.repo_dirty -and $summary.repo_dirty.GetType().Name -eq "Boolean") "repo_dirty must be boolean"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($summary.generated_at)) "generated_at is required"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($summary.gate_started_at)) "gate_started_at is required"
Assert-Condition (-not [string]::IsNullOrWhiteSpace($summary.gate_finished_at)) "gate_finished_at is required"
$gateStartedAt = [datetimeoffset]::Parse($summary.gate_started_at)
$gateFinishedAt = [datetimeoffset]::Parse($summary.gate_finished_at)
Assert-Condition ($gateFinishedAt -ge $gateStartedAt) "gate_finished_at must be greater than or equal to gate_started_at"
Assert-Condition ($null -ne $summary.steps -and $summary.steps.Count -gt 0) "steps must contain at least one entry"
Assert-Condition ($summary.total_steps -eq $summary.steps.Count) "total_steps must match steps count"
Assert-Condition ($summary.run_steps -ge 0) "run_steps must be non-negative"
Assert-Condition ($summary.skipped_steps -ge 0) "skipped_steps must be non-negative"
Assert-Condition (($summary.run_steps + $summary.skipped_steps) -eq $summary.total_steps) "run_steps and skipped_steps must add up to total_steps"

foreach ($step in $summary.steps) {
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($step.name)) "step.name is required"
    Assert-Condition (@("run", "skip") -contains $step.status) "Unexpected step status for $($step.name): $($step.status)"
    if ($step.status -eq "run" -and $summary.result -ne "planned") {
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($step.started_at)) "started_at is required for run step $($step.name)"
        Assert-Condition (-not [string]::IsNullOrWhiteSpace($step.finished_at)) "finished_at is required for run step $($step.name)"
        Assert-Condition ($null -ne $step.duration_ms -and $step.duration_ms -ge 0) "duration_ms must be non-negative for run step $($step.name)"
        $stepStartedAt = [datetimeoffset]::Parse($step.started_at)
        $stepFinishedAt = [datetimeoffset]::Parse($step.finished_at)
        Assert-Condition ($stepFinishedAt -ge $stepStartedAt) "finished_at must be greater than or equal to started_at for run step $($step.name)"
    }
}

Write-Host "Stage 4 summary schema is valid: $SummaryPath" -ForegroundColor Green
