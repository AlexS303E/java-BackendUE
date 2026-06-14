package com.game.backend.catalog.repository;

import com.game.backend.catalog.api.AllowedModuleDto;
import com.game.backend.catalog.api.CatalogItemDto;
import com.game.backend.catalog.api.WeaponMountDto;
import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class CatalogRepository extends JdbcRepository {
    public record WeaponSlotRule(String classTag, String weaponSlotId, boolean allowed) {
    }

    public record MountAllowedModule(long catalogVersion, String mountId, String moduleId) {
    }

    public record CatalogDeployment(long catalogVersion, boolean allowNewMatches, boolean allowExistingMatches) {
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

    public List<CatalogItemDto> findItems(long catalogVersion) {
        return query(
            """
                SELECT item_id, catalog_version, item_type, display_name, is_enabled
                FROM catalog_items
                WHERE catalog_version = ?
                ORDER BY item_type, item_id
                """,
            (rs, rowNum) -> new CatalogItemDto(
                rs.getString("item_id"),
                rs.getLong("catalog_version"),
                rs.getString("item_type"),
                rs.getString("display_name"),
                rs.getBoolean("is_enabled")
            ),
            catalogVersion
        );
    }

    public List<WeaponMountDto> findWeaponMounts(long catalogVersion) {
        return query(
            """
                SELECT mount_id, catalog_version, weapon_id, mount_type, mount_index, is_required, display_order
                FROM weapon_module_mounts
                WHERE catalog_version = ?
                ORDER BY weapon_id, display_order, mount_id
                """,
            (rs, rowNum) -> new WeaponMountDto(
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

    public List<AllowedModuleDto> findAllowedModules(long catalogVersion) {
        return query(
            """
                SELECT mount_id, module_id, catalog_version
                FROM weapon_mount_allowed_modules
                WHERE catalog_version = ?
                ORDER BY mount_id, module_id
                """,
            (rs, rowNum) -> new AllowedModuleDto(
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
}
