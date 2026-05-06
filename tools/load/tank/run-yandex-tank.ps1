param(
    [int]$Players = 20,
    [string]$Schedule = "line(5, 50, 2m) const(50, 2m)",
    [string]$TankImage = "yandex/yandex-tank:latest",
    [string]$TargetHostForTank = "",
    [int]$PublicPort = 8080,
    [int]$StartupTimeoutSeconds = 120,
    [switch]$SkipBackendStart,
    [switch]$KeepBackendRunning,
    [switch]$SkipDockerDependencies,
    [switch]$NoServerApi,
    [switch]$NoLoginLoad,
    [int]$AmmoCopiesPerPlayer = 1
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "OK: $Message" -ForegroundColor Green
}

function Fail([string]$Message) {
    throw $Message
}

function Test-IsWindowsHost {
    return $env:OS -eq "Windows_NT"
}

function Require-Command([string]$Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        Fail "Required command '$Name' was not found in PATH."
    }
}

function Get-RepoRoot {
    $scriptDir = Split-Path -Parent $PSCommandPath
    return (Resolve-Path (Join-Path $scriptDir "..\..\..")).Path
}

function Invoke-JsonRequest {
    param(
        [Parameter(Mandatory=$true)][string]$Method,
        [Parameter(Mandatory=$true)][string]$Uri,
        [object]$Body = $null,
        [hashtable]$Headers = @{}
    )

    $params = @{
        Method = $Method
        Uri = $Uri
        Headers = $Headers
        TimeoutSec = 30
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    return Invoke-RestMethod @params
}

function Wait-BackendHealth {
    param(
        [string]$HealthUrl,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $health = Invoke-RestMethod -Method Get -Uri $HealthUrl -TimeoutSec 3
            if ($health.status -eq "UP") {
                return
            }
        } catch {
            Start-Sleep -Seconds 2
        }
    }
    Fail "Backend health endpoint was not reachable in $TimeoutSeconds seconds: $HealthUrl"
}

function Test-PortFree {
    param([int]$Port)
    try {
        $client = New-Object System.Net.Sockets.TcpClient
        $async = $client.BeginConnect("127.0.0.1", $Port, $null, $null)
        $connected = $async.AsyncWaitHandle.WaitOne(500)
        if ($connected -and $client.Connected) {
            $client.Close()
            return $false
        }
        $client.Close()
        return $true
    } catch {
        return $true
    }
}

function New-HttpAmmoBlock {
    param(
        [Parameter(Mandatory=$true)][string]$Method,
        [Parameter(Mandatory=$true)][string]$Path,
        [Parameter(Mandatory=$true)][string]$HostHeader,
        [Parameter(Mandatory=$true)][string]$Tag,
        [hashtable]$Headers = @{},
        [string]$Body = ""
    )

    $crlf = "`r`n"
    $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $request = "$Method $Path HTTP/1.1$crlf"
    $request += "Host: $HostHeader$crlf"
    $request += "User-Agent: ue-backend-yandex-tank$crlf"
    $request += "Accept: application/json$crlf"
    $request += "Connection: close$crlf"

    foreach ($key in $Headers.Keys) {
        $request += "${key}: $($Headers[$key])$crlf"
    }

    if ($Body.Length -gt 0) {
        if (-not $Headers.ContainsKey("Content-Type")) {
            $request += "Content-Type: application/json$crlf"
        }
        $request += "Content-Length: $($bodyBytes.Length)$crlf"
    }

    $request += $crlf
    if ($Body.Length -gt 0) {
        $request += $Body
    }

    $requestSize = [System.Text.Encoding]::UTF8.GetByteCount($request)
    return "$requestSize $Tag`n$request`r`n"
}

function New-GuidString {
    return ([guid]::NewGuid()).ToString()
}

function ConvertTo-CompactJson([object]$Value) {
    return ($Value | ConvertTo-Json -Depth 30 -Compress)
}

function Write-LoadYaml {
    param(
        [string]$Path,
        [string]$TargetAddress,
        [string]$Schedule,
        [string]$AmmoPath,
        [string]$PhoutPath
    )

    $yaml = @"
phantom:
  address: $TargetAddress
  ammo_type: phantom
  ammofile: $AmmoPath
  load_profile:
    load_type: rps
    schedule: $Schedule
  instances: 1000
  loop: -1
  writelog: none
  phout_file: $PhoutPath
console:
  enabled: true
telegraf:
  enabled: false
autostop:
  autostop:
    - http(5xx,10%,10s)
    - net(xx,1,30s)
    - time(2s,15s)
"@
    Set-Content -Path $Path -Value $yaml -Encoding UTF8
}

