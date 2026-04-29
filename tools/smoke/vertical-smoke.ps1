param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$loginName = "player_$suffix"
$password = "password123"
$registerBody = @{
    login_name = $loginName
    password = $password
} | ConvertTo-Json

$registered = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/register" `
    -Body $registerBody `
    -ContentType "application/json"

$playerId = $registered.player_id
$loginBody = @{
    login_name = $loginName
    password = $password
} | ConvertTo-Json

$tokens = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/login" `
    -Body $loginBody `
    -ContentType "application/json"

if ($tokens.player_id -ne $playerId -or [string]::IsNullOrWhiteSpace($tokens.access_token)) {
    throw "Expected login to return access token for registered player"
}

$authHeaders = @{ "Authorization" = "Bearer $($tokens.access_token)" }
$catalog = Invoke-RestMethod -Uri "$BaseUrl/catalog/snapshot?realm_id=global"
$access = Invoke-RestMethod -Uri "$BaseUrl/me/access" -Headers $authHeaders
$presets = Invoke-RestMethod -Uri "$BaseUrl/me/presets" -Headers $authHeaders

$weaponPreset = $presets.weapon_presets | Where-Object { $_.class_tag -eq "class.assault" -and $_.preset_slot -eq 1 } | Select-Object -First 1
$saveBody = @{
    catalog_version = $weaponPreset.catalog_version
    slots = @(
        @{
            weapon_slot_id = "primary"
            weapon_id = "weapon.ak12"
            modules = @(
                @{
                    mount_id = "weapon.ak12.mount.scope.01"
                    module_id = "module.scope.red_dot_01"
                }
            )
        },
        @{
            weapon_slot_id = "grenade"
            weapon_id = $null
            modules = @()
        }
    )
} | ConvertTo-Json -Depth 8

$savedPreset = Invoke-RestMethod `
    -Method Put `
    -Uri "$BaseUrl/me/presets/weapons/class.assault/1" `
    -Headers @{
        "Authorization" = "Bearer $($tokens.access_token)"
        "If-Match" = "`"$($weaponPreset.revision)`""
    } `
    -Body $saveBody `
    -ContentType "application/json"

if ($savedPreset.revision -ne ($weaponPreset.revision + 1)) {
    throw "Expected weapon preset revision to increment"
}

$staleRevisionStatus = $null
try {
    Invoke-RestMethod `
        -Method Put `
        -Uri "$BaseUrl/me/presets/weapons/class.assault/1" `
        -Headers @{
            "Authorization" = "Bearer $($tokens.access_token)"
            "If-Match" = "`"$($weaponPreset.revision)`""
        } `
        -Body $saveBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $staleRevisionStatus = [int]$_.Exception.Response.StatusCode
    }
}

if ($staleRevisionStatus -ne 412) {
    throw "Expected stale If-Match to return 412, got $staleRevisionStatus"
}

$matchId = [Guid]::NewGuid().ToString()
$runtimeOperationId = [Guid]::NewGuid().ToString()
$runtimeBody = @{
    operation_id = $runtimeOperationId
    operation_seq = 1
    match_id = $matchId
    player_id = $playerId
    class_tag = "class.assault"
    weapon_preset_slot = 1
    base_weapon_preset_revision = $savedPreset.revision
    runtime_change_payload = @{
        schema_version = 1
        changes = @(
            @{
                op = "set_module"
                weapon_slot_id = "primary"
                weapon_id = "weapon.ak12"
                mount_id = "weapon.ak12.mount.scope.01"
                module_id = "module.scope.red_dot_01"
            }
        )
    }
} | ConvertTo-Json -Depth 10

$runtimeApplied = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/server/runtime-preset-changes" `
    -Headers @{ "Idempotency-Key" = $runtimeOperationId } `
    -Body $runtimeBody `
    -ContentType "application/json"

if ($runtimeApplied.status -ne "applied" -or $runtimeApplied.result_revision -ne ($savedPreset.revision + 1)) {
    throw "Expected runtime change to apply and increment revision"
}

$runtimeConflictStatus = $null
$runtimeConflictOperationId = [Guid]::NewGuid().ToString()
$runtimeConflictBody = @{
    operation_id = $runtimeConflictOperationId
    operation_seq = 2
    match_id = $matchId
    player_id = $playerId
    class_tag = "class.assault"
    weapon_preset_slot = 1
    base_weapon_preset_revision = $savedPreset.revision
    runtime_change_payload = @{
        schema_version = 1
        changes = @(
            @{
                op = "clear_module"
                weapon_slot_id = "primary"
                weapon_id = "weapon.ak12"
                mount_id = "weapon.ak12.mount.scope.01"
            }
        )
    }
} | ConvertTo-Json -Depth 10

try {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/server/runtime-preset-changes" `
        -Headers @{ "Idempotency-Key" = $runtimeConflictOperationId } `
        -Body $runtimeConflictBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $runtimeConflictStatus = [int]$_.Exception.Response.StatusCode
    }
}

if ($runtimeConflictStatus -ne 409) {
    throw "Expected runtime revision conflict to return 409, got $runtimeConflictStatus"
}

$refreshBody = @{
    refresh_token = $tokens.refresh_token
} | ConvertTo-Json

$rotatedTokens = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/refresh" `
    -Body $refreshBody `
    -ContentType "application/json"

if ($rotatedTokens.player_id -ne $playerId -or $rotatedTokens.refresh_token -eq $tokens.refresh_token) {
    throw "Expected refresh to rotate refresh token"
}

$rotatedAccess = Invoke-RestMethod `
    -Uri "$BaseUrl/me/access" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

if ($rotatedAccess.player_id -ne $playerId) {
    throw "Expected refreshed access token to authenticate player"
}

$matchBody = @{
    match_id = $matchId
    player_id = $playerId
    realm_id = "global"
    class_tag = "class.assault"
    team_tag = "team.red"
    weapon_preset_slot = 1
    outfit_preset_slot = 1
    supported_catalog_versions = @(1)
    preferred_catalog_version = 1
    server_build_id = "ds-dev-smoke"
} | ConvertTo-Json -Depth 8

$profile = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/server/match-profile/build" `
    -Body $matchBody `
    -ContentType "application/json"

$primary = $profile.weapons | Where-Object { $_.weapon_slot_id -eq "primary" } | Select-Object -First 1
if ($null -eq $primary -or $primary.weapon_id -ne "weapon.ak12") {
    throw "Expected primary weapon.ak12 in match profile"
}

[PSCustomObject]@{
    status = "VERTICAL_SMOKE_OK"
    player_id = $playerId
    catalog_version = $catalog.catalog_version
    catalog_items = $catalog.items.Count
    access_items = $access.items.Count
    weapon_presets = $presets.weapon_presets.Count
    saved_revision = $savedPreset.revision
    stale_revision_status = $staleRevisionStatus
    runtime_applied_revision = $runtimeApplied.result_revision
    runtime_conflict_status = $runtimeConflictStatus
    refresh_rotated = ($rotatedTokens.refresh_token -ne $tokens.refresh_token)
    outfit_presets = $presets.outfit_presets.Count
    primary_weapon = $primary.weapon_id
    primary_module = $primary.modules[0].module_id
    outfit_item = $profile.outfit[0].item_id
}

$logoutBody = @{
    refresh_token = $rotatedTokens.refresh_token
} | ConvertTo-Json

Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/logout" `
    -Body $logoutBody `
    -ContentType "application/json" | Out-Null
