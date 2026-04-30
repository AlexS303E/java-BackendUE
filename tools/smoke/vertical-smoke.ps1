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
$serverId = "10000000-0000-0000-0000-000000000001"
$serverFingerprint = "dev-ds-fingerprint"
$serverHeaders = @{
    "X-Server-Id" = $serverId
    "X-Server-Certificate-Fingerprint" = $serverFingerprint
}
$adminHeaders = @{
    "X-Admin-Token" = "dev-admin-token"
    "X-Admin-Id" = "smoke-admin"
}
$catalog = Invoke-RestMethod -Uri "$BaseUrl/catalog/snapshot?realm_id=global"
$access = Invoke-RestMethod -Uri "$BaseUrl/me/access" -Headers $authHeaders
$presets = Invoke-RestMethod -Uri "$BaseUrl/me/presets" -Headers $authHeaders

$adminOverview = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/overview" `
    -Headers $adminHeaders

if ($adminOverview.backend.ok -ne $true -or $adminOverview.infrastructure.databaseOk -ne $true) {
    throw "Expected admin overview to report backend/database OK"
}

$adminPlayerSearch = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/players/search?query=$loginName" `
    -Headers $adminHeaders

$adminFoundPlayer = $adminPlayerSearch.players |
    Where-Object { $_.playerId -eq $playerId } |
    Select-Object -First 1

if ($null -eq $adminFoundPlayer -or $adminFoundPlayer.accessRevision -lt 1) {
    throw "Expected admin player search to find registered player"
}

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

$serverUnauthenticatedStatus = $null
try {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/server/match-profile/build" `
        -Body $matchBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $serverUnauthenticatedStatus = [int]$_.Exception.Response.StatusCode
    }
}

if ($serverUnauthenticatedStatus -ne 401) {
    throw "Expected /server call without server identity to return 401, got $serverUnauthenticatedStatus"
}

$profile = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/server/match-profile/build" `
    -Headers $serverHeaders `
    -Body $matchBody `
    -ContentType "application/json"

$primary = $profile.weapons | Where-Object { $_.weapon_slot_id -eq "primary" } | Select-Object -First 1
if ($null -eq $primary -or $primary.weapon_id -ne "weapon.ak12") {
    throw "Expected primary weapon.ak12 in match profile"
}

$adminServers = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/servers" `
    -Headers $adminHeaders

if (($adminServers.servers | Where-Object { $_.serverId -eq $serverId } | Select-Object -First 1) -eq $null) {
    throw "Expected admin servers status to include dev server identity"
}

$adminMatches = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/matches" `
    -Headers $adminHeaders

if (($adminMatches.matches | Where-Object { $_.matchId -eq $matchId } | Select-Object -First 1) -eq $null) {
    throw "Expected admin matches status to include built match"
}

$runtimeEventId = [Guid]::NewGuid().ToString()
$runtimeEventBody = @{
    event_id = $runtimeEventId
    event_seq = 1
    match_id = $matchId
    event_type = "loadout_applied"
    player_id = $playerId
    payload_schema_version = 1
    occurred_at = (Get-Date).ToUniversalTime().ToString("o")
    payload = @{
        class_tag = "class.assault"
        weapon_preset_slot = 1
        profile_revision = $profile.dependency_revisions.profile_revision
    }
} | ConvertTo-Json -Depth 8

$runtimeEvent = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/server/runtime-events" `
    -Headers $serverHeaders `
    -Body $runtimeEventBody `
    -ContentType "application/json"

if ($runtimeEvent.status -ne "recorded" -or $runtimeEvent.event_id -ne $runtimeEventId) {
    throw "Expected runtime event to be recorded"
}

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
    -Headers @{
        "X-Server-Id" = $serverId
        "X-Server-Certificate-Fingerprint" = $serverFingerprint
        "Idempotency-Key" = $runtimeOperationId
    } `
    -Body $runtimeBody `
    -ContentType "application/json"

if ($runtimeApplied.status -ne "applied" -or $runtimeApplied.result_revision -ne ($savedPreset.revision + 1)) {
    throw "Expected runtime change to apply and increment revision"
}

