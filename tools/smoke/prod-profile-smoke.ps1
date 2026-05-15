param(
    [string]$RepoRoot = "",
    [int]$PublicPort = 18080,
    [int]$PrivateMtlsPort = 19443,
    [int]$StartupTimeoutSeconds = 120,
    [string]$CorsOrigin = "https://game.example",
    [switch]$SkipDocker,
    [switch]$KeepBackendRunning
)

$ErrorActionPreference = "Stop"

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)
    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }
    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

function Wait-HttpOk {
    param([string]$Url, [int]$TimeoutSeconds)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($response.StatusCode -eq 200) {
                return
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    throw "Timed out waiting for $Url. Last error: $lastError"
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }
    if ($env:OS -eq "Windows_NT") {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    } else {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$backendDir = Join-Path $root "backend"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"
$generateCertsScript = Join-Path $root "tools\mtls\generate-dev-certs.ps1"
$certDir = Join-Path $root "tools\mtls\out-prod-smoke"
$password = "changeit"
$backendP12 = Join-Path $certDir "backend.p12"
$truststore = Join-Path $certDir "backend-truststore.p12"
$logDir = Join-Path $root "tools\smoke\logs"
$stdout = Join-Path $logDir "backend-prod-smoke.out.log"
$stderr = Join-Path $logDir "backend-prod-smoke.err.log"
$backendProcess = $null

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}
if (-not (Test-Path -LiteralPath $generateCertsScript)) {
    throw "mTLS certificate generator not found: $generateCertsScript"
}

Push-Location $root
try {
    if (-not $SkipDocker) {
        & docker compose up -d postgres redis
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose up failed with exit code $LASTEXITCODE"
        }
    }

    & powershell -ExecutionPolicy Bypass -File $generateCertsScript -OutputDir $certDir -Password $password -BackendDns "localhost" -BackendIp "127.0.0.1"
    if ($LASTEXITCODE -ne 0) {
        throw "generate-dev-certs.ps1 failed with exit code $LASTEXITCODE"
    }

    Push-Location $backendDir
    try {
        & $gradleWrapper bootJar
        if ($LASTEXITCODE -ne 0) {
            throw "bootJar failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }

    $jar = Get-ChildItem -Path (Join-Path $backendDir "build\libs") -Filter "*.jar" -File |
        Where-Object { $_.Name -notlike "*plain*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $jar) {
        throw "No bootJar artifact found."
    }

    New-Item -ItemType Directory -Force -Path $logDir | Out-Null
    Remove-Item $stdout, $stderr -Force -ErrorAction SilentlyContinue

    $previousEnv = @{}
    $envKeys = @(
        "SPRING_PROFILES_ACTIVE",
        "SERVER_PORT",
        "ADMIN_TOKEN",
        "JWT_SECRET",
        "CORS_ALLOWED_ORIGINS",
        "SERVER_MTLS_ENABLED",
        "SERVER_MTLS_PORT",
        "SERVER_MTLS_REQUIRE_PRIVATE_PORT",
        "SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK",
        "SERVER_MTLS_KEY_STORE",
        "SERVER_MTLS_KEY_STORE_PASSWORD",
        "SERVER_MTLS_TRUST_STORE",
        "SERVER_MTLS_TRUST_STORE_PASSWORD",
        "OUTBOX_WORKER_ENABLED",
        "RATE_LIMIT_ENABLED"
    )
    foreach ($key in $envKeys) {
        $previousEnv[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
    }

    $env:SPRING_PROFILES_ACTIVE = "prod"
    $env:SERVER_PORT = [string]$PublicPort
    $env:ADMIN_TOKEN = "prod-smoke-admin-token"
    $env:JWT_SECRET = "prod-smoke-jwt-secret-value-at-least-32"
    $env:CORS_ALLOWED_ORIGINS = $CorsOrigin
    $env:SERVER_MTLS_ENABLED = "true"
    $env:SERVER_MTLS_PORT = [string]$PrivateMtlsPort
    $env:SERVER_MTLS_REQUIRE_PRIVATE_PORT = "true"
    $env:SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK = "false"
    $env:SERVER_MTLS_KEY_STORE = "file:$backendP12"
    $env:SERVER_MTLS_KEY_STORE_PASSWORD = $password
    $env:SERVER_MTLS_TRUST_STORE = "file:$truststore"
    $env:SERVER_MTLS_TRUST_STORE_PASSWORD = $password
    $env:OUTBOX_WORKER_ENABLED = "false"
    $env:RATE_LIMIT_ENABLED = "true"

    $javaExe = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { "java" } else { Join-Path $env:JAVA_HOME "bin\java.exe" }
    $backendProcess = Start-Process `
        -FilePath $javaExe `
        -ArgumentList @("-jar", $jar.FullName) `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    Wait-HttpOk -Url "http://localhost:$PublicPort/actuator/health" -TimeoutSeconds $StartupTimeoutSeconds

    $info = Invoke-WebRequest -Uri "http://localhost:$PublicPort/actuator/info" -UseBasicParsing -TimeoutSec 5
    if ($info.StatusCode -ne 200) {
        throw "Expected /actuator/info to return 200, got $($info.StatusCode)"
    }

    $metricsStatus = $null
    try {
        Invoke-WebRequest -Uri "http://localhost:$PublicPort/actuator/metrics" -UseBasicParsing -TimeoutSec 5 | Out-Null
        $metricsStatus = 200
    } catch {
        if ($null -ne $_.Exception.Response) {
            $metricsStatus = [int]$_.Exception.Response.StatusCode
        }
    }
    if ($metricsStatus -eq 200) {
        throw "Expected /actuator/metrics to be unavailable over prod HTTP exposure."
    }

    $corsAllowed = Invoke-WebRequest `
        -Method Options `
        -Uri "http://localhost:$PublicPort/auth/login" `
        -Headers @{
            "Origin" = $CorsOrigin
            "Access-Control-Request-Method" = "POST"
        } `
        -UseBasicParsing `
        -TimeoutSec 5
    if ($corsAllowed.Headers["Access-Control-Allow-Origin"] -ne $CorsOrigin) {
        throw "Expected CORS allow origin '$CorsOrigin'. Headers: $($corsAllowed.Headers | Out-String)"
    }

    [PSCustomObject]@{
        status = "PROD_PROFILE_SMOKE_OK"
        public_port = $PublicPort
        private_mtls_port = $PrivateMtlsPort
        cors_origin = $CorsOrigin
        metrics_status = $metricsStatus
        jar = $jar.FullName
    }
} finally {
    if (-not $KeepBackendRunning) {
        Stop-ProcessTree -Process $backendProcess
    }

    if (Test-Path variable:previousEnv) {
        foreach ($key in $previousEnv.Keys) {
            if ($null -eq $previousEnv[$key]) {
                Remove-Item "Env:$key" -ErrorAction SilentlyContinue
            } else {
                Set-Item "Env:$key" $previousEnv[$key]
            }
        }
    }
    Pop-Location
}
