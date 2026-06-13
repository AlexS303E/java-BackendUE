package com.game.backend.presets.repository;

import com.game.backend.common.persistence.JdbcRepository;
import com.game.backend.presets.api.ModuleSelectionDto;
import com.game.backend.presets.api.OutfitItemDto;
import com.game.backend.presets.api.OutfitPresetDto;
import com.game.backend.presets.api.SaveModuleRequest;
import com.game.backend.presets.api.SaveWeaponSlotRequest;
import com.game.backend.presets.api.WeaponPresetDto;
import com.game.backend.presets.api.WeaponSlotPresetDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class PresetsRepository extends JdbcRepository {
    public record WeaponPresetKey(String classTag, int presetSlot, long catalogVersion) {
    }

    public record OutfitPresetKey(String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
    }

    public record SlotAndWeaponKey(
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
    }

    public record PresetHeader(long revision, boolean sanitized) {
    }

    public record WeaponPresetSlotRow(
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String selectedWeaponId
    ) {
    }

    public record WeaponConfigModuleRow(
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
    }

    public record OutfitPresetItemRow(
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        String clothingSlotId,
        String itemId
    ) {
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

    public List<WeaponPresetDto> findWeaponPresets(UUID playerId) {
        return query(
            """
                SELECT class_tag, preset_slot, catalog_version, revision, sanitized
                FROM player_weapon_presets
                WHERE player_id = ?
                ORDER BY class_tag, preset_slot
                """,
            (rs, rowNum) -> new WeaponPresetDto(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version"),
                rs.getLong("revision"),
                rs.getBoolean("sanitized"),
                new ArrayList<>()
            ),
            playerId
        );
    }

    public List<WeaponPresetSlotRow> findWeaponPresetSlotRows(UUID playerId) {
        return query(
            """
                SELECT class_tag, preset_slot, catalog_version, weapon_slot_id, selected_weapon_id
                FROM player_weapon_preset_slots
                WHERE player_id = ?
                ORDER BY class_tag, preset_slot, weapon_slot_id
                """,
            (rs, rowNum) -> new WeaponPresetSlotRow(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version"),
                rs.getString("weapon_slot_id"),
                rs.getString("selected_weapon_id")
            ),
            playerId
        );
    }

    public List<WeaponConfigModuleRow> findWeaponConfigModuleRows(UUID playerId) {
        return query(
            """
                SELECT class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id, mount_id, module_id
                FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                ORDER BY class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id, mount_id
                """,
            (rs, rowNum) -> new WeaponConfigModuleRow(
                rs.getString("class_tag"),
                rs.getInt("preset_slot"),
                rs.getLong("catalog_version"),
                rs.getString("weapon_slot_id"),
                rs.getString("weapon_id"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            ),
            playerId
        );
    }

    public List<OutfitPresetDto> findOutfitPresets(UUID playerId) {
        return query(
            """
                SELECT team_tag, class_tag, outfit_preset_slot, catalog_version, revision, sanitized
                FROM player_outfit_presets
                WHERE player_id = ?
                ORDER BY team_tag, class_tag, outfit_preset_slot
                """,
            (rs, rowNum) -> new OutfitPresetDto(
                rs.getString("team_tag"),
                rs.getString("class_tag"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("catalog_version"),
                rs.getLong("revision"),
                rs.getBoolean("sanitized"),
                new ArrayList<>()
            ),
            playerId
        );
    }

    public List<OutfitPresetItemRow> findOutfitPresetItemRows(UUID playerId) {
        return query(
            """
                SELECT team_tag, class_tag, outfit_preset_slot, catalog_version, clothing_slot_id, item_id
                FROM player_outfit_preset_items
                WHERE player_id = ?
                ORDER BY team_tag, class_tag, outfit_preset_slot, clothing_slot_id
                """,
            (rs, rowNum) -> new OutfitPresetItemRow(
                rs.getString("team_tag"),
                rs.getString("class_tag"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("catalog_version"),
                rs.getString("clothing_slot_id"),
                rs.getString("item_id")
            ),
            playerId
        );
    }

    public List<WeaponSlotPresetDto> findWeaponSlots(
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
            (rs, rowNum) -> new WeaponSlotPresetDto(
                rs.getString("weapon_slot_id"),
                rs.getString("selected_weapon_id"),
                List.of()
            ),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public List<ModuleSelectionDto> findModules(
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
            (rs, rowNum) -> new ModuleSelectionDto(
                rs.getString("mount_id"),
                rs.getString("module_id")
            ),
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    public List<OutfitItemDto> findOutfitItems(
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
            (rs, rowNum) -> new OutfitItemDto(
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

    public List<PresetHeader> lockWeaponPreset(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return query(
            """
                SELECT revision, sanitized
                FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                FOR UPDATE
                """,
            (rs, rowNum) -> new PresetHeader(rs.getLong("revision"), rs.getBoolean("sanitized")),
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public void updateWeaponPresetRevision(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        OffsetDateTime now
    ) {
        update(
            """
                UPDATE player_weapon_presets
                SET revision = ?,
                    sanitized = false,
                    updated_at = ?
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                """,
            revision,
            now,
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public void upsertSelectedSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot
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
                ON CONFLICT (player_id, class_tag, preset_slot, catalog_version, weapon_slot_id)
                DO UPDATE SET selected_weapon_id = EXCLUDED.selected_weapon_id
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            slot.weaponSlotId(),
            slot.weaponId()
        );
    }

    public void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot,
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
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id)
                DO UPDATE SET
                  config_revision = player_weapon_preset_weapon_configs.config_revision + 1,
                  last_used_at = EXCLUDED.last_used_at
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            slot.weaponSlotId(),
            slot.weaponId(),
            now
        );
    }

    public void deleteWeaponConfigModules(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        update(
            """
                DELETE FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_slot_id = ?
                  AND weapon_id = ?
                """,
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    public void insertWeaponConfigModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        SaveModuleRequest module
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
            module.mountId(),
            module.moduleId()
        );
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