$serverForbiddenStatus = $null
$limitedRuntimeOperationId = [Guid]::NewGuid().ToString()
$limitedRuntimeBody = @{
    operation_id = $limitedRuntimeOperationId
    operation_seq = 99
    match_id = $matchId
    player_id = $playerId
    class_tag = "class.assault"
    weapon_preset_slot = 1
    base_weapon_preset_revision = $runtimeApplied.result_revision
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

try {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/server/runtime-preset-changes" `
        -Headers @{
            "X-Server-Id" = "10000000-0000-0000-0000-000000000002"
            "X-Server-Certificate-Fingerprint" = "dev-ds-limited-fingerprint"
            "Idempotency-Key" = $limitedRuntimeOperationId
        } `
        -Body $limitedRuntimeBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $serverForbiddenStatus = [int]$_.Exception.Response.StatusCode
    }
}

if ($serverForbiddenStatus -ne 403) {
    throw "Expected limited server identity to return 403 for runtime changes, got $serverForbiddenStatus"
}

$runtimeConflictStatus = $null
$runtimeConflictPendingChangeId = $null
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
        -Headers @{
            "X-Server-Id" = $serverId
            "X-Server-Certificate-Fingerprint" = $serverFingerprint
            "Idempotency-Key" = $runtimeConflictOperationId
        } `
        -Body $runtimeConflictBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $runtimeConflictStatus = [int]$_.Exception.Response.StatusCode
    }
    $problemBody = $_.ErrorDetails.Message
    if ([string]::IsNullOrWhiteSpace($problemBody) -and $null -ne $_.Exception.Response) {
        $stream = $_.Exception.Response.GetResponseStream()
        if ($null -ne $stream) {
            $reader = [System.IO.StreamReader]::new($stream)
            $problemBody = $reader.ReadToEnd()
        }
    }
    if (-not [string]::IsNullOrWhiteSpace($problemBody)) {
        $problem = $problemBody | ConvertFrom-Json
        $runtimeConflictPendingChangeId = $problem.pending_change_id
    }
}

if ($runtimeConflictStatus -ne 409) {
    throw "Expected runtime revision conflict to return 409, got $runtimeConflictStatus"
}

if ([string]::IsNullOrWhiteSpace($runtimeConflictPendingChangeId)) {
    throw "Expected runtime revision conflict to return pending_change_id"
}

$pendingChanges = Invoke-RestMethod `
    -Uri "$BaseUrl/me/post-match-pending-changes" `
    -Headers $authHeaders

$pendingChange = $pendingChanges.changes |
    Where-Object { $_.change_id -eq $runtimeConflictPendingChangeId } |
    Select-Object -First 1

if ($null -eq $pendingChange -or $pendingChange.status -ne "pending") {
    throw "Expected pending change to be visible to player"
}

$notificationsAfterConflict = Invoke-RestMethod `
    -Uri "$BaseUrl/me/notifications?status=unread&limit=20" `
    -Headers $authHeaders

$pendingCreatedNotification = $notificationsAfterConflict.notifications |
    Where-Object { $_.event_type -eq "post_match_pending_change.created" -and $_.aggregate_id -eq $runtimeConflictPendingChangeId } |
    Select-Object -First 1

if ($null -eq $pendingCreatedNotification) {
    throw "Expected pending change creation notification to be visible to player"
}

$resolveBody = @{
    resolution = "apply_if_still_valid"
} | ConvertTo-Json

$resolvedPendingChange = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/me/post-match-pending-changes/$runtimeConflictPendingChangeId/resolve" `
    -Headers $authHeaders `
    -Body $resolveBody `
    -ContentType "application/json"

if ($resolvedPendingChange.status -ne "applied" -or $resolvedPendingChange.result_revision -ne ($runtimeApplied.result_revision + 1)) {
    throw "Expected post-match pending change to apply and increment revision"
}

$notificationsAfterResolve = Invoke-RestMethod `
    -Uri "$BaseUrl/me/notifications?status=unread&limit=20" `
    -Headers $authHeaders

$pendingResolvedNotification = $notificationsAfterResolve.notifications |
    Where-Object { $_.event_type -eq "post_match_pending_change.resolved" -and $_.aggregate_id -eq $runtimeConflictPendingChangeId } |
    Select-Object -First 1

