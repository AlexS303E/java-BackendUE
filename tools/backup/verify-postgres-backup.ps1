param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,
    [string]$RepoRoot = "",
    [string]$Service = "postgres",
    [string]$User = "ue_backend",
    [string]$VerifyDatabase = ""
)

$ErrorActionPreference = "Stop"

function Assert-PostgresIdentifier {
    param([string]$Name, [string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z_][A-Za-z0-9_]{0,62}$') {
        throw "$Name must be a PostgreSQL identifier (letters, digits, and underscores only)."
    }
}

function Assert-ComposeServiceName {
    param([string]$Value)

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -notmatch '^[A-Za-z0-9][A-Za-z0-9_.-]{0,62}$') {
        throw "Service must be a Docker Compose service name."
    }
}

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)
    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

if ([string]::IsNullOrWhiteSpace($VerifyDatabase)) {
    $VerifyDatabase = "ue_backend_restore_verify_" + ([Guid]::NewGuid().ToString("N").Substring(0, 8))
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
Assert-ComposeServiceName -Value $Service
Assert-PostgresIdentifier -Name "User" -Value $User
Assert-PostgresIdentifier -Name "VerifyDatabase" -Value $VerifyDatabase
$resolvedBackup = (Resolve-Path -LiteralPath $BackupPath).Path
$checksumPath = "$resolvedBackup.sha256"
if (-not (Test-Path -LiteralPath $checksumPath)) {
    throw "Backup checksum manifest was not found: $checksumPath"
}
$expectedChecksum = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
if ($expectedChecksum -notmatch '^[A-Fa-f0-9]{64}$') {
    throw "Backup checksum manifest must contain a SHA-256 value: $checksumPath"
}
$actualChecksum = (Get-FileHash -LiteralPath $resolvedBackup -Algorithm SHA256).Hash
if (-not $actualChecksum.Equals($expectedChecksum, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Backup checksum verification failed: $resolvedBackup"
}

$containerPath = "/tmp/" + [IO.Path]::GetFileName($resolvedBackup)

Push-Location $root
try {
    $containerId = ((& docker compose ps -q $Service) | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "Docker compose service '$Service' is not running."
    }

    & docker cp $resolvedBackup "${containerId}:$containerPath"
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp failed with exit code $LASTEXITCODE"
    }

    & docker compose exec -T $Service psql -U $User -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $VerifyDatabase;"
    if ($LASTEXITCODE -ne 0) {
        throw "DROP verify database failed with exit code $LASTEXITCODE"
    }

    & docker compose exec -T $Service psql -U $User -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $VerifyDatabase;"
    if ($LASTEXITCODE -ne 0) {
        throw "CREATE verify database failed with exit code $LASTEXITCODE"
    }

    & docker compose exec -T $Service pg_restore -U $User -d $VerifyDatabase --no-owner --no-privileges $containerPath
    if ($LASTEXITCODE -ne 0) {
        throw "pg_restore verify failed with exit code $LASTEXITCODE"
    }

    $version = (& docker compose exec -T $Service psql -U $User -d $VerifyDatabase -t -A -v ON_ERROR_STOP=1 -c "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1;").Trim()
    if ([string]::IsNullOrWhiteSpace($version)) {
        throw "Restore verification did not find a successful Flyway version."
    }

    [PSCustomObject]@{
        status = "RESTORE_VERIFY_OK"
        backup = $resolvedBackup
        sha256 = $actualChecksum
        verify_database = $VerifyDatabase
        latest_flyway_version = $version
    }
} finally {
    try {
        & docker compose exec -T $Service psql -U $User -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $VerifyDatabase;" | Out-Null
        & docker compose exec -T $Service rm -f $containerPath | Out-Null
    } catch {
        Write-Warning "Failed to clean restore verification artifacts: $($_.Exception.Message)"
    }
    Pop-Location
}
