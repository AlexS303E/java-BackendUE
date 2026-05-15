param(
    [Parameter(Mandatory = $true)]
    [string]$BackupPath,
    [string]$RepoRoot = "",
    [string]$Service = "postgres",
    [string]$User = "ue_backend",
    [string]$VerifyDatabase = ""
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)
    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$resolvedBackup = (Resolve-Path -LiteralPath $BackupPath).Path
if ([string]::IsNullOrWhiteSpace($VerifyDatabase)) {
    $VerifyDatabase = "ue_backend_restore_verify_" + ([Guid]::NewGuid().ToString("N").Substring(0, 8))
}

$containerPath = "/tmp/" + [IO.Path]::GetFileName($resolvedBackup)

Push-Location $root
try {
    $containerId = (& docker compose ps -q $Service).Trim()
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
