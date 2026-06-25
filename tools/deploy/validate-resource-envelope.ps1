param(
    [string]$EnvelopePath = "",
    [double]$CpuLimit = 0,
    [int]$MemoryLimitMiB = 0,
    [string]$JavaToolOptions = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($EnvelopePath)) {
    $EnvelopePath = Join-Path $PSScriptRoot "..\..\config\production-resource-envelope.env"
}
$resolvedEnvelope = (Resolve-Path -LiteralPath $EnvelopePath).Path

$envelope = @{}
foreach ($line in Get-Content -LiteralPath $resolvedEnvelope) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith("#")) {
        continue
    }
    $parts = $line.Split("=", 2)
    if ($parts.Count -ne 2) {
        throw "Invalid resource envelope line: $line"
    }
    $envelope[$parts[0].Trim()] = $parts[1].Trim()
}

$requiredKeys = @(
    "APP_CPU_REQUEST",
    "APP_CPU_LIMIT",
    "APP_MEMORY_REQUEST_MIB",
    "APP_MEMORY_LIMIT_MIB",
    "JAVA_TOOL_OPTIONS"
)
foreach ($key in $requiredKeys) {
    if (-not $envelope.ContainsKey($key)) {
        throw "Resource envelope is missing $key"
    }
}

$minimumCpu = [double]$envelope["APP_CPU_REQUEST"]
$minimumMemoryMiB = [int]$envelope["APP_MEMORY_LIMIT_MIB"]
if ($CpuLimit -le 0) {
    $CpuLimit = [double]$envelope["APP_CPU_LIMIT"]
}
if ($MemoryLimitMiB -le 0) {
    $MemoryLimitMiB = $minimumMemoryMiB
}
if ([string]::IsNullOrWhiteSpace($JavaToolOptions)) {
    $JavaToolOptions = $envelope["JAVA_TOOL_OPTIONS"]
}

if ($CpuLimit -lt $minimumCpu) {
    throw "CPU limit $CpuLimit is below the Stage 1 minimum of $minimumCpu vCPU"
}
if ($MemoryLimitMiB -lt $minimumMemoryMiB) {
    throw "Memory limit $MemoryLimitMiB MiB is below the Stage 1 minimum of $minimumMemoryMiB MiB"
}

$requiredJvmOptions = @(
    "-XX:InitialRAMPercentage=25",
    "-XX:MaxRAMPercentage=60",
    "-XX:+UseG1GC",
    "-XX:+ExitOnOutOfMemoryError"
)
foreach ($option in $requiredJvmOptions) {
    if (-not $JavaToolOptions.Contains($option)) {
        throw "JAVA_TOOL_OPTIONS is missing required option $option"
    }
}

[PSCustomObject]@{
    status = "RESOURCE_ENVELOPE_OK"
    cpu_limit = $CpuLimit
    memory_limit_mib = $MemoryLimitMiB
    java_tool_options = $JavaToolOptions
    envelope = $resolvedEnvelope
}
