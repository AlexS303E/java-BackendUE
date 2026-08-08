param(
    [string]$RepoRoot = ""
)

$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($RepoRoot)) {
    $RepoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
} else {
    $RepoRoot = (Resolve-Path -LiteralPath $RepoRoot).Path
}

$scanner = Join-Path $RepoRoot "tools\security\scan-secrets.ps1"
$powerShellExecutable = if ($null -ne (Get-Command pwsh -ErrorAction SilentlyContinue)) { "pwsh" } else { "powershell" }
$tempDir = Join-Path ([IO.Path]::GetTempPath()) ("secret-scan-" + [Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

try {
    $safeFile = Join-Path $tempDir "safe.yml"
    Set-Content -LiteralPath $safeFile -Value "app:`n  name: backend" -Encoding UTF8
    & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $scanner -RepoRoot $RepoRoot -Path $safeFile
    if ($LASTEXITCODE -ne 0) {
        throw "Secret scanner rejected a safe fixture"
    }

    $secretFile = Join-Path $tempDir "secret.yml"
    $fixtureToken = "ghp_" + "abcdefghijklmnopqrstuvwxyz1234567890"
    Set-Content -LiteralPath $secretFile -Value "token: $fixtureToken" -Encoding UTF8
    $output = & $powerShellExecutable -NoProfile -ExecutionPolicy Bypass -File $scanner -RepoRoot $RepoRoot -Path $secretFile 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw "Secret scanner accepted a GitHub token fixture"
    }
    if (($output | Out-String) -notmatch "github-token") {
        throw "Secret scanner did not report the expected rule: $($output | Out-String)"
    }
} finally {
    Remove-Item -LiteralPath $tempDir -Recurse -Force
}

Write-Host "Secret scanner negative tests passed." -ForegroundColor Green
