param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Vus = 5,
    [string]$Duration = "30s",
    [int]$Users = 0,
    [string]$ScriptPath = "",
    [string]$ServerId = "10000000-0000-0000-0000-000000000001",
    [string]$ServerFingerprint = "dev-ds-fingerprint",
    [string]$ServerBuildId = "ds-dev-smoke"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    throw "k6 is not installed or is not available in PATH. Install k6, then rerun this script."
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
if ([string]::IsNullOrWhiteSpace($ScriptPath)) {
    $ScriptPath = Join-Path $repoRoot "tools\load\load-smoke.js"
}

if (-not (Test-Path $ScriptPath)) {
    throw "k6 script was not found: $ScriptPath"
}

if ($Users -le 0) {
    $Users = $Vus
}

$previousEnv = @{
    BASE_URL = $env:BASE_URL
    K6_VUS = $env:K6_VUS
    K6_DURATION = $env:K6_DURATION
    LOAD_USERS = $env:LOAD_USERS
    SERVER_ID = $env:SERVER_ID
    SERVER_FINGERPRINT = $env:SERVER_FINGERPRINT
    SERVER_BUILD_ID = $env:SERVER_BUILD_ID
}

try {
    $env:BASE_URL = $BaseUrl
    $env:K6_VUS = [string]$Vus
    $env:K6_DURATION = $Duration
    $env:LOAD_USERS = [string]$Users
    $env:SERVER_ID = $ServerId
    $env:SERVER_FINGERPRINT = $ServerFingerprint
    $env:SERVER_BUILD_ID = $ServerBuildId

    Write-Host "Running k6 load smoke"
    Write-Host "  BaseUrl:  $BaseUrl"
    Write-Host "  VUs:      $Vus"
    Write-Host "  Users:    $Users"
    Write-Host "  Duration: $Duration"
    Write-Host "  Script:   $ScriptPath"

    k6 run $ScriptPath
    if ($LASTEXITCODE -ne 0) {
        throw "k6 load smoke failed with exit code $LASTEXITCODE"
    }
} finally {
    foreach ($key in $previousEnv.Keys) {
        if ($null -eq $previousEnv[$key]) {
            Remove-Item -Path "env:$key" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "env:$key" -Value $previousEnv[$key]
        }
    }
}
