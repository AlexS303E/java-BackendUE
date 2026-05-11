package com.game.backend.catalog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.application.AdminAuditService;
import com.game.backend.admin.application.AdminIdentity;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.api.CatalogLifecycleResponse;
import com.game.backend.catalog.api.CatalogPublishRequest;
import com.game.backend.catalog.api.CatalogRollbackRequest;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Минимальный catalog lifecycle для MVP: publish/rollback, deployment pointers,
 * перенос текущих player projections/presets на новую active версию и stale old snapshots.
 *
 * В текущей canonical schema player_weapon_presets/player_outfit_presets имеют PK без catalog_version,
 * поэтому MVP переносит текущий durable preset в новую catalog_version вместо параллельного хранения v1/v2.
 * Уже выданные match profile snapshots остаются в player_match_profiles как pinned JSON payload,
 * но помечаются stale для новых admission.
 */
@Service
public class CatalogLifecycleService {
    private static final String ACTION_PUBLISH = "catalog.publish";
    private static final String ACTION_ROLLBACK = "catalog.rollback";
    private static final int MANUAL_CONFLICT_TTL_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminAuditService adminAuditService;
    private final OutboxService outboxService;
    private final RedisCacheService cacheService;
    private final CatalogValidationData catalogValidationData;

    public CatalogLifecycleService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AdminAuditService adminAuditService,
        OutboxService outboxService,
        RedisCacheService cacheService,
        CatalogValidationData catalogValidationData
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adminAuditService = adminAuditService;
        this.outboxService = outboxService;
        this.cacheService = cacheService;
        this.catalogValidationData = catalogValidationData;
    }

    /**
     * Публикует validated/canary catalog_version как единственную active для новых матчей realm.
     */
    @Transactional
    public CatalogLifecycleResponse publish(AdminIdentity admin, CatalogPublishRequest request) {
        UUID operationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String realmId = normalize(request.realmId(), "realm_id");
        long targetVersion = request.catalogVersion();
        int rolloutPercent = request.rolloutPercent() == null ? 100 : request.rolloutPercent();
        boolean allowExistingMatches = request.allowExistingMatches() == null || request.allowExistingMatches();

        try {
            ensureRealmExists(realmId);
            ensurePublishableCatalogVersion(targetVersion);
            CatalogDeployment active = activeDeployment(realmId);
            if (active != null && active.catalogVersion() == targetVersion && active.allowNewMatches()) {
                CatalogLifecycleResponse response = new CatalogLifecycleResponse(
                    operationId,
                    realmId,
                    targetVersion,
                    targetVersion,
                    "publish_noop",
                    0,
                    0,
                    0,
                    0
                );
                audit(admin, ACTION_PUBLISH, realmId, targetVersion, "success", response, Map.of("noop", true, "reason", request.reason()));
                return response;
            }

            long previousVersion = active == null ? 0L : active.catalogVersion();
            LifecycleMigrationResult migration = previousVersion == 0L || previousVersion == targetVersion
                ? LifecycleMigrationResult.empty()
                : migrateDurablePlayerState(previousVersion, targetVersion, operationId, now);

            activateCatalog(realmId, previousVersion, targetVersion, rolloutPercent, allowExistingMatches, now);
            cacheService.evictCatalogSnapshots(realmId);
            cacheService.evictCatalogAllowsNewMatches(realmId);
            catalogValidationData.evict();
            int staleProfiles = invalidateRealmProfiles(realmId, "catalog_published", operationId, now);
            recordOutbox(ACTION_PUBLISH, operationId, realmId, previousVersion, targetVersion, migration, staleProfiles, now);

            CatalogLifecycleResponse response = new CatalogLifecycleResponse(
                operationId,
                realmId,
                previousVersion,
                targetVersion,
                "publish",
                migration.weaponPresets(),
                migration.outfitPresets(),
                migration.accessPlayers(),
                staleProfiles
            );
            audit(admin, ACTION_PUBLISH, realmId, targetVersion, "success", response, Map.of("reason", request.reason()));
            return response;
        } catch (ApiException exception) {
            auditFailure(admin, ACTION_PUBLISH, realmId, targetVersion, request.reason(), exception);
            throw exception;
        }
    }

    /**
     * Откатывает realm к previous deployment или к явно указанной версии каталога.
     */
    @Transactional
    public CatalogLifecycleResponse rollback(AdminIdentity admin, CatalogRollbackRequest request) {
        UUID operationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        String realmId = normalize(request.realmId(), "realm_id");

        try {
            ensureRealmExists(realmId);
            CatalogDeployment active = requireActiveDeployment(realmId);
            long targetVersion = request.targetCatalogVersion() == null
                ? latestPreviousDeploymentVersion(realmId)
                : request.targetCatalogVersion();
            ensureRollbackTarget(realmId, targetVersion);
            if (active.catalogVersion() == targetVersion) {
                CatalogLifecycleResponse response = new CatalogLifecycleResponse(
                    operationId,
                    realmId,
                    targetVersion,
                    targetVersion,
                    "rollback_noop",
                    0,
                    0,
                    0,
                    0
                );
                audit(admin, ACTION_ROLLBACK, realmId, targetVersion, "success", response, Map.of("noop", true, "reason", request.reason()));
                return response;
            }

            LifecycleMigrationResult migration = migrateDurablePlayerState(active.catalogVersion(), targetVersion, operationId, now);
            rollbackDeployment(realmId, active.catalogVersion(), targetVersion, now);
            cacheService.evictCatalogSnapshots(realmId);
            catalogValidationData.evict();
            int staleProfiles = invalidateRealmProfiles(realmId, "catalog_rolled_back", operationId, now);
            recordOutbox(ACTION_ROLLBACK, operationId, realmId, active.catalogVersion(), targetVersion, migration, staleProfiles, now);

            CatalogLifecycleResponse response = new CatalogLifecycleResponse(
                operationId,
                realmId,
                active.catalogVersion(),
                targetVersion,
                "rollback",
                migration.weaponPresets(),
                migration.outfitPresets(),
                migration.accessPlayers(),
                staleProfiles
            );
            audit(admin, ACTION_ROLLBACK, realmId, targetVersion, "success", response, Map.of("reason", request.reason()));
            return response;
        } catch (ApiException exception) {
            auditFailure(admin, ACTION_ROLLBACK, realmId, request.targetCatalogVersion(), request.reason(), exception);
            throw exception;
        }
    }

    private LifecycleMigrationResult migrateDurablePlayerState(long fromVersion, long toVersion, UUID operationId, OffsetDateTime now) {
        int accessPlayers = migratePlayerAccess(fromVersion, toVersion, now);
        int weaponPresets = migrateWeaponPresets(fromVersion, toVersion, operationId, now);
        int outfitPresets = migrateOutfitPresets(fromVersion, toVersion, now);
        return new LifecycleMigrationResult(weaponPresets, outfitPresets, accessPlayers);
    }

    private int migratePlayerAccess(long fromVersion, long toVersion, OffsetDateTime now) {
        List<UUID> playerIds = jdbcTemplate.queryForList(
            "SELECT player_id FROM player_access_projection_state ORDER BY player_id",
            UUID.class
        );
        List<CatalogItem> newItems = catalogItems(toVersion);
        int migratedPlayers = 0;

        for (UUID playerId : playerIds) {
            boolean changed = false;
            for (CatalogItem item : newItems) {
                String oldItemId = oldItemIdForNewItem(item.itemId(), fromVersion, toVersion);
                AccessFlags flags = oldItemId == null ? AccessFlags.defaultOpen(item.enabled()) : accessFlags(playerId, oldItemId, fromVersion, item.enabled());
                int updated = jdbcTemplate.update(
                    """
                        INSERT INTO player_item_access(
                          player_id,
                          item_id,
                          catalog_version,
                          is_hidden,
                          is_locked_in_shop,
                          is_locked_by_quest,
                          is_disabled,
                          disabled_reason,
                          unlock_hint_code,
                          unlock_hint_payload,
                          updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                        ON CONFLICT (player_id, item_id, catalog_version)
                        DO UPDATE SET
                          is_hidden = EXCLUDED.is_hidden,
                          is_locked_in_shop = EXCLUDED.is_locked_in_shop,
                          is_locked_by_quest = EXCLUDED.is_locked_by_quest,
                          is_disabled = EXCLUDED.is_disabled,
                          disabled_reason = EXCLUDED.disabled_reason,
                          unlock_hint_code = EXCLUDED.unlock_hint_code,
                          unlock_hint_payload = EXCLUDED.unlock_hint_payload,
                          updated_at = EXCLUDED.updated_at
                        """,
                    playerId,
                    item.itemId(),
                    toVersion,
                    flags.hidden(),
                    flags.lockedInShop(),
                    flags.lockedByQuest(),
                    flags.disabled(),
                    flags.disabledReason(),
                    flags.unlockHintCode(),
                    flags.unlockHintPayloadJson(),
                    now
                );
                changed = changed || updated > 0;
            }
            if (changed) {
                jdbcTemplate.update(
                    """
                        UPDATE player_access_projection_state
                        SET access_revision = access_revision + 1,
                            projection_rebuilt_at = ?
                        WHERE player_id = ?
                        """,
                    now,
                    playerId
                );
                migratedPlayers++;
            }
        }
        return migratedPlayers;
    }

    private int migrateWeaponPresets(long fromVersion, long toVersion, UUID operationId, OffsetDateTime now) {
        List<WeaponPresetHeader> headers = jdbcTemplate.query(
            """
                SELECT player_id, class_tag, preset_slot, catalog_version, revision, sanitized
                FROM player_weapon_presets
                WHERE catalog_version = ?
                ORDER BY player_id, class_tag, preset_slot
                FOR UPDATE
                """,
            (rs, rowNum) -> new WeaponPresetHeader(
                rs.getObject("player_id", UUID.class),
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version"),
                rs.getLong("revision"),
                rs.getBoolean("sanitized")
            ),
            fromVersion
        );

        List<WeaponPresetSnapshot> presets = headers.stream()
            .map(header -> new WeaponPresetSnapshot(
                header.playerId(),
                header.classTag(),
                header.presetSlot(),
                header.catalogVersion(),
                header.revision(),
                header.sanitized(),
                weaponSlotsSnapshot(header.playerId(), header.classTag(), header.presetSlot(), fromVersion),
                weaponConfigsSnapshot(header.playerId(), header.classTag(), header.presetSlot(), fromVersion)
            ))
            .toList();

        for (WeaponPresetSnapshot preset : presets) {
            MigratedWeaponPreset migrated = migrateWeaponPresetSnapshot(preset, fromVersion, toVersion);
            jdbcTemplate.update(
                """
                    DELETE FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                preset.playerId(),
                preset.classTag(),
                preset.presetSlot()
            );
            jdbcTemplate.update(
                """
                    INSERT INTO player_weapon_presets(
                      player_id,
                      class_tag,
                      preset_slot,
                      catalog_version,
                      revision,
                      sanitized,
                      updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                preset.playerId(),
                preset.classTag(),
                preset.presetSlot(),
                toVersion,
                preset.revision() + 1,
                preset.sanitized() || migrated.sanitized(),
                now
            );
            for (MigratedWeaponSlot slot : migrated.slots()) {
                jdbcTemplate.update(
                    """
                        INSERT INTO player_weapon_preset_slots(
                          player_id,
                          class_tag,
                          preset_slot,
                          catalog_version,
                          weapon_slot_id,
                          selected_weapon_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                    preset.playerId(),
                    preset.classTag(),
                    preset.presetSlot(),
                    toVersion,
                    slot.weaponSlotId(),
                    slot.selectedWeaponId()
                );
            }
            for (MigratedWeaponConfig config : migrated.configs()) {
                jdbcTemplate.update(
                    """
                        INSERT INTO player_weapon_preset_weapon_configs(
                          player_id,
                          class_tag,
                          preset_slot,
                          catalog_version,
                          weapon_slot_id,
                          weapon_id,
                          config_revision,
                          last_used_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                    preset.playerId(),
                    preset.classTag(),
                    preset.presetSlot(),
                    toVersion,
                    config.weaponSlotId(),
                    config.weaponId(),
                    config.configRevision(),
                    now
                );
                for (MigratedModule module : config.modules()) {
                    jdbcTemplate.update(
                        """
                            INSERT INTO player_weapon_preset_weapon_config_modules(
                              player_id,
                              class_tag,
                              preset_slot,
                              catalog_version,
                              weapon_slot_id,
                              weapon_id,
                              mount_id,
                              module_id
                            )
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                        preset.playerId(),
                        preset.classTag(),
                        preset.presetSlot(),
                        toVersion,
                        config.weaponSlotId(),
                        config.weaponId(),
                        module.mountId(),
                        module.moduleId()
                    );
                }
            }
            if (!migrated.manualConflicts().isEmpty()) {
                createManualCatalogConflict(preset, fromVersion, toVersion, operationId, migrated.manualConflicts(), now);
            }
        }
        return presets.size();
    }

    private MigratedWeaponPreset migrateWeaponPresetSnapshot(WeaponPresetSnapshot preset, long fromVersion, long toVersion) {
        boolean sanitized = false;
        List<ManualMigrationConflict> manualConflicts = new ArrayList<>();
        Map<String, String> selectedBySlot = new LinkedHashMap<>();
        for (WeaponSlotSnapshot slot : preset.slots()) {
            String newSlotId = resolveSlotId(slot.weaponSlotId(), fromVersion, toVersion, manualConflicts);
            if (newSlotId == null || !weaponSlotAllowed(preset.classTag(), newSlotId)) {
                sanitized = true;
                continue;
            }
            String selectedWeaponId = null;
            if (slot.selectedWeaponId() != null) {
                selectedWeaponId = resolveItemId(slot.selectedWeaponId(), fromVersion, toVersion, manualConflicts);
                if (selectedWeaponId == null || !catalogItemUsableForPreset(selectedWeaponId, toVersion, "weapon", preset.classTag())) {
                    sanitized = true;
                    selectedWeaponId = null;
                } else if (!selectedWeaponId.equals(slot.selectedWeaponId())) {
                    sanitized = true;
                }
            }
            selectedBySlot.put(newSlotId, selectedWeaponId);
        }
        for (String allowedSlotId : allowedWeaponSlots(preset.classTag())) {
            selectedBySlot.putIfAbsent(allowedSlotId, null);
        }

        List<MigratedWeaponConfig> configs = new ArrayList<>();
        for (WeaponConfigSnapshot config : preset.configs()) {
            String newSlotId = resolveSlotId(config.weaponSlotId(), fromVersion, toVersion, manualConflicts);
            String newWeaponId = resolveItemId(config.weaponId(), fromVersion, toVersion, manualConflicts);
            if (newSlotId == null
                || !weaponSlotAllowed(preset.classTag(), newSlotId)
                || newWeaponId == null
                || !catalogItemUsableForPreset(newWeaponId, toVersion, "weapon", preset.classTag())) {
                sanitized = true;
                continue;
            }
            if (!newSlotId.equals(config.weaponSlotId()) || !newWeaponId.equals(config.weaponId())) {
                sanitized = true;
            }
            List<MigratedModule> modules = new ArrayList<>();
            for (ModuleSnapshot module : config.modules()) {
                String newMountId = resolveMountId(module.mountId(), fromVersion, toVersion, manualConflicts);
                String newModuleId = resolveItemId(module.moduleId(), fromVersion, toVersion, manualConflicts);
                if (newMountId == null
                    || newModuleId == null
                    || !catalogItemUsableForPreset(newModuleId, toVersion, "module", preset.classTag())
                    || !mountModuleAllowed(toVersion, newWeaponId, newMountId, newModuleId)) {
                    sanitized = true;
                    continue;
                }
                if (!newMountId.equals(module.mountId()) || !newModuleId.equals(module.moduleId())) {
                    sanitized = true;
                }
                modules.add(new MigratedModule(newMountId, newModuleId));
            }
            configs.add(new MigratedWeaponConfig(newSlotId, newWeaponId, config.configRevision(), modules));
        }

        List<MigratedWeaponSlot> slots = selectedBySlot.entrySet()
            .stream()
            .map(entry -> new MigratedWeaponSlot(entry.getKey(), entry.getValue()))
            .toList();
        return new MigratedWeaponPreset(slots, configs, sanitized, manualConflicts);
    }

    private int migrateOutfitPresets(long fromVersion, long toVersion, OffsetDateTime now) {
        List<OutfitPresetHeader> headers = jdbcTemplate.query(
            """
                SELECT player_id, team_tag, class_tag, outfit_preset_slot, catalog_version, revision, sanitized
                FROM player_outfit_presets
                WHERE catalog_version = ?
                ORDER BY player_id, team_tag, class_tag, outfit_preset_slot
                FOR UPDATE
                """,
            (rs, rowNum) -> new OutfitPresetHeader(
                rs.getObject("player_id", UUID.class),
                rs.getString("team_tag"),
                rs.getString("class_tag"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("catalog_version"),
                rs.getLong("revision"),
                rs.getBoolean("sanitized")
            ),
            fromVersion
        );

        List<OutfitPresetSnapshot> presets = headers.stream()
            .map(header -> new OutfitPresetSnapshot(
                header.playerId(),
                header.teamTag(),
                header.classTag(),
                header.outfitPresetSlot(),
                header.catalogVersion(),
                header.revision(),
                header.sanitized(),
                outfitItemsSnapshot(header.playerId(), header.teamTag(), header.classTag(), header.outfitPresetSlot(), fromVersion)
            ))
            .toList();

        for (OutfitPresetSnapshot preset : presets) {
            MigratedOutfitPreset migrated = migrateOutfitPresetSnapshot(preset, fromVersion, toVersion);
            jdbcTemplate.update(
                """
                    DELETE FROM player_outfit_presets
                    WHERE player_id = ?
                      AND team_tag = ?
                      AND class_tag = ?
                      AND outfit_preset_slot = ?
                    """,
                preset.playerId(),
                preset.teamTag(),
                preset.classTag(),
                preset.outfitPresetSlot()
            );
            jdbcTemplate.update(
                """
                    INSERT INTO player_outfit_presets(
                      player_id,
                      team_tag,
                      class_tag,
                      outfit_preset_slot,
                      catalog_version,
                      revision,
                      sanitized,
                      updated_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                preset.playerId(),
                preset.teamTag(),
                preset.classTag(),
                preset.outfitPresetSlot(),
                toVersion,
                preset.revision() + 1,
                preset.sanitized() || migrated.sanitized(),
                now
            );
            for (MigratedOutfitItem item : migrated.items()) {
                jdbcTemplate.update(
                    """
                        INSERT INTO player_outfit_preset_items(
                          player_id,
                          team_tag,
                          class_tag,
                          outfit_preset_slot,
                          catalog_version,
                          clothing_slot_id,
                          item_id
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                    preset.playerId(),
                    preset.teamTag(),
                    preset.classTag(),
                    preset.outfitPresetSlot(),
                    toVersion,
                    item.clothingSlotId(),
                    item.itemId()
                );
            }
        }
        return presets.size();
    }

    private MigratedOutfitPreset migrateOutfitPresetSnapshot(OutfitPresetSnapshot preset, long fromVersion, long toVersion) {
        boolean sanitized = false;
        List<MigratedOutfitItem> items = new ArrayList<>();
        for (OutfitItemSnapshot item : preset.items()) {
            String newSlotId = resolveSlotId(item.clothingSlotId(), fromVersion, toVersion);
            String newItemId = resolveItemId(item.itemId(), fromVersion, toVersion);
            if (newSlotId == null
                || !clothingSlotActive(newSlotId)
                || newItemId == null
                || !catalogItemUsableForOutfit(newItemId, toVersion, preset.classTag())) {
                sanitized = true;
                continue;
            }
            if (!newSlotId.equals(item.clothingSlotId()) || !newItemId.equals(item.itemId())) {
                sanitized = true;
            }
            items.add(new MigratedOutfitItem(newSlotId, newItemId));
        }
        return new MigratedOutfitPreset(items, sanitized);
    }

    private void activateCatalog(String realmId, long previousVersion, long targetVersion, int rolloutPercent, boolean allowExistingMatches, OffsetDateTime now) {
        if (previousVersion != 0L) {
            jdbcTemplate.update(
                """
                    UPDATE catalog_deployments
                    SET deployment_state = 'previous',
                        rollout_percent = 0,
                        allow_new_matches = false,
                        allow_existing_matches = true,
                        retired_at = ?
                    WHERE realm_id = ?
                      AND deployment_state = 'active'
                      AND allow_new_matches = true
                    """,
                now,
                realmId
            );
            jdbcTemplate.update(
                """
                    UPDATE catalog_versions
                    SET state = 'previous',
                        retired_at = ?
                    WHERE catalog_version = ?
                    """,
                now,
                previousVersion
            );
        }

        jdbcTemplate.update(
            """
                INSERT INTO catalog_deployments(
                  realm_id,
                  catalog_version,
                  deployment_state,
                  rollout_percent,
                  allow_new_matches,
                  allow_existing_matches,
                  activated_at,
                  retired_at
                )
                VALUES (?, ?, 'active', ?, true, ?, ?, null)
                ON CONFLICT (realm_id, catalog_version)
                DO UPDATE SET
                  deployment_state = 'active',
                  rollout_percent = EXCLUDED.rollout_percent,
                  allow_new_matches = true,
                  allow_existing_matches = EXCLUDED.allow_existing_matches,
                  activated_at = EXCLUDED.activated_at,
                  retired_at = null
                """,
            realmId,
            targetVersion,
            rolloutPercent,
            allowExistingMatches,
            now
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_versions
                SET state = 'active',
                    activated_at = ?,
                    retired_at = null
                WHERE catalog_version = ?
                """,
            now,
            targetVersion
        );
    }

    private void rollbackDeployment(String realmId, long activeVersion, long targetVersion, OffsetDateTime now) {
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'rolled_back',
                    rollout_percent = 0,
                    allow_new_matches = false,
                    allow_existing_matches = true,
                    retired_at = ?
                WHERE realm_id = ?
                  AND catalog_version = ?
                """,
            now,
            realmId,
            activeVersion
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_versions
                SET state = 'rolled_back',
                    retired_at = ?
                WHERE catalog_version = ?
                """,
            now,
            activeVersion
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'active',
                    rollout_percent = 100,
                    allow_new_matches = true,
                    allow_existing_matches = true,
                    activated_at = ?,
                    retired_at = null
                WHERE realm_id = ?
                  AND catalog_version = ?
                """,
            now,
            realmId,
            targetVersion
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_versions
                SET state = 'active',
                    activated_at = ?,
                    retired_at = null
                WHERE catalog_version = ?
                """,
            now,
            targetVersion
        );
    }

    private int invalidateRealmProfiles(String realmId, String reason, UUID operationId, OffsetDateTime now) {
        return jdbcTemplate.update(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE realm_id = ?
                  AND is_stale = false
                """,
            reason + ":" + operationId,
            now,
            realmId
        );
    }

    private void recordOutbox(
        String action,
        UUID operationId,
        String realmId,
        long previousVersion,
        long activeVersion,
        LifecycleMigrationResult migration,
        int staleProfiles,
        OffsetDateTime now
    ) {
        outboxService.record(
            action,
            "catalog_deployment",
            realmId + ":" + activeVersion,
            1,
            Map.of(
                "operation_id", operationId,
                "realm_id", realmId,
                "previous_catalog_version", previousVersion,
                "active_catalog_version", activeVersion,
                "migrated_weapon_presets", migration.weaponPresets(),
                "migrated_outfit_presets", migration.outfitPresets(),
                "migrated_access_players", migration.accessPlayers(),
                "stale_match_profiles", staleProfiles
            ),
            now
        );
    }

    private void audit(
        AdminIdentity admin,
        String action,
        String realmId,
        long targetVersion,
        String result,
        CatalogLifecycleResponse response,
        Map<String, Object> extra
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation_id", response.operationId());
        payload.put("realm_id", response.realmId());
        payload.put("previous_catalog_version", response.previousCatalogVersion());
        payload.put("active_catalog_version", response.activeCatalogVersion());
        payload.put("migrated_weapon_presets", response.migratedWeaponPresets());
        payload.put("migrated_outfit_presets", response.migratedOutfitPresets());
        payload.put("migrated_access_players", response.migratedAccessPlayers());
        payload.put("stale_match_profiles", response.staleMatchProfiles());
        payload.putAll(extra);
        adminAuditService.record(admin, action, "catalog_deployment", realmId + ":" + targetVersion, null, result, payload);
    }

    private void auditFailure(AdminIdentity admin, String action, String realmId, Long targetVersion, String reason, ApiException exception) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("realm_id", realmId);
        payload.put("target_catalog_version", targetVersion);
        payload.put("reason", reason);
        payload.put("code", exception.code());
        payload.put("status", exception.status().value());
        adminAuditService.record(
            admin,
            action,
            "catalog_deployment",
            realmId + ":" + targetVersion,
            null,
            exception.status() == HttpStatus.FORBIDDEN ? "denied" : "failed",
            payload
        );
    }

    private void ensureRealmExists(String realmId) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM realms WHERE realm_id = ? AND is_active = true)",
            Boolean.class,
            realmId
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REALM_NOT_FOUND", "Realm was not found or is inactive");
        }
    }

    private void ensurePublishableCatalogVersion(long catalogVersion) {
        List<String> states = jdbcTemplate.queryForList(
            "SELECT state FROM catalog_versions WHERE catalog_version = ? FOR UPDATE",
            String.class,
            catalogVersion
        );
        if (states.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATALOG_VERSION_NOT_FOUND", "Catalog version was not found");
        }
        String state = states.getFirst();
        if ("draft".equals(state) || "retired".equals(state) || "rolled_back".equals(state)) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOG_VERSION_NOT_PUBLISHABLE", "Only validated/canary/previous catalog versions can be published");
        }
    }

    private void ensureRollbackTarget(String realmId, long catalogVersion) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_deployments cd
                  JOIN catalog_versions cv ON cv.catalog_version = cd.catalog_version
                  WHERE cd.realm_id = ?
                    AND cd.catalog_version = ?
                    AND cd.deployment_state IN ('previous', 'rolled_back', 'active')
                    AND cv.state <> 'retired'
                )
                """,
            Boolean.class,
            realmId,
            catalogVersion
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.CONFLICT, "ROLLBACK_TARGET_NOT_AVAILABLE", "Rollback target catalog version is not available for realm");
        }
    }

    private CatalogDeployment requireActiveDeployment(String realmId) {
        CatalogDeployment active = activeDeployment(realmId);
        if (active == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ACTIVE_CATALOG_NOT_FOUND", "No active catalog deployment found for realm");
        }
        return active;
    }

    private CatalogDeployment activeDeployment(String realmId) {
        List<CatalogDeployment> rows = jdbcTemplate.query(
            """
                SELECT catalog_version, allow_new_matches, allow_existing_matches
                FROM catalog_deployments
                WHERE realm_id = ?
                  AND deployment_state = 'active'
                  AND allow_new_matches = true
                ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                LIMIT 1
                FOR UPDATE
                """,
            (rs, rowNum) -> new CatalogDeployment(
                rs.getLong("catalog_version"),
                rs.getBoolean("allow_new_matches"),
                rs.getBoolean("allow_existing_matches")
            ),
            realmId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long latestPreviousDeploymentVersion(String realmId) {
        List<Long> versions = jdbcTemplate.queryForList(
            """
                SELECT catalog_version
                FROM catalog_deployments
                WHERE realm_id = ?
                  AND deployment_state = 'previous'
                ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                LIMIT 1
                """,
            Long.class,
            realmId
        );
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PREVIOUS_CATALOG_NOT_FOUND", "No previous catalog deployment found for rollback");
        }
        return versions.getFirst();
    }

    private List<CatalogItem> catalogItems(long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT item_id, item_type, is_enabled
                FROM catalog_items
                WHERE catalog_version = ?
                ORDER BY item_id
                """,
            (rs, rowNum) -> new CatalogItem(
                rs.getString("item_id"),
                rs.getString("item_type"),
                rs.getBoolean("is_enabled")
            ),
            catalogVersion
        );
    }

    private AccessFlags accessFlags(UUID playerId, String itemId, long catalogVersion, boolean targetCatalogEnabled) {
        List<AccessFlags> rows = jdbcTemplate.query(
            """
                SELECT
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  disabled_reason,
                  unlock_hint_code,
                  unlock_hint_payload::text AS unlock_hint_payload
                FROM player_item_access
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new AccessFlags(
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled") || !targetCatalogEnabled,
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                rs.getString("unlock_hint_payload")
            ),
            playerId,
            itemId,
            catalogVersion
        );
        return rows.isEmpty() ? AccessFlags.defaultOpen(targetCatalogEnabled) : rows.getFirst();
    }

    private String oldItemIdForNewItem(String newItemId, long fromVersion, long toVersion) {
        if (catalogItemExists(newItemId, fromVersion)) {
            return newItemId;
        }
        List<String> mapped = jdbcTemplate.queryForList(
            """
                SELECT old_id
                FROM catalog_id_migration_map
                WHERE from_catalog_version = ?
                  AND to_catalog_version = ?
                  AND id_type = 'item'
                  AND migration_action = 'map'
                  AND new_id = ?
                ORDER BY old_id
                LIMIT 1
                """,
            String.class,
            fromVersion,
            toVersion,
            newItemId
        );
        return mapped.isEmpty() ? null : mapped.getFirst();
    }

    private void createManualCatalogConflict(
        WeaponPresetSnapshot preset,
        long fromVersion,
        long toVersion,
        UUID operationId,
        List<ManualMigrationConflict> manualConflicts,
        OffsetDateTime now
    ) {
        UUID changeId = UUID.randomUUID();
        long resultRevision = preset.revision() + 1;
        jdbcTemplate.update(
            """
                INSERT INTO post_match_pending_changes(
                  change_id,
                  player_id,
                  match_id,
                  class_tag,
                  weapon_preset_slot,
                  base_weapon_preset_revision,
                  current_conflicting_revision,
                  reason_code,
                  status,
                  payload,
                  payload_schema_version,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'catalog_conflict', 'pending', ?::jsonb, 1, ?, ?)
                """,
            changeId,
            preset.playerId(),
            operationId,
            preset.classTag(),
            preset.presetSlot(),
            preset.revision(),
            resultRevision,
            manualConflictPayload(fromVersion, toVersion, operationId, manualConflicts),
            now,
            now.plusDays(MANUAL_CONFLICT_TTL_DAYS)
        );
    }

    private String manualConflictPayload(
        long fromVersion,
        long toVersion,
        UUID operationId,
        List<ManualMigrationConflict> manualConflicts
    ) {
        List<Map<String, Object>> conflicts = manualConflicts.stream()
            .map(conflict -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id_type", conflict.idType());
                row.put("old_id", conflict.oldId());
                row.put("new_id", conflict.newId());
                row.put("migration_action", "manual");
                return row;
            })
            .toList();

        Map<String, Object> conflict = new LinkedHashMap<>();
        conflict.put("reason_code", "catalog_conflict");
        conflict.put("resolution", "manual_required");
        conflict.put("from_catalog_version", fromVersion);
        conflict.put("to_catalog_version", toVersion);
        conflict.put("operation_id", operationId);
        conflict.put("manual_migration_conflicts", conflicts);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("source", "catalog_lifecycle");
        payload.put("conflict", conflict);
        return toJson(payload);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CATALOG_LIFECYCLE_PAYLOAD_SERIALIZATION_FAILED", "Unable to serialize catalog lifecycle payload");
        }
    }

    private String resolveItemId(String oldItemId, long fromVersion, long toVersion) {
        return resolveItemId(oldItemId, fromVersion, toVersion, null);
    }

    private String resolveItemId(String oldItemId, long fromVersion, long toVersion, List<ManualMigrationConflict> manualConflicts) {
        if (oldItemId == null) {
            return null;
        }
        MigrationEntry migration = migrationEntry(fromVersion, toVersion, "item", oldItemId);
        if (migration != null) {
            if ("map".equals(migration.action())) {
                return migration.newId();
            }
            if ("manual".equals(migration.action())) {
                addManualConflict(manualConflicts, "item", oldItemId, migration.newId());
            }
            return null;
        }
        return catalogItemExists(oldItemId, toVersion) ? oldItemId : null;
    }

    private String resolveMountId(String oldMountId, long fromVersion, long toVersion) {
        return resolveMountId(oldMountId, fromVersion, toVersion, null);
    }

    private String resolveMountId(String oldMountId, long fromVersion, long toVersion, List<ManualMigrationConflict> manualConflicts) {
        if (oldMountId == null) {
            return null;
        }
        MigrationEntry migration = migrationEntry(fromVersion, toVersion, "mount", oldMountId);
        if (migration != null) {
            if ("map".equals(migration.action())) {
                return migration.newId();
            }
            if ("manual".equals(migration.action())) {
                addManualConflict(manualConflicts, "mount", oldMountId, migration.newId());
            }
            return null;
        }
        return mountExists(oldMountId, toVersion) ? oldMountId : null;
    }

    private String resolveSlotId(String oldSlotId, long fromVersion, long toVersion) {
        return resolveSlotId(oldSlotId, fromVersion, toVersion, null);
    }

    private String resolveSlotId(String oldSlotId, long fromVersion, long toVersion, List<ManualMigrationConflict> manualConflicts) {
        if (oldSlotId == null) {
            return null;
        }
        MigrationEntry migration = migrationEntry(fromVersion, toVersion, "slot", oldSlotId);
        if (migration != null) {
            if ("map".equals(migration.action())) {
                return migration.newId();
            }
            if ("manual".equals(migration.action())) {
                addManualConflict(manualConflicts, "slot", oldSlotId, migration.newId());
            }
            return null;
        }
        return oldSlotId;
    }

    private void addManualConflict(List<ManualMigrationConflict> manualConflicts, String idType, String oldId, String newId) {
        if (manualConflicts != null) {
            manualConflicts.add(new ManualMigrationConflict(idType, oldId, newId));
        }
    }

    private MigrationEntry migrationEntry(long fromVersion, long toVersion, String idType, String oldId) {
        List<MigrationEntry> rows = jdbcTemplate.query(
            """
                SELECT migration_action, new_id
                FROM catalog_id_migration_map
                WHERE from_catalog_version = ?
                  AND to_catalog_version = ?
                  AND id_type = ?
                  AND old_id = ?
                """,
            (rs, rowNum) -> new MigrationEntry(rs.getString("migration_action"), rs.getString("new_id")),
            fromVersion,
            toVersion,
            idType,
            oldId
        );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean catalogItemExists(String itemId, long catalogVersion) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM catalog_items WHERE item_id = ? AND catalog_version = ?)",
            Boolean.class,
            itemId,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    private boolean mountExists(String mountId, long catalogVersion) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM weapon_module_mounts WHERE mount_id = ? AND catalog_version = ?)",
            Boolean.class,
            mountId,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    private boolean catalogItemUsableForPreset(String itemId, long catalogVersion, String itemType, String classTag) {
        Boolean usable = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items ci
                  WHERE ci.item_id = ?
                    AND ci.catalog_version = ?
                    AND ci.item_type = ?
                    AND ci.is_enabled = true
                    AND NOT EXISTS (
                      SELECT 1
                      FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'deny'
                    )
                    AND (
                      NOT EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.rule_effect = 'allow'
                      )
                      OR EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.class_tag = ?
                          AND icr.rule_effect = 'allow'
                      )
                    )
                )
                """,
            Boolean.class,
            itemId,
            catalogVersion,
            itemType,
            classTag,
            classTag
        );
        return Boolean.TRUE.equals(usable);
    }

    private boolean catalogItemUsableForOutfit(String itemId, long catalogVersion, String classTag) {
        Boolean usable = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items ci
                  WHERE ci.item_id = ?
                    AND ci.catalog_version = ?
                    AND ci.item_type = 'clothing'
                    AND ci.is_enabled = true
                    AND NOT EXISTS (
                      SELECT 1
                      FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'deny'
                    )
                    AND (
                      NOT EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.rule_effect = 'allow'
                      )
                      OR EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.class_tag = ?
                          AND icr.rule_effect = 'allow'
                      )
                    )
                )
                """,
            Boolean.class,
            itemId,
            catalogVersion,
            classTag,
            classTag
        );
        return Boolean.TRUE.equals(usable);
    }

    private boolean mountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        Boolean allowed = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM weapon_module_mounts wmm
                  JOIN weapon_mount_allowed_modules wmam
                    ON wmam.mount_id = wmm.mount_id
                   AND wmam.catalog_version = wmm.catalog_version
                  WHERE wmm.catalog_version = ?
                    AND wmm.weapon_id = ?
                    AND wmm.mount_id = ?
                    AND wmam.module_id = ?
                )
                """,
            Boolean.class,
            catalogVersion,
            weaponId,
            mountId,
            moduleId
        );
        return Boolean.TRUE.equals(allowed);
    }

    private boolean weaponSlotAllowed(String classTag, String weaponSlotId) {
        Boolean allowed = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM class_weapon_slot_rules
                  WHERE class_tag = ?
                    AND weapon_slot_id = ?
                    AND is_allowed = true
                )
                """,
            Boolean.class,
            classTag,
            weaponSlotId
        );
        return Boolean.TRUE.equals(allowed);
    }

    private List<String> allowedWeaponSlots(String classTag) {
        return jdbcTemplate.queryForList(
            """
                SELECT weapon_slot_id
                FROM class_weapon_slot_rules
                WHERE class_tag = ?
                  AND is_allowed = true
                ORDER BY weapon_slot_id
                """,
            String.class,
            classTag
        );
    }

    private boolean clothingSlotActive(String clothingSlotId) {
        Boolean active = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM clothing_slot_definitions WHERE clothing_slot_id = ? AND is_active = true)",
            Boolean.class,
            clothingSlotId
        );
        return Boolean.TRUE.equals(active);
    }

    private List<WeaponSlotSnapshot> weaponSlotsSnapshot(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT weapon_slot_id, selected_weapon_id
                FROM player_weapon_preset_slots
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                ORDER BY weapon_slot_id
                """,
            (rs, rowNum) -> new WeaponSlotSnapshot(rs.getString("weapon_slot_id"), rs.getString("selected_weapon_id")),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    private List<WeaponConfigSnapshot> weaponConfigsSnapshot(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT weapon_slot_id, weapon_id, config_revision
                FROM player_weapon_preset_weapon_configs
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                ORDER BY weapon_slot_id, weapon_id
                """,
            (rs, rowNum) -> new WeaponConfigSnapshot(
                rs.getString("weapon_slot_id"),
                rs.getString("weapon_id"),
                rs.getLong("config_revision"),
                modulesSnapshot(playerId, classTag, presetSlot, catalogVersion, rs.getString("weapon_slot_id"), rs.getString("weapon_id"))
            ),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    private List<ModuleSnapshot> modulesSnapshot(UUID playerId, String classTag, int presetSlot, long catalogVersion, String weaponSlotId, String weaponId) {
        return jdbcTemplate.query(
            """
                SELECT mount_id, module_id
                FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_slot_id = ?
                  AND weapon_id = ?
                ORDER BY mount_id
                """,
            (rs, rowNum) -> new ModuleSnapshot(rs.getString("mount_id"), rs.getString("module_id")),
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    private List<OutfitItemSnapshot> outfitItemsSnapshot(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT clothing_slot_id, item_id
                FROM player_outfit_preset_items
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                ORDER BY clothing_slot_id
                """,
            (rs, rowNum) -> new OutfitItemSnapshot(rs.getString("clothing_slot_id"), rs.getString("item_id")),
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion
        );
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " is required");
        }
        return value.trim();
    }

    private record CatalogDeployment(long catalogVersion, boolean allowNewMatches, boolean allowExistingMatches) {
    }

    private record LifecycleMigrationResult(int weaponPresets, int outfitPresets, int accessPlayers) {
        static LifecycleMigrationResult empty() {
            return new LifecycleMigrationResult(0, 0, 0);
        }
    }

    private record CatalogItem(String itemId, String itemType, boolean enabled) {
    }

    private record AccessFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        String unlockHintPayloadJson
    ) {
        static AccessFlags defaultOpen(boolean catalogEnabled) {
            return new AccessFlags(false, false, false, !catalogEnabled, catalogEnabled ? null : "catalog_disabled", catalogEnabled ? null : "admin_disabled", null);
        }
    }

    private record MigrationEntry(String action, String newId) {
    }

    private record WeaponPresetHeader(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized
    ) {
    }

    private record WeaponPresetSnapshot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized,
        List<WeaponSlotSnapshot> slots,
        List<WeaponConfigSnapshot> configs
    ) {
    }

    private record WeaponSlotSnapshot(String weaponSlotId, String selectedWeaponId) {
    }

    private record WeaponConfigSnapshot(String weaponSlotId, String weaponId, long configRevision, List<ModuleSnapshot> modules) {
    }

    private record ModuleSnapshot(String mountId, String moduleId) {
    }

    private record MigratedWeaponPreset(
        List<MigratedWeaponSlot> slots,
        List<MigratedWeaponConfig> configs,
        boolean sanitized,
        List<ManualMigrationConflict> manualConflicts
    ) {
    }

    private record MigratedWeaponSlot(String weaponSlotId, String selectedWeaponId) {
    }

    private record MigratedWeaponConfig(String weaponSlotId, String weaponId, long configRevision, List<MigratedModule> modules) {
    }

    private record MigratedModule(String mountId, String moduleId) {
    }

    private record ManualMigrationConflict(String idType, String oldId, String newId) {
    }

    private record OutfitPresetHeader(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized
    ) {
    }

    private record OutfitPresetSnapshot(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized,
        List<OutfitItemSnapshot> items
    ) {
    }

    private record OutfitItemSnapshot(String clothingSlotId, String itemId) {
    }

    private record MigratedOutfitPreset(List<MigratedOutfitItem> items, boolean sanitized) {
    }

    private record MigratedOutfitItem(String clothingSlotId, String itemId) {
    }
}
