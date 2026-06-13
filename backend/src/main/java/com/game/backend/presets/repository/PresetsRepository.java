package com.game.backend.presets.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class PresetsRepository extends JdbcRepository {
    public record WeaponPresetKey(String classTag, int presetSlot, long catalogVersion) {
    }

    public record OutfitPresetKey(String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
    }

    public PresetsRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<String> findItemTypes(String itemId, long catalogVersion) {
        return queryForList(
            """
                SELECT item_type
                FROM catalog_items
                WHERE item_id = ?
                  AND catalog_version = ?
                """,
            String.class,
            itemId,
            catalogVersion
        );
    }

    public boolean isWeaponSlotAllowed(String classTag, String weaponSlotId) {
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

    public boolean isSelectedWeapon(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        Boolean matches = queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM player_weapon_preset_slots
                  WHERE player_id = ?
                    AND class_tag = ?
                    AND preset_slot = ?
                    AND catalog_version = ?
                    AND weapon_slot_id = ?
                    AND selected_weapon_id = ?
                )
                """,
            Boolean.class,
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
        return Boolean.TRUE.equals(matches);
    }

    public boolean isMountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
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

    public List<WeaponPresetKey> lockWeaponPresetsUsingWeapon(UUID playerId, String weaponId, long catalogVersion) {
        return query(
            """
                SELECT wp.class_tag, wp.preset_slot, wp.catalog_version
                FROM player_weapon_presets wp
                WHERE wp.player_id = ?
                  AND wp.catalog_version = ?
                  AND EXISTS (
                    SELECT 1
                    FROM player_weapon_preset_slots slot
                    WHERE slot.player_id = wp.player_id
                      AND slot.class_tag = wp.class_tag
                      AND slot.preset_slot = wp.preset_slot
                      AND slot.catalog_version = wp.catalog_version
                      AND slot.selected_weapon_id = ?
                  )
                FOR UPDATE OF wp
                """,
            (rs, rowNum) -> new WeaponPresetKey(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version")
            ),
            playerId,
            catalogVersion,
            weaponId
        );
    }

    public List<WeaponPresetKey> lockWeaponPresetsUsingModule(UUID playerId, String moduleId, long catalogVersion) {
        return query(
            """
                SELECT wp.class_tag, wp.preset_slot, wp.catalog_version
                FROM player_weapon_presets wp
                WHERE wp.player_id = ?
                  AND wp.catalog_version = ?
                  AND EXISTS (
                    SELECT 1
                    FROM player_weapon_preset_weapon_config_modules mod
                    WHERE mod.player_id = wp.player_id
                      AND mod.class_tag = wp.class_tag
                      AND mod.preset_slot = wp.preset_slot
                      AND mod.catalog_version = wp.catalog_version
                      AND mod.module_id = ?
                  )
                FOR UPDATE OF wp
                """,
            (rs, rowNum) -> new WeaponPresetKey(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version")
            ),
            playerId,
            catalogVersion,
            moduleId
        );
    }

    public List<OutfitPresetKey> lockOutfitPresetsUsingItem(UUID playerId, String itemId, long catalogVersion) {
        return query(
            """
                SELECT op.team_tag, op.class_tag, op.outfit_preset_slot, op.catalog_version
                FROM player_outfit_presets op
                WHERE op.player_id = ?
                  AND op.catalog_version = ?
                  AND EXISTS (
                    SELECT 1
                    FROM player_outfit_preset_items item
                    WHERE item.player_id = op.player_id
                      AND item.team_tag = op.team_tag
                      AND item.class_tag = op.class_tag
                      AND item.outfit_preset_slot = op.outfit_preset_slot
                      AND item.catalog_version = op.catalog_version
                      AND item.item_id = ?
                  )
                FOR UPDATE OF op
                """,
            (rs, rowNum) -> new OutfitPresetKey(
                rs.getString("team_tag"),
                rs.getString("class_tag"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("catalog_version")
            ),
            playerId,
            catalogVersion,
            itemId
        );
    }

    public void deleteWeaponConfigsForWeapon(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponId
    ) {
        update(
            """
                DELETE FROM player_weapon_preset_weapon_configs
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_id = ?
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponId
        );
    }

    public void clearSelectedWeapon(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponId
    ) {
        update(
            """
                UPDATE player_weapon_preset_slots
                SET selected_weapon_id = null
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND selected_weapon_id = ?
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponId
        );
    }

    public void bumpWeaponConfigRevisionForModuleRemoval(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String moduleId,
        OffsetDateTime now
    ) {
        update(
            """
                UPDATE player_weapon_preset_weapon_configs cfg
                SET config_revision = cfg.config_revision + 1,
                    last_used_at = ?
                WHERE cfg.player_id = ?
                  AND cfg.class_tag = ?
                  AND cfg.preset_slot = ?
                  AND cfg.catalog_version = ?
                  AND EXISTS (
                    SELECT 1
                    FROM player_weapon_preset_weapon_config_modules mod
                    WHERE mod.player_id = cfg.player_id
                      AND mod.class_tag = cfg.class_tag
                      AND mod.preset_slot = cfg.preset_slot
                      AND mod.catalog_version = cfg.catalog_version
                      AND mod.weapon_slot_id = cfg.weapon_slot_id
                      AND mod.weapon_id = cfg.weapon_id
                      AND mod.module_id = ?
                  )
                """,
            now,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            moduleId
        );
    }

    public void deleteWeaponConfigModulesByModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String moduleId
    ) {
        update(
            """
                DELETE FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND module_id = ?
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            moduleId
        );
    }

    public void deleteOutfitPresetItem(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        String itemId
    ) {
        update(
            """
                DELETE FROM player_outfit_preset_items
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                  AND item_id = ?
                """,
            playerId,
            teamTag,
            classTag,
            outfitPresetSlot,
            catalogVersion,
            itemId
        );
    }

    public long markWeaponPresetSanitized(UUID playerId, WeaponPresetKey preset, OffsetDateTime now) {
        return queryForObject(
            """
                UPDATE player_weapon_presets
                SET revision = revision + 1,
                    sanitized = true,
                    updated_at = ?
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                RETURNING revision
                """,
            Long.class,
            now,
            playerId,
            preset.classTag(),
            preset.presetSlot(),
            preset.catalogVersion()
        );
    }

    public long markOutfitPresetSanitized(UUID playerId, OutfitPresetKey preset, OffsetDateTime now) {
        return queryForObject(
            """
                UPDATE player_outfit_presets
                SET revision = revision + 1,
                    sanitized = true,
                    updated_at = ?
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                RETURNING revision
                """,
            Long.class,
            now,
            playerId,
            preset.teamTag(),
            preset.classTag(),
            preset.outfitPresetSlot(),
            preset.catalogVersion()
        );
    }
}
