package com.game.backend.catalog.application;

import com.game.backend.catalog.repository.CatalogRepository;
import com.game.backend.catalog.repository.CatalogRepository.AccessFlags;
import com.game.backend.catalog.repository.CatalogRepository.CatalogDeployment;
import com.game.backend.catalog.repository.CatalogRepository.LifecycleCatalogItem;
import com.game.backend.catalog.repository.CatalogRepository.MigrationEntry;
import com.game.backend.catalog.repository.CatalogRepository.ModuleSnapshot;
import com.game.backend.catalog.repository.CatalogRepository.OutfitItemSnapshot;
import com.game.backend.catalog.repository.CatalogRepository.OutfitPresetHeader;
import com.game.backend.catalog.repository.CatalogRepository.WeaponConfigSnapshot;
import com.game.backend.catalog.repository.CatalogRepository.WeaponPresetHeader;
import com.game.backend.catalog.repository.CatalogRepository.WeaponSlotSnapshot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.application.AdminAuditService;
import com.game.backend.admin.application.AdminIdentity;
import com.game.backend.admin.application.AdminMutationIdempotencyService;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.api.CatalogLifecycleResponse;
import com.game.backend.catalog.api.CatalogPublishRequest;
import com.game.backend.catalog.api.CatalogRollbackRequest;
import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
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

    private final CatalogRepository repository;
    private final ObjectMapper objectMapper;
    private final AdminMutationIdempotencyService idempotencyService;
    private final AdminAuditService adminAuditService;
    private final OutboxService outboxService;
    private final RedisCacheService cacheService;
    private final CatalogValidationData catalogValidationData;

    public CatalogLifecycleService(
        CatalogRepository repository,
        ObjectMapper objectMapper,
        AdminMutationIdempotencyService idempotencyService,
        AdminAuditService adminAuditService,
        OutboxService outboxService,
        RedisCacheService cacheService,
        CatalogValidationData catalogValidationData
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.adminAuditService = adminAuditService;
        this.outboxService = outboxService;
        this.cacheService = cacheService;
        this.catalogValidationData = catalogValidationData;
    }

    /**
     * Публикует validated/canary catalog_version как единственную active для новых матчей realm.
     */
    public CatalogLifecycleResponse publish(AdminIdentity admin, String idempotencyKey, CatalogPublishRequest request) {
        return idempotencyService.execute(
            admin,
            "admin.catalog.publish",
            "/admin/catalog/publish",
            idempotencyKey,
            request,
            CatalogLifecycleResponse.class,
            () -> publishOnce(admin, request)
        );
    }

    @Transactional
    protected CatalogLifecycleResponse publishOnce(AdminIdentity admin, CatalogPublishRequest request) {
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
    public CatalogLifecycleResponse rollback(AdminIdentity admin, String idempotencyKey, CatalogRollbackRequest request) {
        return idempotencyService.execute(
            admin,
            "admin.catalog.rollback",
            "/admin/catalog/rollback",
            idempotencyKey,
            request,
            CatalogLifecycleResponse.class,
            () -> rollbackOnce(admin, request)
        );
    }

    @Transactional
    protected CatalogLifecycleResponse rollbackOnce(AdminIdentity admin, CatalogRollbackRequest request) {
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
        List<UUID> playerIds = repository.findAccessProjectionPlayerIds();
        List<LifecycleCatalogItem> newItems = catalogItems(toVersion);
        int migratedPlayers = 0;

        for (UUID playerId : playerIds) {
            boolean changed = false;
            for (LifecycleCatalogItem item : newItems) {
                String oldItemId = oldItemIdForNewItem(item.itemId(), fromVersion, toVersion);
                AccessFlags flags = oldItemId == null ? AccessFlags.defaultOpen(item.enabled()) : accessFlags(playerId, oldItemId, fromVersion, item.enabled());
                int updated = repository.upsertPlayerItemAccess(playerId, item.itemId(), toVersion, flags, now);
                changed = changed || updated > 0;
            }
            if (changed) {
                repository.bumpAccessProjectionRevision(playerId, now);
                migratedPlayers++;
            }
        }
        return migratedPlayers;
    }

    private int migrateWeaponPresets(long fromVersion, long toVersion, UUID operationId, OffsetDateTime now) {
        List<WeaponPresetHeader> headers = repository.lockWeaponPresetHeaders(fromVersion);

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
            repository.deleteWeaponPreset(preset.playerId(), preset.classTag(), preset.presetSlot());
            repository.insertWeaponPreset(
                preset.playerId(),
                preset.classTag(),
                preset.presetSlot(),
                toVersion,
                preset.revision() + 1,
                preset.sanitized() || migrated.sanitized(),
                now
            );
            for (MigratedWeaponSlot slot : migrated.slots()) {
                repository.insertWeaponPresetSlot(
                    preset.playerId(),
                    preset.classTag(),
                    preset.presetSlot(),
                    toVersion,
                    slot.weaponSlotId(),
                    slot.selectedWeaponId()
                );
            }
            for (MigratedWeaponConfig config : migrated.configs()) {
                repository.insertWeaponConfig(
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
                    repository.insertWeaponConfigModule(
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
        List<OutfitPresetHeader> headers = repository.lockOutfitPresetHeaders(fromVersion);

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
            repository.deleteOutfitPreset(preset.playerId(), preset.teamTag(), preset.classTag(), preset.outfitPresetSlot());
            repository.insertOutfitPreset(
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
                repository.insertOutfitPresetItem(
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
            repository.retireActiveDeployment(realmId, now);
            repository.markCatalogVersionPrevious(previousVersion, now);
        }

        repository.upsertActiveDeployment(realmId, targetVersion, rolloutPercent, allowExistingMatches, now);
        repository.markCatalogVersionActive(targetVersion, now);
    }

    private void rollbackDeployment(String realmId, long activeVersion, long targetVersion, OffsetDateTime now) {
        repository.markDeploymentRolledBack(realmId, activeVersion, now);
        repository.markCatalogVersionRolledBack(activeVersion, now);
        repository.reactivateDeployment(realmId, targetVersion, now);
        repository.markCatalogVersionActive(targetVersion, now);
    }

    private int invalidateRealmProfiles(String realmId, String reason, UUID operationId, OffsetDateTime now) {
        return repository.markRealmProfilesStale(realmId, reason + ":" + operationId, now);
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
        if (!repository.realmExists(realmId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REALM_NOT_FOUND", "Realm was not found or is inactive");
        }
    }

    private void ensurePublishableCatalogVersion(long catalogVersion) {
        List<String> states = repository.lockCatalogVersionStates(catalogVersion);
        if (states.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATALOG_VERSION_NOT_FOUND", "Catalog version was not found");
        }
        String state = states.getFirst();
        if ("draft".equals(state) || "retired".equals(state) || "rolled_back".equals(state)) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOG_VERSION_NOT_PUBLISHABLE", "Only validated/canary/previous catalog versions can be published");
        }
    }

    private void ensureRollbackTarget(String realmId, long catalogVersion) {
        if (!repository.rollbackTargetExists(realmId, catalogVersion)) {
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
        List<CatalogDeployment> rows = repository.lockActiveDeployments(realmId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long latestPreviousDeploymentVersion(String realmId) {
        List<Long> versions = repository.findLatestPreviousDeploymentVersions(realmId);
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "PREVIOUS_CATALOG_NOT_FOUND", "No previous catalog deployment found for rollback");
        }
        return versions.getFirst();
    }

    private List<LifecycleCatalogItem> catalogItems(long catalogVersion) {
        return repository.findLifecycleCatalogItems(catalogVersion);
    }

    private AccessFlags accessFlags(UUID playerId, String itemId, long catalogVersion, boolean targetCatalogEnabled) {
        List<AccessFlags> rows = repository.findAccessFlags(playerId, itemId, catalogVersion, targetCatalogEnabled);
        return rows.isEmpty() ? AccessFlags.defaultOpen(targetCatalogEnabled) : rows.getFirst();
    }

    private String oldItemIdForNewItem(String newItemId, long fromVersion, long toVersion) {
        if (catalogItemExists(newItemId, fromVersion)) {
            return newItemId;
        }
        List<String> mapped = repository.findMappedOldItemIdsForNewItem(newItemId, fromVersion, toVersion);
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
        repository.insertPostMatchCatalogConflict(
            changeId,
            preset.playerId(),
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
        List<MigrationEntry> rows = repository.findMigrationEntries(fromVersion, toVersion, idType, oldId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private boolean catalogItemExists(String itemId, long catalogVersion) {
        return repository.catalogItemExists(itemId, catalogVersion);
    }

    private boolean mountExists(String mountId, long catalogVersion) {
        return repository.mountExists(mountId, catalogVersion);
    }

    private boolean catalogItemUsableForPreset(String itemId, long catalogVersion, String itemType, String classTag) {
        return repository.catalogItemUsableForPreset(itemId, catalogVersion, itemType, classTag);
    }

    private boolean catalogItemUsableForOutfit(String itemId, long catalogVersion, String classTag) {
        return repository.catalogItemUsableForOutfit(itemId, catalogVersion, classTag);
    }

    private boolean mountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        return repository.mountModuleAllowed(catalogVersion, weaponId, mountId, moduleId);
    }

    private boolean weaponSlotAllowed(String classTag, String weaponSlotId) {
        return repository.weaponSlotAllowed(classTag, weaponSlotId);
    }

    private List<String> allowedWeaponSlots(String classTag) {
        return repository.findAllowedWeaponSlots(classTag);
    }

    private boolean clothingSlotActive(String clothingSlotId) {
        return repository.clothingSlotActive(clothingSlotId);
    }

    private List<WeaponSlotSnapshot> weaponSlotsSnapshot(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return repository.findWeaponSlotSnapshots(playerId, classTag, presetSlot, catalogVersion);
    }

    private List<WeaponConfigSnapshot> weaponConfigsSnapshot(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return repository.findWeaponConfigSnapshots(playerId, classTag, presetSlot, catalogVersion);
    }

    private List<OutfitItemSnapshot> outfitItemsSnapshot(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
        return repository.findOutfitItemSnapshots(playerId, teamTag, classTag, outfitPresetSlot, catalogVersion);
    }

    private String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", field + " is required");
        }
        return value.trim();
    }

    private record LifecycleMigrationResult(int weaponPresets, int outfitPresets, int accessPlayers) {
        static LifecycleMigrationResult empty() {
            return new LifecycleMigrationResult(0, 0, 0);
        }
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

    private record MigratedOutfitPreset(List<MigratedOutfitItem> items, boolean sanitized) {
    }

    private record MigratedOutfitItem(String clothingSlotId, String itemId) {
    }
}
