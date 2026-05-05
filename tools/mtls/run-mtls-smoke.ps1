param(
    [string]$RepoRoot = "",
    [string]$CertOutputDir = "",
    [string]$Password = "changeit",
    [string]$ServerId = "10000000-0000-0000-0000-000000000001",
    [int]$PublicPort = 8080,
    [int]$PrivateMtlsPort = 9443,
    [int]$StartupTimeoutSeconds = 120,
    [switch]$SkipDocker,
    [switch]$SkipPortCheck,
    [switch]$KeepBackendRunning
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step {
    param([string]$Message)
    Write-Host "`n==> $Message" -ForegroundColor Cyan
}

function Write-Ok {
    param([string]$Message)
    Write-Host "OK: $Message" -ForegroundColor Green
}

function Fail {
    param([string]$Message)
    throw $Message
}

function Require-Command {
    param([string]$Name)
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Fail "Required command '$Name' was not found in PATH."
    }
}

function Resolve-RepoRoot {
    param([string]$ProvidedRepoRoot)
    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path $ProvidedRepoRoot).Path
    }

    # tools\mtls\run-mtls-smoke.ps1 -> repo root
    return (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
}

function Test-TcpPortOpen {
    param(
        [string]$HostName,
        [int]$Port
    )
    $client = New-Object System.Net.Sockets.TcpClient
    try {
        $async = $client.BeginConnect($HostName, $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(500)
        if (-not $connected) {
            return $false
        }
        $client.EndConnect($async)
        return $true
    } catch {
        return $false
    } finally {
        $client.Close()
    }
}

function Invoke-Compose {
    param(
        [string]$RepoRoot,
        [string[]]$Arguments
    )
    Push-Location $RepoRoot
    try {
        & docker compose @Arguments
        if ($LASTEXITCODE -ne 0) {
            Fail "docker compose $($Arguments -join ' ') failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

function Invoke-PostgresSql {
    param(
        [string]$RepoRoot,
        [string]$Sql,
        [switch]$Scalar
    )

    Push-Location $RepoRoot
    try {
        $args = @(
            "compose", "exec", "-T", "postgres",
            "psql", "-U", "ue_backend", "-d", "ue_backend", "-v", "ON_ERROR_STOP=1"
        )
        if ($Scalar) {
            $args += @("-t", "-A")
        }
        $args += @("-c", $Sql)
        $output = & docker @args
        if ($LASTEXITCODE -ne 0) {
            Fail "psql command failed with exit code $LASTEXITCODE. SQL: $Sql"
        }
        if ($Scalar) {
            return (($output | Out-String).Trim())
        }
        return $output
    } finally {
        Pop-Location
    }
}

function Get-CertFingerprintSha256 {
    param([string]$CertificatePath)
    $line = & openssl x509 -in $CertificatePath -noout -fingerprint -sha256
    if ($LASTEXITCODE -ne 0) {
        Fail "Failed to read certificate fingerprint from $CertificatePath"
    }
    return (($line -replace '^.*=', '') -replace ':', '').ToLowerInvariant()
}

function Wait-HttpOk {
    param(
        [string]$Url,
        [int]$TimeoutSeconds
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            $result = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 3
            if ($result.StatusCode -ge 200 -and $result.StatusCode -lt 500) {
                return
            }
        } catch {
            $lastError = $_.Exception.Message
        }
        Start-Sleep -Seconds 2
    }
    Fail "Timed out waiting for $Url. Last error: $lastError"
}



function Resolve-JavaTool {
    param([string]$Name)

    $extension = ""
    if (Test-RunningOnWindows) {
        $extension = ".exe"
    }

    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidate = Join-Path $env:JAVA_HOME ("bin\" + $Name + $extension)
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    Fail "Required Java tool '$Name' was not found. Install JDK 21 or set JAVA_HOME."
}

function ConvertTo-CompactJson {
    param([object]$Body)
    return ($Body | ConvertTo-Json -Depth 20 -Compress)
}

function Initialize-JavaSmokeClient {
    param([string]$RepoRoot)

    $script:JavaCommand = Resolve-JavaTool -Name "java"
    $javacCommand = Resolve-JavaTool -Name "javac"

    $workDir = Join-Path $RepoRoot "tools\mtls\work\java-client"
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null
    $javaFile = Join-Path $workDir "MtlsSmokeHttpClient.java"
    $classFile = Join-Path $workDir "MtlsSmokeHttpClient.class"

    $javaSource = @'
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;

public final class MtlsSmokeHttpClient {
    private MtlsSmokeHttpClient() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 9) {
            throw new IllegalArgumentException("Usage: <method> <uri> <body-file|-> <response-file> <status-file> <client-p12|-> <client-pass|-> <truststore|-> <trust-pass|-> [Header: value]...");
        }

        String method = args[0];
        URI uri = URI.create(args[1]);
        String bodyFile = args[2];
        Path responseFile = Path.of(args[3]);
        Path statusFile = Path.of(args[4]);
        String clientStorePath = args[5];
        String clientStorePassword = args[6];
        String trustStorePath = args[7];
        String trustStorePassword = args[8];

        HttpClient.Builder clientBuilder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));

        if ("https".equalsIgnoreCase(uri.getScheme())) {
            clientBuilder.sslContext(buildSslContext(
                    clientStorePath,
                    clientStorePassword,
                    trustStorePath,
                    trustStorePassword
            ));
        }

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30));

        for (int i = 9; i < args.length; i++) {
            String header = args[i];
            int colonIndex = header.indexOf(':');
            if (colonIndex <= 0) {
                throw new IllegalArgumentException("Invalid header argument: " + header);
            }
            String name = header.substring(0, colonIndex).trim();
            String value = header.substring(colonIndex + 1).trim();
            requestBuilder.header(name, value);
        }

        if (!"-".equals(bodyFile)) {
            String body = Files.readString(Path.of(bodyFile), StandardCharsets.UTF_8);
            requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response = clientBuilder.build().send(
                requestBuilder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );

        Files.writeString(statusFile, Integer.toString(response.statusCode()), StandardCharsets.UTF_8);
        Files.writeString(responseFile, response.body() == null ? "" : response.body(), StandardCharsets.UTF_8);
    }

    private static SSLContext buildSslContext(
            String clientStorePath,
            String clientStorePassword,
            String trustStorePath,
            String trustStorePassword
    ) throws Exception {
        KeyManager[] keyManagers = null;
        if (!"-".equals(clientStorePath)) {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            char[] password = clientStorePassword.toCharArray();
            try (FileInputStream input = new FileInputStream(clientStorePath)) {
                keyStore.load(input, password);
            }
            KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(keyStore, password);
            keyManagers = keyManagerFactory.getKeyManagers();
        }

        TrustManager[] trustManagers = null;
        if (!"-".equals(trustStorePath)) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            char[] password = trustStorePassword.toCharArray();
            try (FileInputStream input = new FileInputStream(trustStorePath)) {
                trustStore.load(input, password);
            }
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagerFactory.init(trustStore);
            trustManagers = trustManagerFactory.getTrustManagers();
        }

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers, trustManagers, new SecureRandom());
        return sslContext;
    }
}
'@

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($javaFile, $javaSource, $utf8NoBom)

    & $javacCommand "-encoding" "UTF-8" "-d" $workDir $javaFile
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $classFile)) {
        Fail "Failed to compile Java mTLS smoke client. javac exit code: $LASTEXITCODE"
    }

    $script:JavaSmokeClientClasspath = $workDir
}

