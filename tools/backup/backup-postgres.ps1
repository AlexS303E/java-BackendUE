param(
    [string]$RepoRoot = "",
    [string]$OutputDir = "",
    [string]$Service = "postgres",
    [string]$Database = "ue_backend",
    [string]$User = "ue_backend",
    [int]$RetentionDays = 14
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
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $root "backups\postgres"
}
$outputPath = (New-Item -ItemType Directory -Force -Path $OutputDir).FullName

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$fileName = "$Database-$timestamp.dump"
$containerPath = "/tmp/$fileName"
$hostPath = Join-Path $outputPath $fileName

Push-Location $root
try {
    $containerId = (& docker compose ps -q $Service).Trim()
    if ([string]::IsNullOrWhiteSpace($containerId)) {
        throw "Docker compose service '$Service' is not running."
    }

    & docker compose exec -T $Service sh -c "pg_dump -Fc -U $User -d $Database -f $containerPath"
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE"
    }

    & docker cp "${containerId}:$containerPath" $hostPath
    if ($LASTEXITCODE -ne 0) {
        throw "docker cp failed with exit code $LASTEXITCODE"
    }

    & docker compose exec -T $Service rm -f $containerPath | Out-Null

    if ($RetentionDays -gt 0) {
        Get-ChildItem -Path $outputPath -Filter "$Database-*.dump" -File |
            Where-Object { $_.LastWriteTimeUtc -lt (Get-Date).ToUniversalTime().AddDays(-$RetentionDays) } |
            Remove-Item -Force
    }

    [PSCustomObject]@{
        status = "BACKUP_OK"
        database = $Database
        path = $hostPath
        bytes = (Get-Item -LiteralPath $hostPath).Length
        retention_days = $RetentionDays
    }
} finally {
    Pop-Location
}
