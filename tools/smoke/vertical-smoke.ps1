param(
    [string]$BaseUrl = "http://localhost:8080"
)

$ErrorActionPreference = "Stop"

$suffix = [Guid]::NewGuid().ToString("N").Substring(0, 8)
$registerBody = @{
    login_name = "player_$suffix"
    password = "password123"
} | ConvertTo-Json

$registered = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/auth/register" `
    -Body $registerBody `
    -ContentType "application/json"

$playerId = $registered.player_id
$catalog = Invoke-RestMethod -Uri "$BaseUrl/catalog/snapshot?realm_id=global"
$access = Invoke-RestMethod -Uri "$BaseUrl/me/access" -Headers @{ "X-Player-Id" = $playerId }
$presets = Invoke-RestMethod -Uri "$BaseUrl/me/presets" -Headers @{ "X-Player-Id" = $playerId }

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
        "X-Player-Id" = $playerId
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
            "X-Player-Id" = $playerId
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

$matchBody = @{
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
    outfit_presets = $presets.outfit_presets.Count
    primary_weapon = $primary.weapon_id
    primary_module = $primary.modules[0].module_id
    outfit_item = $profile.outfit[0].item_id
}
