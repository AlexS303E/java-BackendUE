param(
    [string]$RepoRoot = "",
    [string[]]$Path = @()
)

$ErrorActionPreference = "Stop"

function Resolve-RepositoryRoot {
    param([string]$ProvidedRepoRoot)

    if (-not [string]::IsNullOrWhiteSpace($ProvidedRepoRoot)) {
        return (Resolve-Path -LiteralPath $ProvidedRepoRoot).Path
    }

    return (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
}

function Get-ScanTargets {
    param([string]$Root, [string[]]$ExplicitPaths)

    if ($ExplicitPaths.Count -gt 0) {
        return @($ExplicitPaths | ForEach-Object { (Resolve-Path -LiteralPath $_).Path })
    }

    $trackedFiles = & git -C $Root ls-files
    if ($LASTEXITCODE -ne 0) {
        throw "Unable to list tracked files for secret scanning"
    }

    $allowedExtensions = @(".java", ".kt", ".kts", ".properties", ".yml", ".yaml", ".ps1", ".sh", ".json", ".xml")
    return @($trackedFiles |
        Where-Object {
            $_ -notmatch "(^|/)(build|artifacts|backups|\.gradle)/" -and
            $_ -notmatch "^backend/src/test/" -and
            $allowedExtensions -contains [IO.Path]::GetExtension($_).ToLowerInvariant()
        } |
        ForEach-Object { Join-Path $Root $_ })
}

$root = Resolve-RepositoryRoot -ProvidedRepoRoot $RepoRoot
$rules = @(
    [pscustomobject]@{
        Name = "private-key"
        Pattern = "-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----\s*(?:[A-Za-z0-9+/=]{32,}\s*){2,}"
    },
    [pscustomobject]@{
        Name = "aws-access-key"
        Pattern = "\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"
    },
    [pscustomobject]@{
        Name = "github-token"
        Pattern = "\b(?:gh[pousr]_[A-Za-z0-9_]{20,}|github_pat_[A-Za-z0-9_]{20,})\b"
    },
    [pscustomobject]@{
        Name = "slack-token"
        Pattern = "\bxox(?:b|p|a|r|s)-[A-Za-z0-9-]{20,}\b"
    }
)

$findings = New-Object 'System.Collections.Generic.List[string]'
foreach ($target in Get-ScanTargets -Root $root -ExplicitPaths $Path) {
    if (-not (Test-Path -LiteralPath $target -PathType Leaf)) {
        continue
    }

    $content = Get-Content -LiteralPath $target -Raw
    foreach ($rule in $rules) {
        foreach ($match in [regex]::Matches($content, $rule.Pattern, [Text.RegularExpressions.RegexOptions]::IgnoreCase)) {
            $line = 1 + ([regex]::Matches($content.Substring(0, $match.Index), "\n")).Count
            $relativePath = if ($target.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)) {
                $target.Substring($root.Length).TrimStart('\', '/')
            } else {
                $target
            }
            $findings.Add("$relativePath`:$line [$($rule.Name)]")
        }
    }
}

if ($findings.Count -gt 0) {
    Write-Host "Potential committed secret material found:`n$($findings -join "`n")" -ForegroundColor Red
    exit 1
}

Write-Host "Secret scan passed: no credential signatures found." -ForegroundColor Green
