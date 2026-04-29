package com.game.backend.presets.application;

import com.game.backend.presets.api.ModuleSelectionDto;
import com.game.backend.presets.api.OutfitItemDto;
import com.game.backend.presets.api.OutfitPresetDto;
import com.game.backend.presets.api.PlayerPresetsResponse;
import com.game.backend.presets.api.WeaponPresetDto;
import com.game.backend.presets.api.WeaponSlotPresetDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class PresetsService {
    private final JdbcTemplate jdbcTemplate;

    public PresetsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PlayerPresetsResponse getPlayerPresets(UUID playerId) {
        return new PlayerPresetsResponse(
            playerId,
            weaponPresets(playerId),
            outfitPresets(playerId)
        );
    }

    public List<WeaponPresetDto> weaponPresets(UUID playerId) {
        return jdbcTemplate.query(
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
                weaponSlots(
                    playerId,
                    rs.getString("class_tag"),
                    rs.getInt("preset_slot"),
                    rs.getLong("catalog_version")
                )
            ),
            playerId
        );
    }

    public List<OutfitPresetDto> outfitPresets(UUID playerId) {
        return jdbcTemplate.query(
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
                outfitItems(
                    playerId,
                    rs.getString("team_tag"),
                    rs.getString("class_tag"),
                    rs.getInt("outfit_preset_slot"),
                    rs.getLong("catalog_version")
                )
            ),
            playerId
        );
    }

    public List<WeaponSlotPresetDto> weaponSlots(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
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
            (rs, rowNum) -> {
                String weaponSlotId = rs.getString("weapon_slot_id");
                String selectedWeaponId = rs.getString("selected_weapon_id");
                return new WeaponSlotPresetDto(
                    weaponSlotId,
                    selectedWeaponId,
                    selectedWeaponId == null
                        ? List.of()
                        : modules(playerId, classTag, presetSlot, catalogVersion, weaponSlotId, selectedWeaponId)
                );
            },
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public List<ModuleSelectionDto> modules(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
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

    public List<OutfitItemDto> outfitItems(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
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
}