function Invoke-JsonHttp {
    param(
        [ValidateSet("GET", "POST", "PUT", "PATCH", "DELETE")]
        [string]$Method,
        [string]$Uri,
        [object]$Body = $null,
        [hashtable]$Headers = @{},
        [string]$ClientCertificatePath = "",
        [string]$ClientCertificatePassword = ""
    )

    if ([string]::IsNullOrWhiteSpace($script:JavaCommand) -or [string]::IsNullOrWhiteSpace($script:JavaSmokeClientClasspath)) {
        Fail "Java smoke client was not initialized."
    }

    $tempDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ue-backend-mtls-smoke-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $responseFile = Join-Path $tempDir "response.json"
    $statusFile = Join-Path $tempDir "status.txt"
    $bodyFile = Join-Path $tempDir "request.json"

    try {
        $bodyArg = "-"
        if ($null -ne $Body) {
            $json = ConvertTo-CompactJson -Body $Body
            $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
            [System.IO.File]::WriteAllText($bodyFile, $json, $utf8NoBom)
            $bodyArg = $bodyFile
            if (-not $Headers.ContainsKey("Content-Type")) {
                $Headers["Content-Type"] = "application/json"
            }
        }

        $clientStoreArg = "-"
        $clientStorePassArg = "-"
        if (-not [string]::IsNullOrWhiteSpace($ClientCertificatePath)) {
            $clientStoreArg = $ClientCertificatePath
            $clientStorePassArg = $ClientCertificatePassword
        }

        $trustStoreArg = "-"
        $trustStorePassArg = "-"
        if ($Uri.StartsWith("https://", [System.StringComparison]::OrdinalIgnoreCase)) {
            $trustStoreArg = $truststore
            $trustStorePassArg = $Password
        }

        $javaArgs = @(
            "-cp", $script:JavaSmokeClientClasspath,
            "MtlsSmokeHttpClient",
            $Method,
            $Uri,
            $bodyArg,
            $responseFile,
            $statusFile,
            $clientStoreArg,
            $clientStorePassArg,
            $trustStoreArg,
            $trustStorePassArg
        )

        foreach ($key in $Headers.Keys) {
            $javaArgs += ("{0}: {1}" -f $key, [string]$Headers[$key])
        }

        $nativeOutput = & $script:JavaCommand @javaArgs 2>&1
        $exitCode = $LASTEXITCODE
        $rawBody = if (Test-Path $responseFile) { Get-Content $responseFile -Raw } else { "" }
        $statusText = if (Test-Path $statusFile) { (Get-Content $statusFile -Raw).Trim() } else { "" }

        if ($exitCode -ne 0) {
            throw "Java HTTP client failed with exit code $exitCode for $Method $Uri. Output: $($nativeOutput | Out-String). Body: $rawBody"
        }

        if (-not ($statusText -match '^(\d{3})$')) {
            throw "Java HTTP client did not return a valid HTTP status for $Method $Uri. Status: $statusText. Output: $($nativeOutput | Out-String). Body: $rawBody"
        }

        $jsonBody = $null
        if (-not [string]::IsNullOrWhiteSpace($rawBody)) {
            try {
                $jsonBody = $rawBody | ConvertFrom-Json
            } catch {
                $jsonBody = $null
            }
        }

        return [pscustomobject]@{
            StatusCode = [int]$statusText
            Body = $jsonBody
            RawBody = $rawBody
        }
    } finally {
        Remove-Item $tempDir -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function Assert-Status {
    param(
        [object]$Response,
        [int]$ExpectedStatus,
        [string]$Scenario
    )
    if ($Response.StatusCode -ne $ExpectedStatus) {
        Fail "$Scenario expected HTTP $ExpectedStatus, got $($Response.StatusCode). Body: $($Response.RawBody)"
    }
    Write-Ok "$Scenario returned HTTP $ExpectedStatus"
}


function Test-RunningOnWindows {
    if ($env:OS -eq "Windows_NT") {
        return $true
    }
    return [System.Environment]::OSVersion.Platform -eq [System.PlatformID]::Win32NT
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    if (Test-RunningOnWindows) {
        & taskkill.exe /PID $Process.Id /T /F | Out-Null
    } else {
        Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
    }
}

$repoRoot = Resolve-RepoRoot -ProvidedRepoRoot $RepoRoot
$backendDir = Join-Path $repoRoot "backend"
$generateCertsScript = Join-Path $repoRoot "tools\mtls\generate-dev-certs.ps1"
$gradleWrapper = Join-Path $backendDir "gradlew.bat"
if (-not (Test-Path $gradleWrapper)) {
    $gradleWrapper = Join-Path $backendDir "gradlew"
}

if ([string]::IsNullOrWhiteSpace($CertOutputDir)) {
    $CertOutputDir = Join-Path $repoRoot "tools\mtls\out"
} elseif (-not [System.IO.Path]::IsPathRooted($CertOutputDir)) {
    $CertOutputDir = Join-Path $repoRoot $CertOutputDir
}
$CertOutputDir = (New-Item -ItemType Directory -Force -Path $CertOutputDir).FullName

$clientP12 = Join-Path $CertOutputDir "ds-client.p12"
$clientCrt = Join-Path $CertOutputDir "ds-client.crt"
$backendP12 = Join-Path $CertOutputDir "backend.p12"
$truststore = Join-Path $CertOutputDir "backend-truststore.p12"
$backendLogDir = Join-Path $repoRoot "tools\mtls\logs"
$backendStdout = Join-Path $backendLogDir "backend-mtls-smoke.out.log"
$backendStderr = Join-Path $backendLogDir "backend-mtls-smoke.err.log"
$backendProcess = $null
$originalFingerprint = $null
$originalStatus = $null

try {
    Write-Step "Validate prerequisites"
    Require-Command "docker"
    Require-Command "openssl"
    Require-Command "keytool"
    if (-not (Test-Path $generateCertsScript)) {
        Fail "Certificate generator not found: $generateCertsScript"
    }
    if (-not (Test-Path $gradleWrapper)) {
        Fail "Gradle wrapper not found in $backendDir"
    }
    Initialize-JavaSmokeClient -RepoRoot $repoRoot
    Write-Ok "required commands and project files found"

    if (-not $SkipPortCheck) {
        Write-Step "Check local ports"
        if (Test-TcpPortOpen -HostName "127.0.0.1" -Port $PublicPort) {
            Fail "Port $PublicPort is already open. Stop the running backend or pass -SkipPortCheck if this is intentional."
        }
        if (Test-TcpPortOpen -HostName "127.0.0.1" -Port $PrivateMtlsPort) {
            Fail "Port $PrivateMtlsPort is already open. Stop the running backend or pass -SkipPortCheck if this is intentional."
        }
        Write-Ok "ports $PublicPort and $PrivateMtlsPort are available"
    }

    if (-not $SkipDocker) {
        Write-Step "Start docker test dependencies"
        Invoke-Compose -RepoRoot $repoRoot -Arguments @("up", "-d", "postgres", "redis")
        Write-Ok "docker dependencies are running"
    }

    Write-Step "Generate dev mTLS certificates"
    & $generateCertsScript -OutputDir $CertOutputDir -Password $Password -BackendDns "localhost" -BackendIp "127.0.0.1" -ServerId $ServerId
    if ($LASTEXITCODE -ne 0) {
        Fail "generate-dev-certs.ps1 failed with exit code $LASTEXITCODE"
    }
    $clientFingerprint = Get-CertFingerprintSha256 -CertificatePath $clientCrt
    Write-Ok "client certificate fingerprint: $clientFingerprint"

    Write-Step "Start backend with real mTLS private connector"
    New-Item -ItemType Directory -Force -Path $backendLogDir | Out-Null
    Remove-Item $backendStdout, $backendStderr -Force -ErrorAction SilentlyContinue

    $previousEnv = @{}
    $envKeys = @(
        "SPRING_PROFILES_ACTIVE",
        "SERVER_MTLS_ENABLED",
        "SERVER_MTLS_PORT",
        "SERVER_MTLS_REQUIRE_PRIVATE_PORT",
        "SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK",
        "SERVER_MTLS_KEY_STORE",
        "SERVER_MTLS_KEY_STORE_PASSWORD",
        "SERVER_MTLS_TRUST_STORE",
        "SERVER_MTLS_TRUST_STORE_PASSWORD",
        "OUTBOX_WORKER_ENABLED"
    )
    foreach ($key in $envKeys) {
        $previousEnv[$key] = [Environment]::GetEnvironmentVariable($key, "Process")
    }

    $env:SPRING_PROFILES_ACTIVE = "local"
    $env:SERVER_MTLS_ENABLED = "true"
    $env:SERVER_MTLS_PORT = [string]$PrivateMtlsPort
    $env:SERVER_MTLS_REQUIRE_PRIVATE_PORT = "true"
    $env:SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK = "false"
    $env:SERVER_MTLS_KEY_STORE = "file:$backendP12"
    $env:SERVER_MTLS_KEY_STORE_PASSWORD = $Password
    $env:SERVER_MTLS_TRUST_STORE = "file:$truststore"
    $env:SERVER_MTLS_TRUST_STORE_PASSWORD = $Password
    $env:OUTBOX_WORKER_ENABLED = "false"

    $backendProcess = Start-Process `
        -FilePath $gradleWrapper `
        -ArgumentList @("bootRun", "--no-daemon") `
        -WorkingDirectory $backendDir `
        -RedirectStandardOutput $backendStdout `
        -RedirectStandardError $backendStderr `
        -PassThru

    Write-Host "Backend PID: $($backendProcess.Id)"
    Write-Host "Backend stdout: $backendStdout"
    Write-Host "Backend stderr: $backendStderr"

    Wait-HttpOk -Url "http://localhost:$PublicPort/actuator/health" -TimeoutSeconds $StartupTimeoutSeconds
    Write-Ok "backend health endpoint is reachable"

    if ($backendProcess.HasExited) {
        $stdout = if (Test-Path $backendStdout) { Get-Content $backendStdout -Raw } else { "" }
        $stderr = if (Test-Path $backendStderr) { Get-Content $backendStderr -Raw } else { "" }
        Fail "Backend process exited early with code $($backendProcess.ExitCode).`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }

    Write-Step "Update dev server identity fingerprint in PostgreSQL"
    $originalFingerprint = Invoke-PostgresSql -RepoRoot $repoRoot -Scalar -Sql "SELECT certificate_fingerprint FROM server_identities WHERE server_id = '$ServerId';"
    $originalStatus = Invoke-PostgresSql -RepoRoot $repoRoot -Scalar -Sql "SELECT status FROM server_identities WHERE server_id = '$ServerId';"
    Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET certificate_fingerprint = '$clientFingerprint', status = 'active', expires_at = now() + interval '365 days' WHERE server_id = '$ServerId';" | Out-Null
    Write-Ok "server identity updated"

    Write-Step "Create smoke player via public API"
    $loginName = "mtls_smoke_" + ([Guid]::NewGuid().ToString("N").Substring(0, 12))
    $registerResponse = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "http://localhost:$PublicPort/auth/register" `
        -Body @{ login_name = $loginName; password = "password123" }
    Assert-Status -Response $registerResponse -ExpectedStatus 201 -Scenario "public register"
    $playerId = [string]$registerResponse.Body.player_id
    if ([string]::IsNullOrWhiteSpace($playerId)) {
        Fail "Register response did not contain player_id. Body: $($registerResponse.RawBody)"
    }
    Write-Ok "smoke player created: $playerId"

    $buildBody = @{
        match_id = [Guid]::NewGuid().ToString()
        player_id = $playerId
        realm_id = "global"
        class_tag = "class.assault"
        team_tag = "team.red"
        weapon_preset_slot = 1
        outfit_preset_slot = 1
        supported_catalog_versions = @(1)
        preferred_catalog_version = 1
        server_build_id = "ds-dev-smoke"
    }

    Write-Step "Positive check: private /server/* with valid client certificate"
    $positive = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "https://localhost:$PrivateMtlsPort/server/match-profile/build" `
        -Headers @{ "X-Server-Id" = $ServerId } `
        -ClientCertificatePath $clientP12 `
        -ClientCertificatePassword $Password `
        -Body $buildBody
    Assert-Status -Response $positive -ExpectedStatus 200 -Scenario "mTLS private match-profile build"
    if ([string]$positive.Body.player_id -ne $playerId) {
        Fail "mTLS positive check returned unexpected player_id. Body: $($positive.RawBody)"
    }
    Write-Ok "positive mTLS snapshot built for player $playerId"

    Write-Step "Negative check: private /server/* without client certificate"
    try {
        $noCert = Invoke-JsonHttp `
            -Method "POST" `
            -Uri "https://localhost:$PrivateMtlsPort/server/match-profile/build" `
            -Headers @{ "X-Server-Id" = $ServerId } `
            -Body $buildBody
        if ($noCert.StatusCode -eq 401 -or $noCert.StatusCode -eq 403) {
            Write-Ok "private request without client cert rejected with HTTP $($noCert.StatusCode)"
        } else {
            Fail "private request without client cert should fail handshake or return 401/403, got HTTP $($noCert.StatusCode). Body: $($noCert.RawBody)"
        }
    } catch {
        Write-Ok "private request without client cert failed before controller: $($_.Exception.Message)"
    }

    Write-Step "Negative check: header fingerprint fallback is disabled on public port"
    $publicServer = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "http://localhost:$PublicPort/server/match-profile/build" `
        -Headers @{ "X-Server-Id" = $ServerId; "X-Server-Certificate-Fingerprint" = $clientFingerprint } `
        -Body $buildBody
    Assert-Status -Response $publicServer -ExpectedStatus 401 -Scenario "public /server/* with header fallback disabled"

    Write-Step "Negative check: valid client cert but unknown server identity"
    $unknownServer = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "https://localhost:$PrivateMtlsPort/server/match-profile/build" `
        -Headers @{ "X-Server-Id" = [Guid]::NewGuid().ToString() } `
        -ClientCertificatePath $clientP12 `
        -ClientCertificatePassword $Password `
        -Body $buildBody
    Assert-Status -Response $unknownServer -ExpectedStatus 401 -Scenario "unknown server identity"

    Write-Step "Negative check: valid client cert but mismatched stored fingerprint"
    Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET certificate_fingerprint = '0000000000000000000000000000000000000000000000000000000000000000' WHERE server_id = '$ServerId';" | Out-Null
    $fingerprintMismatch = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "https://localhost:$PrivateMtlsPort/server/match-profile/build" `
        -Headers @{ "X-Server-Id" = $ServerId } `
        -ClientCertificatePath $clientP12 `
        -ClientCertificatePassword $Password `
        -Body $buildBody
    Assert-Status -Response $fingerprintMismatch -ExpectedStatus 401 -Scenario "fingerprint mismatch"
    Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET certificate_fingerprint = '$clientFingerprint', status = 'active', expires_at = now() + interval '365 days' WHERE server_id = '$ServerId';" | Out-Null

    Write-Step "Negative check: revoked server identity"
    Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET status = 'revoked', revoked_at = now() WHERE server_id = '$ServerId';" | Out-Null
    $revoked = Invoke-JsonHttp `
        -Method "POST" `
        -Uri "https://localhost:$PrivateMtlsPort/server/match-profile/build" `
        -Headers @{ "X-Server-Id" = $ServerId } `
        -ClientCertificatePath $clientP12 `
        -ClientCertificatePassword $Password `
        -Body $buildBody
    Assert-Status -Response $revoked -ExpectedStatus 401 -Scenario "revoked server identity"
    Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET certificate_fingerprint = '$clientFingerprint', status = 'active', revoked_at = null, expires_at = now() + interval '365 days' WHERE server_id = '$ServerId';" | Out-Null

    Write-Step "mTLS smoke result"
    Write-Host "All mTLS smoke checks passed." -ForegroundColor Green
} finally {
    if ($null -ne $originalFingerprint -and -not [string]::IsNullOrWhiteSpace($originalFingerprint)) {
        try {
            $restoreStatus = if ([string]::IsNullOrWhiteSpace($originalStatus)) { "active" } else { $originalStatus }
            Invoke-PostgresSql -RepoRoot $repoRoot -Sql "UPDATE server_identities SET certificate_fingerprint = '$originalFingerprint', status = '$restoreStatus', revoked_at = null WHERE server_id = '$ServerId';" | Out-Null
        } catch {
            Write-Warning "Failed to restore original server identity state: $($_.Exception.Message)"
        }
    }

    if (-not $KeepBackendRunning) {
        Stop-ProcessTree -Process $backendProcess
    } elseif ($null -ne $backendProcess -and -not $backendProcess.HasExited) {
        Write-Host "Backend left running with PID $($backendProcess.Id) because -KeepBackendRunning was passed." -ForegroundColor Yellow
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
}