function Analyze-Phout {
    param(
        [string]$PhoutPath,
        [string]$SummaryPath
    )

    if (-not (Test-Path $PhoutPath)) {
        Write-Host "WARN: phout file was not found: $PhoutPath" -ForegroundColor Yellow
        return
    }

    $groups = @{}
    $total = 0
    $failed = 0

    Get-Content $PhoutPath | ForEach-Object {
        $line = $_.Trim()
        if ($line.Length -eq 0) { return }
        $parts = $line -split "\s+"
        if ($parts.Count -lt 12) { return }

        $tag = $parts[1]
        $intervalRealMicros = 0.0
        [void][double]::TryParse($parts[2], [Globalization.NumberStyles]::Any, [Globalization.CultureInfo]::InvariantCulture, [ref]$intervalRealMicros)
        $netCode = 0
        $protoCode = 0
        [void][int]::TryParse($parts[$parts.Count - 2], [ref]$netCode)
        [void][int]::TryParse($parts[$parts.Count - 1], [ref]$protoCode)

        if (-not $groups.ContainsKey($tag)) {
            $groups[$tag] = New-Object System.Collections.ArrayList
        }
        [void]$groups[$tag].Add([pscustomobject]@{
            Ms = $intervalRealMicros / 1000.0
            NetCode = $netCode
            ProtoCode = $protoCode
        })
        $total++
        if ($netCode -ne 0 -or $protoCode -lt 200 -or $protoCode -ge 400) {
            $failed++
        }
    }

    $rows = New-Object System.Collections.ArrayList
    foreach ($tag in ($groups.Keys | Sort-Object)) {
        $items = @($groups[$tag])
        $latencies = @($items | ForEach-Object { $_.Ms } | Sort-Object)
        if ($latencies.Count -eq 0) { continue }
        $p50Index = [Math]::Min($latencies.Count - 1, [int][Math]::Floor($latencies.Count * 0.50))
        $p95Index = [Math]::Min($latencies.Count - 1, [int][Math]::Ceiling($latencies.Count * 0.95) - 1)
        $p99Index = [Math]::Min($latencies.Count - 1, [int][Math]::Ceiling($latencies.Count * 0.99) - 1)
        $errors = @($items | Where-Object { $_.NetCode -ne 0 -or $_.ProtoCode -lt 200 -or $_.ProtoCode -ge 400 }).Count
        [void]$rows.Add([pscustomobject]@{
            tag = $tag
            count = $items.Count
            errors = $errors
            error_rate = if ($items.Count -eq 0) { 0 } else { [Math]::Round($errors / $items.Count, 4) }
            p50_ms = [Math]::Round($latencies[$p50Index], 2)
            p95_ms = [Math]::Round($latencies[$p95Index], 2)
            p99_ms = [Math]::Round($latencies[$p99Index], 2)
        })
    }

    $rows | Export-Csv -NoTypeInformation -Encoding UTF8 -Path $SummaryPath
    Write-Host ""
    Write-Host "Yandex.Tank phout summary:" -ForegroundColor Cyan
    $rows | Format-Table -AutoSize

    if ($total -gt 0) {
        $errorRate = $failed / $total
        if ($errorRate -ge 0.01) {
            Fail "Load test failed: error rate $([Math]::Round($errorRate * 100, 2))% is >= 1%. See $SummaryPath"
        }
    }
}

$repoRoot = Get-RepoRoot
$backendDir = Join-Path $repoRoot "backend"
$tankDir = Join-Path $repoRoot "tools\load\tank"
$generatedDir = Join-Path $tankDir "generated"
$resultsDir = Join-Path $tankDir "results"
$logsDir = Join-Path $resultsDir "backend-logs"
$publicBaseUrl = "http://localhost:$PublicPort"
$backendProcess = $null

