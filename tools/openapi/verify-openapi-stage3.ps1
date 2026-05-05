param(
    [string]$RepoRoot = "."
)

$ErrorActionPreference = "Stop"

$failures = New-Object System.Collections.Generic.List[string]

function Add-Failure {
    param([string]$Message)
    $script:failures.Add($Message) | Out-Null
}

function Read-Contract {
    param([string]$RelativePath)

    $path = Join-Path $RepoRoot $RelativePath
    if (!(Test-Path $path)) {
        Add-Failure "Missing file: $RelativePath"
        return ""
    }

    return Get-Content $path -Raw
}

function Assert-Contains {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Needle
    )

    if ($Text -notlike "*$Needle*") {
        Add-Failure "$Name does not contain required text: $Needle"
    }
}

function Assert-Path {
    param(
        [string]$Name,
        [string]$Text,
        [string]$Path
    )

    $escaped = [regex]::Escape($Path)
    $pattern = "(?m)^\s*$escaped\s*:"
    if ($Text -notmatch $pattern) {
        Add-Failure "$Name is missing OpenAPI path: $Path"
    }
}

function Assert-NoCamelCasePathOrQueryParameter {
    param(
        [string]$Name,
        [string]$Text
    )

    # Проверяем именно API path/query/header parameter names, а не весь YAML.
    # Schema property names, operationId, descriptions и DTO names здесь не проверяем.
    $forbidden = @(
        "classTag",
        "presetSlot",
        "playerId",
        "itemId",
        "serverId",
        "notificationId",
        "changeId",
        "catalogVersion",
        "realmId",
        "weaponId"
    )

    foreach ($token in $forbidden) {
        $pathTokenPattern = "\{$token\}"
        if ($Text -match $pathTokenPattern) {
            Add-Failure "$Name contains camelCase path parameter: {$token}"
        }

        $parameterNamePattern = "(?m)^\s*name:\s*$token\s*$"
        if ($Text -match $parameterNamePattern) {
            Add-Failure "$Name contains camelCase OpenAPI parameter name: $token"
        }
    }
}

$public = Read-Contract "contracts/openapi/public-api.yaml"
$server = Read-Contract "contracts/openapi/server-api.yaml"
$admin = Read-Contract "contracts/openapi/admin-api.yaml"

$contracts = @{
    "public-api.yaml" = $public
    "server-api.yaml" = $server
    "admin-api.yaml" = $admin
}

foreach ($entry in $contracts.GetEnumerator()) {
    if ($entry.Value.Length -gt 0) {
        Assert-Contains $entry.Key $entry.Value "openapi: 3.1.0"
        Assert-Contains $entry.Key $entry.Value "ProblemDetails"
        Assert-NoCamelCasePathOrQueryParameter $entry.Key $entry.Value
    }
}

$publicPaths = @(
    "/auth/register",
    "/auth/login",
    "/auth/refresh",
    "/auth/logout",
    "/catalog/snapshot",
    "/me/access",
    "/me/presets",
    "/me/presets/weapons/{class_tag}/{preset_slot}",
    "/me/notifications",
    "/me/notifications/{notification_id}/read",
    "/me/post-match-pending-changes",
    "/me/post-match-pending-changes/{change_id}/resolve"
)

foreach ($path in $publicPaths) {
    Assert-Path "public-api.yaml" $public $path
}

Assert-Contains "public-api.yaml" $public "BearerAuth"
Assert-Contains "public-api.yaml" $public "If-Match"
Assert-Contains "public-api.yaml" $public "ETag"
Assert-Contains "public-api.yaml" $public "PRECONDITION_REQUIRED"
Assert-Contains "public-api.yaml" $public "PRECONDITION_FAILED"
Assert-Contains "public-api.yaml" $public "LOADOUT_VALIDATION_FAILED"

$serverPaths = @(
    "/server/match-profile/build",
    "/server/runtime-preset-changes",
    "/server/runtime-events"
)

foreach ($path in $serverPaths) {
    Assert-Path "server-api.yaml" $server $path
}

Assert-Contains "server-api.yaml" $server "X-Server-Id"
Assert-Contains "server-api.yaml" $server "X-Server-Certificate-Fingerprint"
Assert-Contains "server-api.yaml" $server "Idempotency-Key"
Assert-Contains "server-api.yaml" $server "IDEMPOTENCY_OPERATION_ID_MISMATCH"
Assert-Contains "server-api.yaml" $server "PRESET_REVISION_CONFLICT"
Assert-Contains "server-api.yaml" $server "CATALOG_VERSION_NOT_SUPPORTED"

$adminPaths = @(
    "/admin/status/overview",
    "/admin/status/servers",
    "/admin/status/matches",
    "/admin/status/recent-audit",
    "/admin/status/players/search",
    "/admin/status/players/{player_id}/weapon-access",
    "/admin/status/players/{player_id}/weapon-access/audit",
    "/admin/players/{player_id}/access/items/{item_id}",
    "/admin/catalog/publish",
    "/admin/catalog/rollback",
    "/admin/control/players/{player_id}/invalidate-cache",
    "/admin/control/server-identities/{server_id}/revoke",
    "/admin/control/outbox/retry-failed",
    "/admin/control/players/{player_id}/weapon-access"
)

foreach ($path in $adminPaths) {
    Assert-Path "admin-api.yaml" $admin $path
}

Assert-Contains "admin-api.yaml" $admin "X-Admin-Token"
Assert-Contains "admin-api.yaml" $admin "X-Admin-Id"
Assert-Contains "admin-api.yaml" $admin "Idempotency-Key"
Assert-Contains "admin-api.yaml" $admin "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"
Assert-Contains "admin-api.yaml" $admin "CATALOG_VERSION_NOT_PUBLISHABLE"

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "OpenAPI stage 3 verification failed:" -ForegroundColor Red
    foreach ($failure in $failures) {
        Write-Host " - $failure" -ForegroundColor Red
    }
    Write-Host ""
    exit 1
}

Write-Host "OpenAPI stage 3 verification passed." -ForegroundColor Green
exit 0
