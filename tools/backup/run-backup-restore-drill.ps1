param(
    [string]$RepoRoot = "",
    [switch]$SkipDocker
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

function Wait-PostgresReady {
    param([int]$TimeoutSeconds = 60)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        & docker compose exec -T postgres pg_isready -U ue_backend -d ue_backend | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "PostgreSQL did not become ready within $TimeoutSeconds seconds."
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$backupScript = Join-Path $root "tools\backup\backup-postgres.ps1"
$verifyScript = Join-Path $root "tools\backup\verify-postgres-backup.ps1"
$drillDirectory = Join-Path $root ("artifacts\backup-restore-drill\" + [Guid]::NewGuid().ToString("N"))

foreach ($script in @($backupScript, $verifyScript)) {
    if (-not (Test-Path -LiteralPath $script)) {
        throw "Required backup drill script was not found: $script"
    }
}

Push-Location $root
try {
    if (-not $SkipDocker) {
        & docker compose up -d postgres
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed with exit code $LASTEXITCODE"
        }
    }
    Wait-PostgresReady

    & powershell -NoProfile -ExecutionPolicy Bypass -File $backupScript -RepoRoot $root -OutputDir $drillDirectory -RetentionDays 14
    if ($LASTEXITCODE -ne 0) {
        throw "Backup creation failed with exit code $LASTEXITCODE"
    }

    $backup = Get-ChildItem -LiteralPath $drillDirectory -Filter "ue_backend-*.dump" -File | Select-Object -First 1
    if ($null -eq $backup) {
        throw "Backup drill did not produce a PostgreSQL dump."
    }

    & powershell -NoProfile -ExecutionPolicy Bypass -File $verifyScript -RepoRoot $root -BackupPath $backup.FullName
    if ($LASTEXITCODE -ne 0) {
        throw "Restore verification failed with exit code $LASTEXITCODE"
    }

    Write-Host "Backup and restore drill passed." -ForegroundColor Green
} finally {
    if (Test-Path -LiteralPath $drillDirectory) {
        Remove-Item -LiteralPath $drillDirectory -Recurse -Force
    }
    Pop-Location
}
