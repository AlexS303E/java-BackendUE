param(
    [string]$RepoRoot = "",
    [int]$PublicPort = 18080,
    [int]$ManagementPort = 18081,
    [int]$PrivateMtlsPort = 19443,
    [int]$StartupTimeoutSeconds = 120,
    [string]$CorsOrigin = "https://game.example",
    [switch]$SkipDocker,
    [switch]$VerifyRedisOutage,
    [switch]$VerifyMultiReplicaRateLimit,
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

function Invoke-HttpStatus {
    param(
        [string]$Uri,
        [hashtable]$Headers = @{}
    )
    try {
        Invoke-WebRequest `
            -Method Post `
            -Uri $Uri `
            -ContentType "application/json" `
            -Headers $Headers `
            -Body '{"login":"rate-limit-smoke","password":"irrelevant"}' `
            -UseBasicParsing `
            -TimeoutSec 10 | Out-Null
        return 200
    } catch {
        if ($null -ne $_.Exception.Response) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Wait-RedisReady {
    param([int]$TimeoutSeconds = 60)
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        & docker compose exec -T redis redis-cli ping | Out-Null
        if ($LASTEXITCODE -eq 0) {
            return
        }
        Start-Sleep -Seconds 2
    }
    throw "Redis did not become ready within $TimeoutSeconds seconds."
}

$root = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$backendDir = Join-Path $root "backend"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"
$generateCertsScript = Join-Path $root "tools\mtls\generate-dev-certs.ps1"
$resourceEnvelope = Join-Path $root "config\production-resource-envelope.env"
$resourcePreflight = Join-Path $root "tools\deploy\validate-resource-envelope.ps1"
$certDir = Join-Path $root "tools\mtls\out-prod-smoke"
$password = "changeit"
$backendP12 = Join-Path $certDir "backend.p12"
$truststore = Join-Path $certDir "backend-truststore.p12"
$jwtPrivateKey = Join-Path $certDir "jwt-private.pem"
$jwtPublicKey = Join-Path $certDir "jwt-public.pem"
$adminTokenFile = Join-Path $certDir "admin-token.txt"
$keyStorePasswordFile = Join-Path $certDir "keystore-password.txt"
$trustStorePasswordFile = Join-Path $certDir "truststore-password.txt"
$logDir = Join-Path $root "tools\smoke\logs"
$stdout = Join-Path $logDir "backend-prod-smoke.out.log"
$stderr = Join-Path $logDir "backend-prod-smoke.err.log"
$backendProcess = $null
$replicaProcess = $null
$redisRestartRequired = $false

if (-not (Test-Path -LiteralPath $gradleWrapper)) {
    throw "Gradle wrapper not found: $gradleWrapper"
}
if (-not (Test-Path -LiteralPath $generateCertsScript)) {
    throw "mTLS certificate generator not found: $generateCertsScript"
}
if (-not (Test-Path -LiteralPath $resourceEnvelope)) {
    throw "Production resource envelope not found: $resourceEnvelope"
}
if (-not (Test-Path -LiteralPath $resourcePreflight)) {
    throw "Resource preflight not found: $resourcePreflight"
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
        "SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS",
        "DB_URL",
        "DB_USER",
        "DB_PASSWORD",
        "JAVA_TOOL_OPTIONS",
        "SERVER_PORT",
        "MANAGEMENT_SERVER_PORT",
        "MANAGEMENT_SERVER_ADDRESS",
        "ADMIN_TOKEN",
        "APP_ADMIN_IDENTITIES_0_ID",
        "APP_ADMIN_IDENTITIES_0_TOKEN",
        "APP_ADMIN_IDENTITIES_0_ROLES",
        "JWT_PRIVATE_KEY",
        "JWT_PUBLIC_KEY",
        "JWT_ISSUER",
        "JWT_AUDIENCE",
        "JWT_KEY_ID",
        "APP_AUTH_JWT_KEYS_0_ID",
        "APP_AUTH_JWT_KEYS_0_PRIVATE_KEY",
        "APP_AUTH_JWT_KEYS_0_PUBLIC_KEY",
        "APP_AUTH_JWT_ACTIVE_KEY_ID",
        "ADMIN_ALLOWED_CIDRS",
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
        "RATE_LIMIT_ENABLED",
        "RATE_LIMIT_AUTH_LIMIT",
        "TRUSTED_PROXY_CIDRS"
    )
    foreach ($key in $envKeys) {
        $previousEnv[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
    }

    $env:SPRING_PROFILES_ACTIVE = "prod"
    $env:SPRING_FLYWAY_IGNORE_MIGRATION_PATTERNS = "*:missing"
    $env:DB_URL = "jdbc:postgresql://localhost:5432/ue_backend"
    $env:DB_USER = "ue_backend"
    $env:DB_PASSWORD = "ue_backend"
    $resourceValues = @{}
    foreach ($line in Get-Content -LiteralPath $resourceEnvelope) {
        if (-not [string]::IsNullOrWhiteSpace($line) -and -not $line.TrimStart().StartsWith("#")) {
            $parts = $line.Split("=", 2)
            $resourceValues[$parts[0].Trim()] = $parts[1].Trim()
        }
    }
    $env:JAVA_TOOL_OPTIONS = $resourceValues["JAVA_TOOL_OPTIONS"]
    & $resourcePreflight `
        -EnvelopePath $resourceEnvelope `
        -CpuLimit ([double]$resourceValues["APP_CPU_LIMIT"]) `
        -MemoryLimitMiB ([int]$resourceValues["APP_MEMORY_LIMIT_MIB"]) `
        -JavaToolOptions $env:JAVA_TOOL_OPTIONS | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Resource envelope preflight failed with exit code $LASTEXITCODE"
    }
    $env:SERVER_PORT = [string]$PublicPort
    $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
    $env:MANAGEMENT_SERVER_ADDRESS = "127.0.0.1"
    Remove-Item Env:ADMIN_TOKEN -ErrorAction SilentlyContinue
    $env:APP_ADMIN_IDENTITIES_0_ID = "prod-smoke-admin"
    Set-Content -LiteralPath $adminTokenFile -Value "prod-smoke-admin-token" -NoNewline -Encoding UTF8
    Set-Content -LiteralPath $keyStorePasswordFile -Value $password -NoNewline -Encoding UTF8
    Set-Content -LiteralPath $trustStorePasswordFile -Value $password -NoNewline -Encoding UTF8
    $env:APP_ADMIN_IDENTITIES_0_TOKEN = "file:$adminTokenFile"
    $env:APP_ADMIN_IDENTITIES_0_ROLES = "status,access,catalog,ops,security"
    & openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out $jwtPrivateKey | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "openssl genpkey failed with exit code $LASTEXITCODE"
    }
    & openssl rsa -in $jwtPrivateKey -pubout -out $jwtPublicKey | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "openssl rsa -pubout failed with exit code $LASTEXITCODE"
    }
    Remove-Item Env:JWT_PRIVATE_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:JWT_PUBLIC_KEY -ErrorAction SilentlyContinue
    Remove-Item Env:JWT_KEY_ID -ErrorAction SilentlyContinue
    $env:APP_AUTH_JWT_KEYS_0_ID = "prod-smoke-key"
    $env:APP_AUTH_JWT_KEYS_0_PRIVATE_KEY = "file:$jwtPrivateKey"
    $env:APP_AUTH_JWT_KEYS_0_PUBLIC_KEY = "file:$jwtPublicKey"
    $env:APP_AUTH_JWT_ACTIVE_KEY_ID = "prod-smoke-key"
    $env:JWT_ISSUER = "https://prod-smoke.example"
    $env:JWT_AUDIENCE = "prod-smoke-client"
    $env:ADMIN_ALLOWED_CIDRS = "127.0.0.1/32,::1/128"
    $env:CORS_ALLOWED_ORIGINS = $CorsOrigin
    $env:SERVER_MTLS_ENABLED = "true"
    $env:SERVER_MTLS_PORT = [string]$PrivateMtlsPort
    $env:SERVER_MTLS_REQUIRE_PRIVATE_PORT = "true"
    $env:SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK = "false"
    $env:SERVER_MTLS_KEY_STORE = "file:$backendP12"
    $env:SERVER_MTLS_KEY_STORE_PASSWORD = "file:$keyStorePasswordFile"
    $env:SERVER_MTLS_TRUST_STORE = "file:$truststore"
    $env:SERVER_MTLS_TRUST_STORE_PASSWORD = "file:$trustStorePasswordFile"
    $env:OUTBOX_WORKER_ENABLED = "false"
    $env:RATE_LIMIT_ENABLED = "true"
    $env:TRUSTED_PROXY_CIDRS = "127.0.0.1/32,::1/128"
    if ($VerifyMultiReplicaRateLimit) {
        $env:RATE_LIMIT_AUTH_LIMIT = "1"
    }

    $javaExe = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { "java" } else { Join-Path $env:JAVA_HOME "bin\java.exe" }
    $backendProcess = Start-Process `
        -FilePath $javaExe `
        -ArgumentList @("-jar", $jar.FullName) `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $stdout `
        -RedirectStandardError $stderr `
        -WindowStyle Hidden `
        -PassThru

    Wait-HttpOk -Url "http://localhost:$ManagementPort/actuator/health/readiness" -TimeoutSeconds $StartupTimeoutSeconds

    $info = Invoke-WebRequest -Uri "http://localhost:$ManagementPort/actuator/info" -UseBasicParsing -TimeoutSec 5
    if ($info.StatusCode -ne 200) {
        throw "Expected /actuator/info to return 200, got $($info.StatusCode)"
    }

    $metricsStatus = $null
    try {
        Invoke-WebRequest -Uri "http://localhost:$ManagementPort/actuator/metrics" -UseBasicParsing -TimeoutSec 5 | Out-Null
        $metricsStatus = 200
    } catch {
        if ($null -ne $_.Exception.Response) {
            $metricsStatus = [int]$_.Exception.Response.StatusCode
        }
    }
    if ($metricsStatus -eq 200) {
        throw "Expected /actuator/metrics to be unavailable over prod HTTP exposure."
    }

    $publicHealthStatus = $null
    try {
        Invoke-WebRequest -Uri "http://localhost:$PublicPort/actuator/health" -UseBasicParsing -TimeoutSec 5 | Out-Null
        $publicHealthStatus = 200
    } catch {
        if ($null -ne $_.Exception.Response) {
            $publicHealthStatus = [int]$_.Exception.Response.StatusCode
        }
    }
    if ($publicHealthStatus -eq 200) {
        throw "Expected Actuator health to be unavailable over the public connector."
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

    $redisOutageStatus = $null
    if ($VerifyRedisOutage) {
        & docker compose stop redis | Out-Null
        if ($LASTEXITCODE -ne 0) {
            throw "docker compose stop redis failed with exit code $LASTEXITCODE"
        }
        $redisRestartRequired = $true
        try {
            try {
                Invoke-WebRequest `
                    -Method Post `
                    -Uri "http://localhost:$PublicPort/auth/login" `
                    -ContentType "application/json" `
                    -Body '{"login":"redis-outage-smoke","password":"irrelevant"}' `
                    -UseBasicParsing `
                    -TimeoutSec 10 | Out-Null
                $redisOutageStatus = 200
            } catch {
                if ($null -ne $_.Exception.Response) {
                    $redisOutageStatus = [int]$_.Exception.Response.StatusCode
                } else {
                    throw
                }
            }
            if ($redisOutageStatus -ne 503) {
                throw "Expected /auth/login to fail closed with 503 during Redis outage, got $redisOutageStatus."
            }
        } finally {
            & docker compose start redis | Out-Null
            if ($LASTEXITCODE -ne 0) {
                throw "docker compose start redis failed with exit code $LASTEXITCODE"
            }
            $redisRestartRequired = $false
            Wait-RedisReady
        }
    }

    $multiReplicaSecondStatus = $null
    if ($VerifyMultiReplicaRateLimit) {
        $replicaPublicPort = $PublicPort + 10
        $replicaManagementPort = $ManagementPort + 10
        $replicaPrivateMtlsPort = $PrivateMtlsPort + 10
        $replicaStdout = Join-Path $logDir "backend-prod-smoke-replica.out.log"
        $replicaStderr = Join-Path $logDir "backend-prod-smoke-replica.err.log"
        Remove-Item $replicaStdout, $replicaStderr -Force -ErrorAction SilentlyContinue
        $env:SERVER_PORT = [string]$replicaPublicPort
        $env:MANAGEMENT_SERVER_PORT = [string]$replicaManagementPort
        $env:SERVER_MTLS_PORT = [string]$replicaPrivateMtlsPort
        $env:RATE_LIMIT_AUTH_LIMIT = "1"
        $replicaProcess = Start-Process `
            -FilePath $javaExe `
            -ArgumentList @("-jar", $jar.FullName) `
            -WorkingDirectory $backendDir `
            -RedirectStandardOutput $replicaStdout `
            -RedirectStandardError $replicaStderr `
            -WindowStyle Hidden `
            -PassThru
        Wait-HttpOk -Url "http://localhost:$replicaManagementPort/actuator/health/readiness" -TimeoutSeconds $StartupTimeoutSeconds

        $env:SERVER_PORT = [string]$PublicPort
        $env:MANAGEMENT_SERVER_PORT = [string]$ManagementPort
        $env:SERVER_MTLS_PORT = [string]$PrivateMtlsPort
        $env:RATE_LIMIT_AUTH_LIMIT = "1"
        $clientIp = "198.51.100.$(Get-Random -Minimum 1 -Maximum 255)"
        $forwardedHeaders = @{ "X-Forwarded-For" = $clientIp }
        $firstReplicaStatus = Invoke-HttpStatus -Uri "http://localhost:$PublicPort/auth/login" -Headers $forwardedHeaders
        if ($firstReplicaStatus -eq 429) {
            throw "Expected first replica request to be below rate limit, got 429."
        }
        $multiReplicaSecondStatus = Invoke-HttpStatus `
            -Uri "http://localhost:$replicaPublicPort/auth/login" `
            -Headers $forwardedHeaders
        if ($multiReplicaSecondStatus -ne 429) {
            throw "Expected second replica request to share Redis rate limit and return 429, got $multiReplicaSecondStatus."
        }
    }

    [PSCustomObject]@{
        status = "PROD_PROFILE_SMOKE_OK"
        public_port = $PublicPort
        management_port = $ManagementPort
        private_mtls_port = $PrivateMtlsPort
        cors_origin = $CorsOrigin
        metrics_status = $metricsStatus
        public_health_status = $publicHealthStatus
        redis_outage_status = $redisOutageStatus
        multi_replica_second_status = $multiReplicaSecondStatus
        java_tool_options = $env:JAVA_TOOL_OPTIONS
        jar = $jar.FullName
    }
} finally {
    if ($redisRestartRequired) {
        & docker compose start redis | Out-Null
    }
    if (-not $KeepBackendRunning) {
        Stop-ProcessTree -Process $replicaProcess
    }
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
    Remove-Item -LiteralPath $adminTokenFile -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $keyStorePasswordFile, $trustStorePasswordFile -Force -ErrorAction SilentlyContinue
    Pop-Location
}
