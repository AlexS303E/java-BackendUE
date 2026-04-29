package com.game.backend.auth.application;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PlayerBootstrapService {
    private static final String DEFAULT_REALM_ID = "global";
    private static final String DEFAULT_CLASS_TAG = "class.assault";
    private static final int DEFAULT_PRESET_SLOT = 1;
    private static final int DEFAULT_OUTFIT_PRESET_SLOT = 1;
    private static final String DEFAULT_WEAPON_SLOT_ID = "primary";
    private static final String DEFAULT_WEAPON_ID = "weapon.ak12";
    private static final String DEFAULT_MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String DEFAULT_MODULE_ID = "module.scope.red_dot_01";

    private final JdbcTemplate jdbcTemplate;

    public PlayerBootstrapService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void bootstrapNewPlayer(UUID playerId, OffsetDateTime now) {
        long catalogVersion = activeCatalogVersion();
        bootstrapAccess(playerId, catalogVersion, now);
        bootstrapWeaponPreset(playerId, catalogVersion, now);
        bootstrapOutfitPresets(playerId, catalogVersion, now);
    }

    private long activeCatalogVersion() {
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
            DEFAULT_REALM_ID
        );
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVE_CATALOG_NOT_FOUND", "No active catalog deployment found");
        }
        return versions.getFirst();
    }

    private void bootstrapAccess(UUID playerId, long catalogVersion, OffsetDateTime now) {
        jdbcTemplate.update(
            """
                INSERT INTO player_access_projection_state(
                  player_id,
                  access_revision,
                  projection_rebuilt_at
                )
                VALUES (?, 1, ?)
                """,
            playerId,
            now
        );

        List<String> itemIds = jdbcTemplate.queryForList(
            "SELECT item_id FROM catalog_items WHERE catalog_version = ? AND is_enabled = true ORDER BY item_id",
            String.class,
            catalogVersion
        );

        for (String itemId : itemIds) {
            jdbcTemplate.update(
                """
                    INSERT INTO entitlement_ledger(
                      ledger_event_id,
                      player_id,
                      item_id,
                      catalog_version,
                      event_type,
                      source_type,
                      actor_type,
                      actor_id,
                      idempotency_key,
                      created_at
                    )
                    VALUES (?, ?, ?, ?, 'reveal_item', 'default', 'system', 'bootstrap', ?, ?)
                    """,
                UUID.randomUUID(),
                playerId,
                itemId,
                catalogVersion,
                "bootstrap:" + catalogVersion + ":" + itemId,
                now
            );
        }

        jdbcTemplate.update(
            """
                INSERT INTO player_item_access(
                  player_id,
                  item_id,
                  catalog_version,
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  updated_at
                )
                SELECT
                  ?,
                  item_id,
                  catalog_version,
                  false,
                  false,
                  false,
                  false,
                  ?
                FROM catalog_items
                WHERE catalog_version = ?
                  AND is_enabled = true
                """,
            playerId,
            now,
            catalogVersion
        );
    }

    private void bootstrapWeaponPreset(UUID playerId, long catalogVersion, OffsetDateTime now) {
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
                VALUES (?, ?, ?, ?, 1, false, ?)
                """,
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            now
        );

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
                SELECT
                  ?,
                  ?,
                  ?,
                  ?,
                  weapon_slot_id,
                  CASE WHEN weapon_slot_id = ? THEN ? ELSE NULL END
                FROM class_weapon_slot_rules
                WHERE class_tag = ?
                  AND is_allowed = true
                """,
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID,
            DEFAULT_CLASS_TAG
        );

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
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                """,
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID,
            now
        );

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
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID,
            DEFAULT_MOUNT_ID,
            DEFAULT_MODULE_ID
        );
    }

    private void bootstrapOutfitPresets(UUID playerId, long catalogVersion, OffsetDateTime now) {
        List<String> teamTags = jdbcTemplate.queryForList(
            "SELECT team_tag FROM outfit_preset_rules WHERE class_tag = ? ORDER BY team_tag",
            String.class,
            DEFAULT_CLASS_TAG
        );

        for (String teamTag : teamTags) {
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
                    VALUES (?, ?, ?, ?, ?, 1, false, ?)
                    """,
                playerId,
                teamTag,
                DEFAULT_CLASS_TAG,
                DEFAULT_OUTFIT_PRESET_SLOT,
                catalogVersion,
                now
            );

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
                    VALUES (?, ?, ?, ?, ?, 'torso', ?)
                    """,
                playerId,
                teamTag,
                DEFAULT_CLASS_TAG,
                DEFAULT_OUTFIT_PRESET_SLOT,
                catalogVersion,
                defaultJacketForTeam(teamTag)
            );
        }
    }

    private String defaultJacketForTeam(String teamTag) {
        return switch (teamTag) {
            case "team.red" -> "clothing.team_red.jacket_01";
            case "team.blue" -> "clothing.team_blue.jacket_01";
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEFAULT_OUTFIT_NOT_CONFIGURED", "No default outfit configured for " + teamTag);
        };
    }
}