if ($null -eq $pendingResolvedNotification -or $pendingResolvedNotification.payload.status -ne "applied") {
    throw "Expected pending change resolution notification to be visible to player"
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

$adminAccessIdempotencyKey = [Guid]::NewGuid().ToString()
$adminAccessBody = @{
    catalog_version = $catalog.catalog_version
    hidden = $false
    locked_in_shop = $false
    locked_by_quest = $false
    disabled = $true
    disabled_reason = "smoke_admin_disabled_weapon"
    unlock_hint_code = "admin_disabled_weapon"
    unlock_hint_payload = @{
        source = "vertical_smoke"
        ticket = $adminAccessIdempotencyKey
    }
    reason = "vertical_smoke_disable_weapon"
} | ConvertTo-Json -Depth 8

$adminUnauthenticatedStatus = $null
try {
    Invoke-RestMethod `
        -Method Post `
        -Uri "$BaseUrl/admin/players/$playerId/access/items/weapon.ak12" `
        -Headers @{ "Idempotency-Key" = $adminAccessIdempotencyKey } `
        -Body $adminAccessBody `
        -ContentType "application/json" | Out-Null
} catch {
    if ($null -ne $_.Exception.Response) {
        $adminUnauthenticatedStatus = [int]$_.Exception.Response.StatusCode
    }
}

if ($adminUnauthenticatedStatus -ne 401) {
    throw "Expected /admin call without admin token to return 401, got $adminUnauthenticatedStatus"
}

$adminAccess = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/admin/players/$playerId/access/items/weapon.ak12" `
    -Headers ($adminHeaders + @{ "Idempotency-Key" = $adminAccessIdempotencyKey }) `
    -Body $adminAccessBody `
    -ContentType "application/json"

if ($adminAccess.disabled -ne $true -or $adminAccess.player_can_use -ne $false) {
    throw "Expected admin access update to disable weapon.ak12"
}

if ($adminAccess.sanitized_weapon_presets -ne 1 -or $adminAccess.sanitized_outfit_presets -ne 0) {
    throw "Expected admin access update to sanitize one weapon preset"
}

if ($adminAccess.stale_match_profiles -ne 1) {
    throw "Expected admin access update to mark one match profile stale"
}

if ($adminAccess.access_revision -le $rotatedAccess.access_revision) {
    throw "Expected admin access update to increment access revision"
}

$adminAccessReplay = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/admin/players/$playerId/access/items/weapon.ak12" `
    -Headers ($adminHeaders + @{ "Idempotency-Key" = $adminAccessIdempotencyKey }) `
    -Body $adminAccessBody `
    -ContentType "application/json"

if ($adminAccessReplay.duplicate -ne $true -or $adminAccessReplay.access_revision -ne $adminAccess.access_revision) {
    throw "Expected admin access update replay to be idempotent"
}

if ($adminAccessReplay.sanitized_weapon_presets -ne $adminAccess.sanitized_weapon_presets) {
    throw "Expected admin access replay to return original sanitization result"
}

if ($adminAccessReplay.stale_match_profiles -ne $adminAccess.stale_match_profiles) {
    throw "Expected admin access replay to return original stale profile count"
}

$accessAfterAdmin = Invoke-RestMethod `
    -Uri "$BaseUrl/me/access" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

$adminWeaponAccessStatus = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/players/$playerId/weapon-access?weaponId=weapon.ak12&catalogVersion=$($catalog.catalog_version)" `
    -Headers $adminHeaders

if ($adminWeaponAccessStatus.isDisabled -ne $true -or $adminWeaponAccessStatus.effectiveCanUse -ne $false) {
    throw "Expected admin weapon-access status to show disabled weapon"
}

$adminWeaponAccessAudit = Invoke-RestMethod `
    -Uri "$BaseUrl/admin/status/players/$playerId/weapon-access/audit?weaponId=weapon.ak12&catalogVersion=$($catalog.catalog_version)" `
    -Headers $adminHeaders

if (($adminWeaponAccessAudit.events | Where-Object { $_.eventType -eq "admin_override" } | Select-Object -First 1) -eq $null) {
    throw "Expected admin weapon-access audit to include admin_override"
}

$disabledModule = $accessAfterAdmin.items |
    Where-Object { $_.item_id -eq "weapon.ak12" } |
    Select-Object -First 1

if ($null -eq $disabledModule -or $disabledModule.disabled -ne $true -or $disabledModule.player_can_use -ne $false) {
    throw "Expected disabled weapon to be visible in /me/access"
}

$presetsAfterAdmin = Invoke-RestMethod `
    -Uri "$BaseUrl/me/presets" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

$weaponPresetAfterAdmin = $presetsAfterAdmin.weapon_presets |
    Where-Object { $_.class_tag -eq "class.assault" -and $_.preset_slot -eq 1 } |
    Select-Object -First 1

if ($null -eq $weaponPresetAfterAdmin -or $weaponPresetAfterAdmin.sanitized -ne $true) {
    throw "Expected weapon preset to be marked sanitized after admin disabled module"
}

if ($weaponPresetAfterAdmin.revision -ne ($resolvedPendingChange.result_revision + 1)) {
    throw "Expected sanitized weapon preset revision to increment"
}

$primaryAfterAdmin = $weaponPresetAfterAdmin.slots |
    Where-Object { $_.weapon_slot_id -eq "primary" } |
    Select-Object -First 1

if ($null -ne $primaryAfterAdmin.selected_weapon_id -or $primaryAfterAdmin.modules.Count -ne 0) {
    throw "Expected disabled weapon to be removed from sanitized weapon preset"
}

$notificationsAfterAdmin = Invoke-RestMethod `
    -Uri "$BaseUrl/me/notifications?status=unread&limit=50" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

$accessChangedNotification = $notificationsAfterAdmin.notifications |
    Where-Object { $_.event_type -eq "player_access.changed" -and $_.payload.item_id -eq "weapon.ak12" } |
    Select-Object -First 1

if ($null -eq $accessChangedNotification -or $accessChangedNotification.payload.player_can_use -ne $false) {
    throw "Expected player access change notification after weapon disable"
}

$sanitizedNotification = $notificationsAfterAdmin.notifications |
    Where-Object { $_.event_type -eq "weapon_preset.sanitized" -and $_.payload.removed_item_id -eq "weapon.ak12" } |
    Select-Object -First 1

if ($null -eq $sanitizedNotification -or $sanitizedNotification.payload.revision -ne $weaponPresetAfterAdmin.revision) {
    throw "Expected weapon preset sanitized notification after weapon disable"
}

$notificationRead = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/me/notifications/$($pendingCreatedNotification.notification_id)/read" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

if ($notificationRead.status -ne "read") {
    throw "Expected notification read endpoint to mark notification as read"
}

$readNotifications = Invoke-RestMethod `
    -Uri "$BaseUrl/me/notifications?status=read&limit=10" `
    -Headers @{ "Authorization" = "Bearer $($rotatedTokens.access_token)" }

$readBackNotification = $readNotifications.notifications |
    Where-Object { $_.notification_id -eq $pendingCreatedNotification.notification_id } |
    Select-Object -First 1

if ($null -eq $readBackNotification -or $readBackNotification.status -ne "read") {
    throw "Expected read notification to be returned by status=read filter"
}

$sanitizedMatchId = [Guid]::NewGuid().ToString()
$sanitizedMatchBody = @{
    match_id = $sanitizedMatchId
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

$sanitizedProfile = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/server/match-profile/build" `
    -Headers $serverHeaders `
    -Body $sanitizedMatchBody `
    -ContentType "application/json"

$sanitizedPrimary = $sanitizedProfile.weapons |
    Where-Object { $_.weapon_slot_id -eq "primary" } |
    Select-Object -First 1

if ($null -eq $sanitizedPrimary -or $null -ne $sanitizedPrimary.weapon_id -or $sanitizedPrimary.modules.Count -ne 0) {
    throw "Expected sanitized match profile to omit disabled weapon"
}

if ($sanitizedProfile.dependency_revisions.weapon_preset_revision -ne $weaponPresetAfterAdmin.revision) {
    throw "Expected sanitized match profile to use sanitized weapon preset revision"
}

$adminEnableBody = @{
    weapon_id = "weapon.ak12"
    catalog_version = $catalog.catalog_version
    action = "item_enable"
    reason = "vertical_smoke_enable_weapon"
    comment = "re-enable after sanitizer smoke"
} | ConvertTo-Json -Depth 4

$adminControlEnable = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/admin/control/players/$playerId/weapon-access" `
    -Headers $adminHeaders `
    -Body $adminEnableBody `
    -ContentType "application/json"

if ($adminControlEnable.disabled -ne $false -or $adminControlEnable.player_can_use -ne $true) {
    throw "Expected admin control weapon-access adapter to enable weapon.ak12"
}

$adminRetryOutbox = Invoke-RestMethod `
    -Method Post `
    -Uri "$BaseUrl/admin/control/outbox/retry-failed" `
    -Headers $adminHeaders `
    -Body "{}" `
    -ContentType "application/json"

if ($adminRetryOutbox.retried -lt 0) {
    throw "Expected admin outbox retry to return retried count"
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
    runtime_event_status = $runtimeEvent.status
    runtime_conflict_status = $runtimeConflictStatus
    post_match_pending_status = $resolvedPendingChange.status
    post_match_pending_revision = $resolvedPendingChange.result_revision
    notifications_unread = $notificationsAfterAdmin.notifications.Count
    notification_read_status = $notificationRead.status
    server_unauthenticated_status = $serverUnauthenticatedStatus
    server_forbidden_status = $serverForbiddenStatus
    admin_overview_ok = $adminOverview.backend.ok
    admin_unauthenticated_status = $adminUnauthenticatedStatus
    admin_access_revision = $adminAccess.access_revision
    admin_access_duplicate = $adminAccessReplay.duplicate
    admin_disabled_can_use = $disabledModule.player_can_use
    sanitized_weapon_presets = $adminAccess.sanitized_weapon_presets
    stale_match_profiles = $adminAccess.stale_match_profiles
    sanitized_weapon_revision = $weaponPresetAfterAdmin.revision
    sanitized_profile_modules = $sanitizedPrimary.modules.Count
    admin_control_enabled = $adminControlEnable.player_can_use
    admin_outbox_retried = $adminRetryOutbox.retried
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
