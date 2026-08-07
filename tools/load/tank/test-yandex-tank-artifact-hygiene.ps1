param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

$scriptPath = Join-Path $RepoRoot "tools\load\tank\run-yandex-tank.ps1"
$content = Get-Content -LiteralPath $scriptPath -Raw

foreach ($requiredFragment in @(
    'artifacts\load\tank\',
    'Remove-Item -LiteralPath $sensitivePath -Force',
    '${artifactRunDir}:/var/loadtest'
)) {
    if (-not $content.Contains($requiredFragment)) {
        throw "Yandex.Tank artifact hygiene contract is missing: $requiredFragment"
    }
}

foreach ($unsafeFragment in @(
    'Join-Path $tankDir "generated"',
    'Join-Path $tankDir "results"'
)) {
    if ($content.Contains($unsafeFragment)) {
        throw "Yandex.Tank must not write sensitive artifacts under tools/load/tank: $unsafeFragment"
    }
}

Write-Host "Yandex.Tank artifact hygiene checks passed." -ForegroundColor Green
