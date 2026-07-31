param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

$backupScript = Join-Path $RepoRoot "tools\backup\backup-postgres.ps1"
$restoreScript = Join-Path $RepoRoot "tools\backup\verify-postgres-backup.ps1"

function Assert-Rejected {
    param([string]$Name, [scriptblock]$Action, [string]$ExpectedMessage)

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = "Continue"
        $output = & $Action 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -eq 0) {
        throw "Expected $Name to be rejected"
    }
    if (($output | Out-String) -notmatch [regex]::Escape($ExpectedMessage)) {
        throw "Expected $Name failure to contain '$ExpectedMessage'. Actual output: $($output | Out-String)"
    }
}

Assert-Rejected -Name "backup database injection" -ExpectedMessage "Database must be" -Action {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $backupScript -RepoRoot $RepoRoot -Database "ue_backend;drop_database"
}
Assert-Rejected -Name "restore database injection" -ExpectedMessage "VerifyDatabase must be" -Action {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $restoreScript -RepoRoot $RepoRoot -BackupPath "not-needed.dump" -VerifyDatabase "restore;drop_database"
}
Assert-Rejected -Name "compose service injection" -ExpectedMessage "Service must be" -Action {
    & powershell -NoProfile -ExecutionPolicy Bypass -File $backupScript -RepoRoot $RepoRoot -Service "postgres;rm"
}

Write-Host "Backup parameter validation tests passed." -ForegroundColor Green
