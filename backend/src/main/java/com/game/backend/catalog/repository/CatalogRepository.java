package com.game.backend.catalog.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class CatalogRepository extends JdbcRepository {
    public record CatalogItemRecord(
        String itemId,
        long catalogVersion,
        String itemType,
        String displayName,
        boolean enabled
    ) {
    }

    public record WeaponMountRecord(
        String mountId,
        long catalogVersion,
        String weaponId,
        String mountType,
        int mountIndex,
        boolean required,
        int displayOrder
    ) {
    }

    public record AllowedModuleRecord(
        String mountId,
        String moduleId,
        long catalogVersion
    ) {
    }

    public record WeaponSlotRule(String classTag, String weaponSlotId, boolean allowed) {
    }

    public record MountAllowedModule(long catalogVersion, String mountId, String moduleId) {
    }

    public record CatalogDeployment(long catalogVersion, boolean allowNewMatches, boolean allowExistingMatches) {
    }

    public record MigrationEntry(String action, String newId) {
    }

    public record WeaponPresetHeader(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized
    ) {
    }

    public record WeaponSlotSnapshot(String weaponSlotId, String selectedWeaponId) {
    }

    public record WeaponConfigSnapshot(String weaponSlotId, String weaponId, long configRevision, List<ModuleSnapshot> modules) {
    }

    public record ModuleSnapshot(String mountId, String moduleId) {
    }

    public record OutfitPresetHeader(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized
    ) {
    }

    public record OutfitItemSnapshot(String clothingSlotId, String itemId) {
    }

    public record LifecycleCatalogItem(String itemId, String itemType, boolean enabled) {
    }

    public record AccessFlags(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        String unlockHintPayloadJson
    ) {
        public static AccessFlags defaultOpen(boolean catalogEnabled) {
            return new AccessFlags(
                false,
                false,
                false,
                !catalogEnabled,
                catalogEnabled ? null : "catalog_disabled",
                catalogEnabled ? null : "admin_disabled",
                null
            );
        }
    }

    public CatalogRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<Long> findActiveCatalogVersionsForNewMatches(String realmId) {
        return queryForList(
            """
                SELECT catalog_version
                FROM catalog_deployments
                WHERE realm_id = ?
                  AND deployment_state = 'active'
                  AND allow_new_matches = true
                ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                LIMIT 1
                """,
            Long.class,
            realmId
        );
    }

    public boolean catalogVersionAllowsNewMatches(String realmId, long catalogVersion) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_deployments
                  WHERE realm_id = ?
                    AND catalog_version = ?
                    AND deployment_state IN ('active', 'canary')
                    AND allow_new_matches = true
                )
                """,
            Boolean.class,
            realmId,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    public List<CatalogItemRecord> findItems(long catalogVersion) {
        return query(
            """
                SELECT item_id, catalog_version, item_type, display_name, is_enabled
                FROM catalog_items
                WHERE catalog_version = ?
                ORDER BY item_type, item_id
                """,
            (rs, rowNum) -> new CatalogItemRecord(
                rs.getString("item_id"),
                rs.getLong("catalog_version"),
                rs.getString("item_type"),
                rs.getString("display_name"),
                rs.getBoolean("is_enabled")
            ),
            catalogVersion
        );
    }

    public List<WeaponMountRecord> findWeaponMounts(long catalogVersion) {
        return query(
            """
                SELECT mount_id, catalog_version, weapon_id, mount_type, mount_index, is_required, display_order
                FROM weapon_module_mounts
                WHERE catalog_version = ?
                ORDER BY weapon_id, display_order, mount_id
                """,
            (rs, rowNum) -> new WeaponMountRecord(
                rs.getString("mount_id"),
                rs.getLong("catalog_version"),
                rs.getString("weapon_id"),
                rs.getString("mount_type"),
                rs.getInt("mount_index"),
                rs.getBoolean("is_required"),
                rs.getInt("display_order")
            ),
            catalogVersion
        );
    }

    public List<AllowedModuleRecord> findAllowedModules(long catalogVersion) {
        return query(
            """
                SELECT mount_id, module_id, catalog_version
                FROM weapon_mount_allowed_modules
                WHERE catalog_version = ?
                ORDER BY mount_id, module_id
                """,
            (rs, rowNum) -> new AllowedModuleRecord(
                rs.getString("mount_id"),
                rs.getString("module_id"),
                rs.getLong("catalog_version")
            ),
            catalogVersion
        );
    }

    public List<WeaponSlotRule> findAllWeaponSlotRules() {
        return query(
            """
                SELECT class_tag, weapon_slot_id, is_allowed
                FROM class_weapon_slot_rules
                """,
            (rs, rowNum) -> new WeaponSlotRule(
                rs.getString("class_tag"),
                rs.getString("weapon_slot_id"),
                rs.getBoolean("is_allowed")
            )
        );
    }

    public List<WeaponSlotRule> findWeaponSlotRules(String classTag) {
        return query(
            """
                SELECT class_tag, weapon_slot_id, is_allowed
                FROM class_weapon_slot_rules
                WHERE class_tag = ?
                """,
            (rs, rowNum) -> new WeaponSlotRule(
                rs.getString("class_tag"),
                rs.getString("weapon_slot_id"),
                rs.getBoolean("is_allowed")
            ),
            classTag
        );
    }

    public List<String> findActiveClothingSlotIds() {
        return queryForList(
            """
                SELECT clothing_slot_id
                FROM clothing_slot_definitions
                WHERE is_active = true
                """,
            String.class
        );
    }

    public List<MountAllowedModule> findAllMountAllowedModules() {
        return query(
            """
                SELECT catalog_version, mount_id, module_id
                FROM weapon_mount_allowed_modules
                ORDER BY catalog_version, mount_id, module_id
                """,
            (rs, rowNum) -> new MountAllowedModule(
                rs.getLong("catalog_version"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            )
        );
    }

    public List<MountAllowedModule> findMountAllowedModules(long catalogVersion) {
        return query(
            """
                SELECT catalog_version, mount_id, module_id
                FROM weapon_mount_allowed_modules
                WHERE catalog_version = ?
                """,
            (rs, rowNum) -> new MountAllowedModule(
                rs.getLong("catalog_version"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            ),
            catalogVersion
        );
    }

    public List<UUID> findAccessProjectionPlayerIds() {
        return queryForList(
            """
                SELECT player_id
                FROM player_access_projection_state
                ORDER BY player_id
                """,
            UUID.class
        );
    }

    public List<LifecycleCatalogItem> findLifecycleCatalogItems(long catalogVersion) {
        return query(
            """
                SELECT item_id, item_type, is_enabled
                FROM catalog_items
                WHERE catalog_version = ?
                ORDER BY item_id
                """,
            (rs, rowNum) -> new LifecycleCatalogItem(
                rs.getString("item_id"),
                rs.getString("item_type"),
                rs.getBoolean("is_enabled")
            ),
            catalogVersion
        );
    }

    public List<AccessFlags> findAccessFlags(
        UUID playerId,
        String itemId,
        long catalogVersion,
        boolean targetCatalogEnabled
    ) {
        return query(
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
    }

    public int upsertPlayerItemAccess(
        UUID playerId,
        String itemId,
        long catalogVersion,
        AccessFlags flags,
        OffsetDateTime now
    ) {
        return update(
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
            itemId,
            catalogVersion,
            flags.hidden(),
            flags.lockedInShop(),
            flags.lockedByQuest(),
            flags.disabled(),
            flags.disabledReason(),
            flags.unlockHintCode(),
            flags.unlockHintPayloadJson(),
            now
        );
    }

    public void bumpAccessProjectionRevision(UUID playerId, OffsetDateTime now) {
        update(
            """
                UPDATE player_access_projection_state
                SET access_revision = access_revision + 1,
                    projection_rebuilt_at = ?
                WHERE player_id = ?
                """,
            now,
            playerId
        );
    }

    public List<String> findMappedOldItemIdsForNewItem(String newItemId, long fromVersion, long toVersion) {
        return queryForList(
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
    }

    public boolean catalogItemExists(String itemId, long catalogVersion) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items
                  WHERE item_id = ?
                    AND catalog_version = ?
                )
                """,
            Boolean.class,
            itemId,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean realmExists(String realmId) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM realms
                  WHERE realm_id = ?
                    AND is_active = true
                )
                """,
            Boolean.class,
            realmId
        );
        return Boolean.TRUE.equals(exists);
    }

    public List<String> lockCatalogVersionStates(long catalogVersion) {
        return queryForList(
            """
                SELECT state
                FROM catalog_versions
                WHERE catalog_version = ?
                FOR UPDATE
                """,
            String.class,
            catalogVersion
        );
    }

    public boolean rollbackTargetExists(String realmId, long catalogVersion) {
        Boolean exists = queryForObject(
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
        return Boolean.TRUE.equals(exists);
    }

    public List<CatalogDeployment> lockActiveDeployments(String realmId) {
        return query(
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
    }

    public List<Long> findLatestPreviousDeploymentVersions(String realmId) {
        return queryForList(
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
    }

    public void retireActiveDeployment(String realmId, OffsetDateTime now) {
        update(
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
    }

    public void markCatalogVersionPrevious(long catalogVersion, OffsetDateTime now) {
        update(
            """
                UPDATE catalog_versions
                SET state = 'previous',
                    retired_at = ?
                WHERE catalog_version = ?
                """,
            now,
            catalogVersion
        );
    }

    public void upsertActiveDeployment(
        String realmId,
        long catalogVersion,
        int rolloutPercent,
        boolean allowExistingMatches,
        OffsetDateTime now
    ) {
        update(
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
            catalogVersion,
            rolloutPercent,
            allowExistingMatches,
            now
        );
    }

    public void markCatalogVersionActive(long catalogVersion, OffsetDateTime now) {
        update(
            """
                UPDATE catalog_versions
                SET state = 'active',
                    activated_at = ?,
                    retired_at = null
                WHERE catalog_version = ?
                """,
            now,
            catalogVersion
        );
    }

    public void markDeploymentRolledBack(String realmId, long catalogVersion, OffsetDateTime now) {
        update(
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
            catalogVersion
        );
    }

    public void markCatalogVersionRolledBack(long catalogVersion, OffsetDateTime now) {
        update(
            """
                UPDATE catalog_versions
                SET state = 'rolled_back',
                    retired_at = ?
                WHERE catalog_version = ?
                """,
            now,
            catalogVersion
        );
    }

    public void reactivateDeployment(String realmId, long catalogVersion, OffsetDateTime now) {
        update(
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
            catalogVersion
        );
    }

    public int markRealmProfilesStale(String realmId, String staleReason, OffsetDateTime now) {
        return update(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE realm_id = ?
                  AND is_stale = false
                """,
            staleReason,
            now,
            realmId
        );
    }

    public List<MigrationEntry> findMigrationEntries(long fromVersion, long toVersion, String idType, String oldId) {
        return query(
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
    }

    public boolean mountExists(String mountId, long catalogVersion) {
        Boolean exists = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM weapon_module_mounts
                  WHERE mount_id = ?
                    AND catalog_version = ?
                )
                """,
            Boolean.class,
            mountId,
            catalogVersion
        );
        return Boolean.TRUE.equals(exists);
    }

    public boolean catalogItemUsableForPreset(String itemId, long catalogVersion, String itemType, String classTag) {
        Boolean usable = queryForObject(
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

    public boolean catalogItemUsableForOutfit(String itemId, long catalogVersion, String classTag) {
        Boolean usable = queryForObject(
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

    public boolean mountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        Boolean allowed = queryForObject(
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

    public boolean weaponSlotAllowed(String classTag, String weaponSlotId) {
        Boolean allowed = queryForObject(
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

    public List<String> findAllowedWeaponSlots(String classTag) {
        return queryForList(
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

    public boolean clothingSlotActive(String clothingSlotId) {
        Boolean active = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM clothing_slot_definitions
                  WHERE clothing_slot_id = ?
                    AND is_active = true
                )
                """,
            Boolean.class,
            clothingSlotId
        );
        return Boolean.TRUE.equals(active);
    }

    public List<WeaponPresetHeader> lockWeaponPresetHeaders(long catalogVersion) {
        return query(
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
            catalogVersion
        );
    }

    public List<WeaponSlotSnapshot> findWeaponSlotSnapshots(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion
    ) {
        return query(
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

    public List<WeaponConfigSnapshot> findWeaponConfigSnapshots(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion
    ) {
        return query(
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
                findModuleSnapshots(
                    playerId,
                    classTag,
                    presetSlot,
                    catalogVersion,
                    rs.getString("weapon_slot_id"),
                    rs.getString("weapon_id")
                )
            ),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public List<ModuleSnapshot> findModuleSnapshots(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        return query(
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

    public void deleteWeaponPreset(UUID playerId, String classTag, int presetSlot) {
        update(
            """
                DELETE FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                """,
            playerId,
            classTag,
            presetSlot
        );
    }

    public void insertWeaponPreset(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized,
        OffsetDateTime now
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            revision,
            sanitized,
            now
        );
    }

    public void insertWeaponPresetSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String selectedWeaponId
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            selectedWeaponId
        );
    }

    public void insertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        long configRevision,
        OffsetDateTime now
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            configRevision,
            now
        );
    }

    public void insertWeaponConfigModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            mountId,
            moduleId
        );
    }

    public List<OutfitPresetHeader> lockOutfitPresetHeaders(long catalogVersion) {
        return query(
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
            catalogVersion
        );
    }

    public List<OutfitItemSnapshot> findOutfitItemSnapshots(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion
    ) {
        return query(
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

    public void deleteOutfitPreset(UUID playerId, String teamTag, String classTag, int outfitPresetSlot) {
        update(
            """
                DELETE FROM player_outfit_presets
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                """,
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot
        );
    }

    public void insertOutfitPreset(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        boolean sanitized,
        OffsetDateTime now
    ) {
        update(
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
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion,
            revision,
            sanitized,
            now
        );
    }

    public void insertOutfitPresetItem(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        String clothingSlotId,
        String itemId
    ) {
        update(
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
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion,
            clothingSlotId,
            itemId
        );
    }

    public void insertPostMatchCatalogConflict(
        UUID changeId,
        UUID playerId,
        String classTag,
        int presetSlot,
        long baseRevision,
        long resultRevision,
        String payloadJson,
        OffsetDateTime now,
        OffsetDateTime expiresAt
    ) {
        update(
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
            playerId,
            null,
            classTag,
            presetSlot,
            baseRevision,
            resultRevision,
            payloadJson,
            now,
            expiresAt
        );
    }
}
