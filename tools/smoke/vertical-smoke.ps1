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
    outfit_presets = $presets.outfit_presets.Count
    primary_weapon = $primary.weapon_id
    primary_module = $primary.modules[0].module_id
    outfit_item = $profile.outfit[0].item_id
}
