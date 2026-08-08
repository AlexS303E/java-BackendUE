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
$powerShellExecutable = if ($null -ne (Get-Command pwsh -ErrorAction SilentlyContinue)) { "pwsh" } else { "powershell" }

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
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $backupScript -RepoRoot $RepoRoot -Database "ue_backend;drop_database"
}
Assert-Rejected -Name "restore database injection" -ExpectedMessage "VerifyDatabase must be" -Action {
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $restoreScript -RepoRoot $RepoRoot -BackupPath "not-needed.dump" -VerifyDatabase "restore;drop_database"
}
Assert-Rejected -Name "compose service injection" -ExpectedMessage "Service must be" -Action {
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $backupScript -RepoRoot $RepoRoot -Service "postgres;rm"
}

$checksumTestDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("backup-checksum-test-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $checksumTestDirectory | Out-Null
$tamperedBackup = Join-Path $checksumTestDirectory "ue_backend-test.dump"
try {
    Set-Content -LiteralPath $tamperedBackup -Value "tampered backup" -NoNewline -Encoding ascii
    Set-Content -LiteralPath ($tamperedBackup + ".sha256") -Value ("0" * 64) -NoNewline -Encoding ascii
    Assert-Rejected -Name "tampered backup checksum" -ExpectedMessage "Backup checksum verification failed" -Action {
        & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $restoreScript -RepoRoot $RepoRoot -BackupPath $tamperedBackup
    }
} finally {
    Remove-Item -LiteralPath $checksumTestDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host "Backup parameter validation tests passed." -ForegroundColor Green
