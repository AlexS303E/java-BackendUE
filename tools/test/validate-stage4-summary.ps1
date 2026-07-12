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
Assert-Condition ($null -ne $summary.steps -and $summary.steps.Count -gt 0) "steps must contain at least one entry"

foreach ($step in $summary.steps) {
    Assert-Condition (-not [string]::IsNullOrWhiteSpace($step.name)) "step.name is required"
    Assert-Condition (@("run", "skip") -contains $step.status) "Unexpected step status for $($step.name): $($step.status)"
}

Write-Host "Stage 4 summary schema is valid: $SummaryPath" -ForegroundColor Green
