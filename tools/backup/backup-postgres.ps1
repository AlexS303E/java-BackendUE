param(
    [string]$RepoRoot = "",
    [string]$OutputDir = "",
    [string]$Service = "postgres",
    [string]$Database = "ue_backend",
    [string]$User = "ue_backend",
    [int]$RetentionDays = 14
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

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
Assert-ComposeServiceName -Value $Service
Assert-PostgresIdentifier -Name "Database" -Value $Database
Assert-PostgresIdentifier -Name "User" -Value $User
if ([string]::IsNullOrWhiteSpace($OutputDir)) {
    $OutputDir = Join-Path $root "backups\postgres"
}
$outputPath = (New-Item -ItemType Directory -Force -Path $OutputDir).FullName

$timestamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$fileName = "$Database-$timestamp.dump"
$containerPath = "/tmp/$fileName"
$hostPath = Join-Path $outputPath $fileName
$checksumPath = "$hostPath.sha256"

Push-Location $root
try {
    $containerId = ((& docker compose ps -q $Service) | Out-String).Trim()
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

    $checksum = (Get-FileHash -LiteralPath $hostPath -Algorithm SHA256).Hash
    Set-Content -LiteralPath $checksumPath -Value $checksum -NoNewline -Encoding ascii

    & docker compose exec -T $Service rm -f $containerPath | Out-Null

    if ($RetentionDays -gt 0) {
        Get-ChildItem -Path $outputPath -Filter "$Database-*.dump" -File |
            Where-Object { $_.LastWriteTimeUtc -lt (Get-Date).ToUniversalTime().AddDays(-$RetentionDays) } |
            ForEach-Object {
                Remove-Item -LiteralPath $_.FullName -Force
                Remove-Item -LiteralPath ($_.FullName + ".sha256") -Force -ErrorAction SilentlyContinue
            }
    }

    [PSCustomObject]@{
        status = "BACKUP_OK"
        database = $Database
        path = $hostPath
        sha256_path = $checksumPath
        sha256 = $checksum
        bytes = (Get-Item -LiteralPath $hostPath).Length
        retention_days = $RetentionDays
    }
} finally {
    Pop-Location
}
