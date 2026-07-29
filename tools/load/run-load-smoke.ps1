param(
    [string]$BaseUrl = "http://localhost:8080",
    [int]$Vus = 5,
    [string]$Duration = "30s",
    [int]$Users = 0,
    [string]$ScriptPath = "",
    [string]$ServerId = "10000000-0000-0000-0000-000000000001",
    [string]$ServerFingerprint = "dev-ds-fingerprint",
    [string]$ServerBuildId = "ds-dev-smoke",
    [string]$RepoRoot = "",
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$StartBackend,
    [switch]$SkipDocker
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
    throw "k6 is not installed or is not available in PATH. Install k6, then rerun this script."
}

$repoRoot = if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
} else {
    (Resolve-Path $RepoRoot).Path
}
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
    SPRING_PROFILES_ACTIVE = $env:SPRING_PROFILES_ACTIVE
    SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK = $env:SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK
}
$backendProcess = $null

function Wait-HttpOk {
    param([string]$Url, [int]$TimeoutSeconds = 90)

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            if ((Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) {
                return
            }
        } catch {
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for backend health at $Url"
}

function Stop-Backend {
    param([System.Diagnostics.Process]$Process)

    if ($null -ne $Process -and -not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    }
}

try {
    if ($StartBackend) {
        if (-not $SkipDocker) {
            Push-Location $repoRoot
            try {
                & docker compose up -d postgres redis
                if ($LASTEXITCODE -ne 0) {
                    throw "docker compose up failed with exit code $LASTEXITCODE"
                }
            } finally {
                Pop-Location
            }
        }
        $backendDir = Join-Path $repoRoot "backend"
        $gradleWrapper = Join-Path $backendDir "gradlew.bat"
        if (-not (Test-Path $gradleWrapper)) {
            throw "Gradle wrapper was not found: $gradleWrapper"
        }
        if (-not [string]::IsNullOrWhiteSpace($JavaHome)) {
            $env:JAVA_HOME = $JavaHome
            $env:Path = (Join-Path $JavaHome "bin") + [IO.Path]::PathSeparator + $env:Path
        }
        $env:SPRING_PROFILES_ACTIVE = "local"
        $env:SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK = "true"
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = $gradleWrapper
        $startInfo.Arguments = "bootRun --no-daemon"
        $startInfo.WorkingDirectory = $backendDir
        $startInfo.UseShellExecute = $false
        $backendProcess = New-Object System.Diagnostics.Process
        $backendProcess.StartInfo = $startInfo
        [void]$backendProcess.Start()
        Wait-HttpOk -Url "$($BaseUrl.TrimEnd('/'))/api/health"
    }

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
    Stop-Backend -Process $backendProcess
    foreach ($key in $previousEnv.Keys) {
        if ($null -eq $previousEnv[$key]) {
            Remove-Item -Path "env:$key" -ErrorAction SilentlyContinue
        } else {
            Set-Item -Path "env:$key" -Value $previousEnv[$key]
        }
    }
}
