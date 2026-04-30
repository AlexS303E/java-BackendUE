package com.game.backend.presets.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чистит сохраненные loadout presets, если предмет стал недоступен игроку.
 */
@Service
public class LoadoutSanitizationService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxService outboxService;
    private final PlayerNotificationService playerNotificationService;

    public LoadoutSanitizationService(
        JdbcTemplate jdbcTemplate,
        OutboxService outboxService,
        PlayerNotificationService playerNotificationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
        this.playerNotificationService = playerNotificationService;
    }

    /**
     * Удаляет недоступный item из weapon/outfit presets и помечает затронутые presets как sanitized.
     */
    public LoadoutSanitizationResult sanitizeUnavailableItem(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        String itemType = itemType(itemId, catalogVersion);
        int weaponPresets = switch (itemType) {
            case "weapon" -> sanitizeWeapon(playerId, itemId, catalogVersion, source, sourceEventId, now);
            case "module" -> sanitizeModule(playerId, itemId, catalogVersion, source, sourceEventId, now);
            default -> 0;
        };
        int outfitPresets = "clothing".equals(itemType)
            ? sanitizeClothing(playerId, itemId, catalogVersion, source, sourceEventId, now)
            : 0;
        return new LoadoutSanitizationResult(weaponPresets, outfitPresets);
    }

    private String itemType(String itemId, long catalogVersion) {
        List<String> itemTypes = jdbcTemplate.queryForList(
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
        if (itemTypes.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CATALOG_ITEM_NOT_FOUND", "Catalog item was not found");
        }
        return itemTypes.getFirst();
    }

    private int sanitizeWeapon(
        UUID playerId,
        String weaponId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<WeaponPresetKey> presets = weaponPresetsUsingWeapon(playerId, weaponId, catalogVersion);
        for (WeaponPresetKey preset : presets) {
            jdbcTemplate.update(
                """
                    DELETE FROM player_weapon_preset_weapon_configs
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_id = ?
                    """,
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                weaponId
            );
            jdbcTemplate.update(
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
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                weaponId
            );
            long revision = markWeaponPresetSanitized(playerId, preset, now);
            recordWeaponPresetSanitized(playerId, preset, weaponId, "weapon", revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private int sanitizeModule(
        UUID playerId,
        String moduleId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<WeaponPresetKey> presets = weaponPresetsUsingModule(playerId, moduleId, catalogVersion);
        for (WeaponPresetKey preset : presets) {
            jdbcTemplate.update(
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
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                moduleId
            );
            jdbcTemplate.update(
                """
                    DELETE FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND module_id = ?
                    """,
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                moduleId
            );
            long revision = markWeaponPresetSanitized(playerId, preset, now);
            recordWeaponPresetSanitized(playerId, preset, moduleId, "module", revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private int sanitizeClothing(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<OutfitPresetKey> presets = outfitPresetsUsingItem(playerId, itemId, catalogVersion);
        for (OutfitPresetKey preset : presets) {
            jdbcTemplate.update(
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
                preset.teamTag(),
                preset.classTag(),
                preset.outfitPresetSlot(),
                catalogVersion,
                itemId
            );
            long revision = markOutfitPresetSanitized(playerId, preset, now);
            recordOutfitPresetSanitized(playerId, preset, itemId, revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private List<WeaponPresetKey> weaponPresetsUsingWeapon(UUID playerId, String weaponId, long catalogVersion) {
        return jdbcTemplate.query(
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

    private List<WeaponPresetKey> weaponPresetsUsingModule(UUID playerId, String moduleId, long catalogVersion) {
        return jdbcTemplate.query(
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

    private List<OutfitPresetKey> outfitPresetsUsingItem(UUID playerId, String itemId, long catalogVersion) {
        return jdbcTemplate.query(
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

    private long markWeaponPresetSanitized(UUID playerId, WeaponPresetKey preset, OffsetDateTime now) {
        return jdbcTemplate.queryForObject(
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

    private long markOutfitPresetSanitized(UUID playerId, OutfitPresetKey preset, OffsetDateTime now) {
        return jdbcTemplate.queryForObject(
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

    private void recordWeaponPresetSanitized(
        UUID playerId,
        WeaponPresetKey preset,
        String removedItemId,
        String removedItemType,
        long revision,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", playerId,
            "class_tag", preset.classTag(),
            "preset_slot", preset.presetSlot(),
            "catalog_version", preset.catalogVersion(),
            "revision", revision,
            "removed_item_id", removedItemId,
            "removed_item_type", removedItemType,
            "source", source,
            "source_event_id", sourceEventId
        );
        outboxService.record(
            "weapon_preset.sanitized",
            "weapon_preset",
            weaponPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            playerId,
            "weapon_preset.sanitized",
            "weapon_preset",
            weaponPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
    }

    private void recordOutfitPresetSanitized(
        UUID playerId,
        OutfitPresetKey preset,
        String removedItemId,
        long revision,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", playerId,
            "team_tag", preset.teamTag(),
            "class_tag", preset.classTag(),
            "outfit_preset_slot", preset.outfitPresetSlot(),
            "catalog_version", preset.catalogVersion(),
            "revision", revision,
            "removed_item_id", removedItemId,
            "removed_item_type", "clothing",
            "source", source,
            "source_event_id", sourceEventId
        );
        outboxService.record(
            "outfit_preset.sanitized",
            "outfit_preset",
            outfitPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            playerId,
            "outfit_preset.sanitized",
            "outfit_preset",
            outfitPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
    }

    private String weaponPresetAggregateId(UUID playerId, WeaponPresetKey preset) {
        return playerId + ":" + preset.classTag() + ":" + preset.presetSlot() + ":" + preset.catalogVersion();
    }

    private String outfitPresetAggregateId(UUID playerId, OutfitPresetKey preset) {
        return playerId + ":" + preset.teamTag() + ":" + preset.classTag() + ":" + preset.outfitPresetSlot() + ":" + preset.catalogVersion();
    }

    private record WeaponPresetKey(String classTag, int presetSlot, long catalogVersion) {
    }

    private record OutfitPresetKey(String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
    }
}
