package com.game.backend.catalog.repository;

import com.game.backend.catalog.api.AllowedModuleDto;
import com.game.backend.catalog.api.CatalogItemDto;
import com.game.backend.catalog.api.WeaponMountDto;
import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class CatalogRepository extends JdbcRepository {
    public record WeaponSlotRule(String classTag, String weaponSlotId, boolean allowed) {
    }

    public record MountAllowedModule(long catalogVersion, String mountId, String moduleId) {
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
}
