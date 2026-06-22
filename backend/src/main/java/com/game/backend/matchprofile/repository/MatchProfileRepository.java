package com.game.backend.matchprofile.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class MatchProfileRepository extends JdbcRepository {
    public MatchProfileRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<String> findFreshPayload(
        UUID playerId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        return queryForList(
            """
                SELECT payload
                FROM player_match_profiles
                WHERE player_id = ?
                  AND realm_id = ?
                  AND class_tag = ?
                  AND team_tag = ?
                  AND weapon_preset_slot = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_preset_revision = ?
                  AND outfit_preset_revision = ?
                  AND access_revision = ?
                  AND is_stale = false
                  AND expires_at > NOW()
                LIMIT 1
                """,
            String.class,
            playerId,
            realmId,
            classTag,
            teamTag,
            weaponPresetSlot,
            outfitPresetSlot,
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        );
    }

    public void saveProfile(
        UUID profileId,
        UUID playerId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision,
        long catalogVersion,
        long profileRevision,
        String payload,
        OffsetDateTime generatedAt,
        OffsetDateTime expiresAt
    ) {
        update(
            """
                INSERT INTO player_match_profiles(
                  profile_id,
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version,
                  profile_revision,
                  payload,
                  payload_schema_version,
                  is_stale,
                  generated_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1, false, ?, ?)
                ON CONFLICT (
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version
                )
                DO UPDATE SET
                  profile_revision = EXCLUDED.profile_revision,
                  payload = EXCLUDED.payload,
                  payload_schema_version = EXCLUDED.payload_schema_version,
                  is_stale = false,
                  stale_reason = null,
                  stale_at = null,
                  generated_at = EXCLUDED.generated_at,
                  expires_at = EXCLUDED.expires_at
                """,
            profileId,
            playerId,
            realmId,
            classTag,
            teamTag,
            weaponPresetSlot,
            outfitPresetSlot,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision,
            catalogVersion,
            profileRevision,
            payload,
            generatedAt,
            expiresAt
        );
    }

    public List<DependencyRow> loadDependencies(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        String teamTag,
        int outfitPresetSlot,
        long catalogVersion
    ) {
        return query(
            """
                SELECT wp.revision AS weapon_preset_revision,
                       wp.sanitized AS weapon_preset_sanitized,
                       pas.access_revision,
                       op.revision AS outfit_preset_revision,
                       op.sanitized AS outfit_preset_sanitized
                FROM (SELECT 1) seed
                LEFT JOIN player_weapon_presets wp
                  ON wp.player_id = ?
                 AND wp.class_tag = ?
                 AND wp.preset_slot = ?
                 AND wp.catalog_version = ?
                LEFT JOIN player_access_projection_state pas
                  ON pas.player_id = ?
                LEFT JOIN player_outfit_presets op
                  ON op.player_id = ?
                 AND op.team_tag = ?
                 AND op.class_tag = ?
                 AND op.outfit_preset_slot = ?
                 AND op.catalog_version = ?
                """,
            (rs, rowNum) -> new DependencyRow(
                rs.getObject("weapon_preset_revision", Long.class),
                rs.getObject("weapon_preset_sanitized", Boolean.class),
                rs.getObject("access_revision", Long.class),
                rs.getObject("outfit_preset_revision", Long.class),
                rs.getObject("outfit_preset_sanitized", Boolean.class)
            ),
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            playerId,
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion
        );
    }

    public boolean enforceTeamItemRules(String gameModeId) {
        List<Boolean> results = queryForList(
            "SELECT enforce_team_item_rules FROM game_mode_rules WHERE game_mode_id = ?",
            Boolean.class,
            gameModeId
        );
        return !results.isEmpty() && Boolean.TRUE.equals(results.getFirst());
    }

    public List<WeaponRow> findWeaponRows(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return query(
            """
                SELECT ws.weapon_slot_id, ws.selected_weapon_id,
                       wcm.mount_id, wcm.module_id
                FROM player_weapon_preset_slots ws
                LEFT JOIN player_weapon_preset_weapon_config_modules wcm
                  ON  wcm.player_id = ws.player_id
                  AND wcm.class_tag = ws.class_tag
                  AND wcm.preset_slot = ws.preset_slot
                  AND wcm.catalog_version = ws.catalog_version
                  AND wcm.weapon_slot_id = ws.weapon_slot_id
                  AND wcm.weapon_id = ws.selected_weapon_id
                WHERE ws.player_id = ?
                  AND ws.class_tag = ?
                  AND ws.preset_slot = ?
                  AND ws.catalog_version = ?
                ORDER BY ws.weapon_slot_id, wcm.mount_id
                """,
            (rs, rowNum) -> new WeaponRow(
                rs.getString("weapon_slot_id"),
                rs.getString("selected_weapon_id"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            ),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public List<OutfitRow> findOutfitRows(
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
            (rs, rowNum) -> new OutfitRow(
                rs.getString("clothing_slot_id"),
                rs.getString("item_id")
            ),
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion
        );
    }

    public Set<String> findTeamCompliantItems(long catalogVersion, Set<String> itemIds, String teamTag) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        // item_team_rules is an allowlist: no rows means any team may use the item.
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[2 + itemIds.size()];
        params[0] = catalogVersion;
        int i = 1;
        for (String id : itemIds) {
            params[i++] = id;
        }
        params[i] = teamTag;
        return Set.copyOf(queryForList(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                WHERE ci.catalog_version = ?
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND (
                    EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                        AND itr.team_scope = 'all'
                    )
                    OR EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                        AND itr.team_scope = 'specific'
                        AND itr.team_tag = ?
                    )
                    OR NOT EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                    )
                  )
                """,
            String.class,
            params
        ));
    }

    public Set<String> findOutfitTeamCompliantItems(long catalogVersion, Set<String> itemIds, String teamTag) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        // outfit_item_team_rules follows the same allowlist semantics as item_team_rules.
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[2 + itemIds.size()];
        params[0] = catalogVersion;
        int i = 1;
        for (String id : itemIds) {
            params[i++] = id;
        }
        params[i] = teamTag;
        return Set.copyOf(queryForList(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                WHERE ci.catalog_version = ?
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND (
                    EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                        AND oitr.team_scope = 'all'
                    )
                    OR EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                        AND oitr.team_scope = 'specific'
                        AND oitr.team_tag = ?
                    )
                    OR NOT EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                    )
                  )
                """,
            String.class,
            params
        ));
    }

    public List<StaleProfile> staleProfilesForPlayerAccessChange(
        UUID playerId,
        long catalogVersion,
        String staleReason,
        OffsetDateTime staleAt
    ) {
        return query(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE player_id = ?
                  AND catalog_version = ?
                  AND is_stale = false
                RETURNING profile_id, realm_id, class_tag, team_tag, weapon_preset_slot, outfit_preset_slot, profile_revision
                """,
            (rs, rowNum) -> new StaleProfile(
                rs.getObject("profile_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("class_tag"),
                rs.getString("team_tag"),
                rs.getInt("weapon_preset_slot"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("profile_revision")
            ),
            staleReason,
            staleAt,
            playerId,
            catalogVersion
        );
    }

    public List<UUID> staleProfileIdsForPlayer(UUID playerId, String staleReason, OffsetDateTime staleAt) {
        return queryForList(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE player_id = ?
                  AND is_stale = false
                RETURNING profile_id
                """,
            UUID.class,
            staleReason,
            staleAt,
            playerId
        );
    }

    public record DependencyRow(
        Long weaponPresetRevision,
        Boolean weaponPresetSanitized,
        Long accessRevision,
        Long outfitPresetRevision,
        Boolean outfitPresetSanitized
    ) {
    }

    public record WeaponRow(
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
    }

    public record OutfitRow(String clothingSlotId, String itemId) {
    }

    public record StaleProfile(
        UUID profileId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long profileRevision
    ) {
    }
}
