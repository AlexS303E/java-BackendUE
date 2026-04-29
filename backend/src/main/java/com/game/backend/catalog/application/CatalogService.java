package com.game.backend.catalog.application;

import com.game.backend.catalog.api.AllowedModuleDto;
import com.game.backend.catalog.api.CatalogItemDto;
import com.game.backend.catalog.api.CatalogSnapshotResponse;
import com.game.backend.catalog.api.WeaponMountDto;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Сервис чтения каталога и выбора активной версии для матчей.
 */
@Service
public class CatalogService {
    private final JdbcTemplate jdbcTemplate;

    public CatalogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Собирает клиентский snapshot активной версии каталога для realm.
     */
    public CatalogSnapshotResponse getSnapshot(String realmId) {
        long catalogVersion = activeCatalogVersion(realmId);
        return new CatalogSnapshotResponse(
            realmId,
            catalogVersion,
            items(catalogVersion),
            weaponMounts(catalogVersion),
            allowedModules(catalogVersion)
        );
    }

    /**
     * Находит активную версию каталога, разрешенную для новых матчей.
     */
    public long activeCatalogVersion(String realmId) {
        List<Long> versions = jdbcTemplate.queryForList(
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
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOG_VERSION_NOT_SUPPORTED", "No active catalog for realm " + realmId);
        }
        return versions.getFirst();
    }

    /**
     * Проверяет, может ли DS использовать указанную версию каталога для нового матча.
     */
    public boolean catalogVersionAllowsNewMatches(String realmId, long catalogVersion) {
        Boolean exists = jdbcTemplate.queryForObject(
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

    private List<CatalogItemDto> items(long catalogVersion) {
        return jdbcTemplate.query(
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

    private List<WeaponMountDto> weaponMounts(long catalogVersion) {
        return jdbcTemplate.query(
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

    private List<AllowedModuleDto> allowedModules(long catalogVersion) {
        return jdbcTemplate.query(
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
}