try {
    Write-Step "Validate prerequisites"
    Require-Command "docker"
    Require-Command "java"
    if (-not (Test-Path (Join-Path $backendDir "gradlew.bat")) -and -not (Test-Path (Join-Path $backendDir "gradlew"))) {
        Fail "Gradle wrapper was not found in $backendDir"
    }
    if (-not (Test-Path (Join-Path $repoRoot "docker-compose.yml"))) {
        Fail "docker-compose.yml was not found in $repoRoot"
    }
    New-Item -ItemType Directory -Force -Path $generatedDir, $resultsDir, $logsDir | Out-Null
    Write-Ok "required commands and project files found"

    if (-not $SkipBackendStart) {
        Write-Step "Check local backend port"
        if (-not (Test-PortFree -Port $PublicPort)) {
            Fail "Port $PublicPort is already in use. Use -SkipBackendStart if backend is already running."
        }
        Write-Ok "port $PublicPort is available"
    }

    if (-not $SkipDockerDependencies) {
        Write-Step "Start docker test dependencies"
        Push-Location $repoRoot
        try {
            & docker compose up -d postgres redis
        } finally {
            Pop-Location
        }
        Write-Ok "docker dependencies are running"
    }

    if (-not $SkipBackendStart) {
        Write-Step "Start backend"
        $stdoutLog = Join-Path $logsDir "backend-yandex-tank.out.log"
        $stderrLog = Join-Path $logsDir "backend-yandex-tank.err.log"
        $gradleCommand = if (Test-IsWindowsHost) { "gradlew.bat" } else { "./gradlew" }
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        $startInfo.FileName = if (Test-IsWindowsHost) { Join-Path $backendDir $gradleCommand } else { $gradleCommand }
        $startInfo.Arguments = "bootRun --no-daemon"
        $startInfo.WorkingDirectory = $backendDir
        $startInfo.UseShellExecute = $false
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true
        $startInfo.EnvironmentVariables["SERVER_MTLS_ENABLED"] = "false"
        $startInfo.EnvironmentVariables["SERVER_MTLS_ALLOW_HEADER_FINGERPRINT_FALLBACK"] = "true"
        $startInfo.EnvironmentVariables["OUTBOX_WORKER_ENABLED"] = "true"

        $backendProcess = New-Object System.Diagnostics.Process
        $backendProcess.StartInfo = $startInfo
        [void]$backendProcess.Start()
        $backendProcess.StandardOutput.BaseStream.CopyToAsync([System.IO.File]::Open($stdoutLog, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)) | Out-Null
        $backendProcess.StandardError.BaseStream.CopyToAsync([System.IO.File]::Open($stderrLog, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::ReadWrite)) | Out-Null
        Write-Host "Backend PID: $($backendProcess.Id)"
        Write-Host "Backend stdout: $stdoutLog"
        Write-Host "Backend stderr: $stderrLog"
    } else {
        Write-Step "Use already running backend"
    }

    Wait-BackendHealth -HealthUrl "$publicBaseUrl/actuator/health" -TimeoutSeconds $StartupTimeoutSeconds
    Write-Ok "backend health endpoint is reachable"

    Write-Step "Create and login load-test players"
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
    $playersData = New-Object System.Collections.ArrayList
    for ($i = 1; $i -le $Players; $i++) {
        $loginName = "tank_$timestamp`_$i"
        $password = "password123"
        $registerBody = @{ login_name = $loginName; password = $password }
        $register = Invoke-JsonRequest -Method "Post" -Uri "$publicBaseUrl/auth/register" -Body $registerBody
        $login = Invoke-JsonRequest -Method "Post" -Uri "$publicBaseUrl/auth/login" -Body $registerBody
        [void]$playersData.Add([pscustomobject]@{
            LoginName = $loginName
            Password = $password
            PlayerId = [string]$register.player_id
            AccessToken = [string]$login.access_token
        })
    }
    Write-Ok "created and logged in $($playersData.Count) players"

    $serverId = "10000000-0000-0000-0000-000000000001"
    $serverFingerprint = "dev-ds-fingerprint"
    $serverBuildId = "ds-dev-smoke"
    $tankHost = $TargetHostForTank
    if ([string]::IsNullOrWhiteSpace($tankHost)) {
        if (Test-IsWindowsHost) {
            $tankHost = "host.docker.internal"
        } else {
            $tankHost = "127.0.0.1"
        }
    }
    $targetAddress = "$tankHost`:$PublicPort"
    $hostHeader = $targetAddress

    Write-Step "Generate Phantom request-style ammo"
    $ammoPath = Join-Path $generatedDir "mixed.ammo"
    $ammoBuilder = New-Object System.Text.StringBuilder
    foreach ($copy in 1..$AmmoCopiesPerPlayer) {
        foreach ($player in $playersData) {
            $authHeader = @{ "Authorization" = "Bearer $($player.AccessToken)" }
            [void]$ammoBuilder.Append((New-HttpAmmoBlock -Method "GET" -Path "/catalog/snapshot" -HostHeader $hostHeader -Tag "catalog_snapshot"))
            [void]$ammoBuilder.Append((New-HttpAmmoBlock -Method "GET" -Path "/me/access" -HostHeader $hostHeader -Tag "me_access" -Headers $authHeader))
            [void]$ammoBuilder.Append((New-HttpAmmoBlock -Method "GET" -Path "/me/presets" -HostHeader $hostHeader -Tag "me_presets" -Headers $authHeader))

            if (-not $NoLoginLoad) {
                $loginBody = ConvertTo-CompactJson @{ login_name = $player.LoginName; password = $player.Password }
                [void]$ammoBuilder.Append((New-HttpAmmoBlock -Method "POST" -Path "/auth/login" -HostHeader $hostHeader -Tag "auth_login" -Headers @{ "Content-Type" = "application/json" } -Body $loginBody))
            }

            if (-not $NoServerApi) {
                $profileBody = ConvertTo-CompactJson @{
                    match_id = (New-GuidString)
                    player_id = $player.PlayerId
                    realm_id = "global"
                    class_tag = "class.assault"
                    team_tag = "team.red"
                    weapon_preset_slot = 1
                    outfit_preset_slot = 1
                    supported_catalog_versions = @(1)
                    preferred_catalog_version = 1
                    server_build_id = $serverBuildId
                }
                $serverHeaders = @{
                    "Content-Type" = "application/json"
                    "X-Server-Id" = $serverId
                    "X-Server-Certificate-Fingerprint" = $serverFingerprint
                }
                [void]$ammoBuilder.Append((New-HttpAmmoBlock -Method "POST" -Path "/server/match-profile/build" -HostHeader $hostHeader -Tag "match_profile_build" -Headers $serverHeaders -Body $profileBody))
            }
        }
    }
    Set-Content -Path $ammoPath -Value $ammoBuilder.ToString() -Encoding UTF8
    Write-Ok "ammo generated: $ammoPath"

    Write-Step "Generate Yandex.Tank load.yaml"
    $loadYamlPath = Join-Path $generatedDir "load.yaml"
    $phoutPathHost = Join-Path $resultsDir "phout.log"
    $summaryPath = Join-Path $resultsDir "phout-summary.csv"
    if (Test-Path $phoutPathHost) { Remove-Item $phoutPathHost -Force }
    if (Test-Path $summaryPath) { Remove-Item $summaryPath -Force }
    Write-LoadYaml -Path $loadYamlPath -TargetAddress $targetAddress -Schedule $Schedule -AmmoPath "/var/loadtest/generated/mixed.ammo" -PhoutPath "/var/loadtest/results/phout.log"
    Write-Ok "load.yaml generated: $loadYamlPath"

    Write-Step "Run Yandex.Tank Docker image"
    $dockerArgs = New-Object System.Collections.ArrayList
    [void]$dockerArgs.Add("run")
    [void]$dockerArgs.Add("--rm")
    if (-not (Test-IsWindowsHost)) {
        [void]$dockerArgs.Add("--network")
        [void]$dockerArgs.Add("host")
    }
    [void]$dockerArgs.Add("-v")
    [void]$dockerArgs.Add("${tankDir}:/var/loadtest")
    [void]$dockerArgs.Add("-w")
    [void]$dockerArgs.Add("/var/loadtest")
    [void]$dockerArgs.Add($TankImage)
    [void]$dockerArgs.Add("--no-rc")
    [void]$dockerArgs.Add("-c")
    [void]$dockerArgs.Add("generated/load.yaml")
    [void]$dockerArgs.Add("-l")
    [void]$dockerArgs.Add("results/tank.log")

    Write-Host "docker $($dockerArgs -join ' ')"
    & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) {
        Fail "Yandex.Tank exited with code $LASTEXITCODE. See $resultsDir"
    }
    Write-Ok "Yandex.Tank finished"

    Analyze-Phout -PhoutPath $phoutPathHost -SummaryPath $summaryPath
    Write-Step "Load test result"
    Write-Ok "Yandex.Tank load checks passed. Results: $resultsDir"
} finally {
    if ($backendProcess -ne $null -and -not $backendProcess.HasExited -and -not $KeepBackendRunning) {
        Write-Step "Stop backend"
        try {
            $backendProcess.Kill()
            $backendProcess.WaitForExit(10000) | Out-Null
        } catch {
            Write-Host "WARN: failed to stop backend process: $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
}
