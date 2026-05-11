package com.game.backend.presets.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.presets.api.ModuleSelectionDto;
import com.game.backend.presets.api.OutfitItemDto;
import com.game.backend.presets.api.OutfitPresetDto;
import com.game.backend.presets.api.PlayerPresetsResponse;
import com.game.backend.presets.api.SaveModuleRequest;
import com.game.backend.presets.api.SaveWeaponSlotRequest;
import com.game.backend.presets.api.WeaponPresetDto;
import com.game.backend.presets.api.WeaponPresetSaveRequest;
import com.game.backend.presets.api.WeaponPresetSaveResponse;
import com.game.backend.presets.api.WeaponSlotPresetDto;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Управляет чтением и сохранением player presets с проверкой каталога и доступа.
 */
@Service
public class PresetsService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxService outboxService;

    public PresetsService(JdbcTemplate jdbcTemplate, OutboxService outboxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
    }

    /**
     * Полностью сохраняет weapon preset, если If-Match совпал с текущей ревизией.
     */
    @Transactional
    public WeaponPresetSaveResponse saveWeaponPreset(
        UUID playerId,
        String classTag,
        int presetSlot,
        String ifMatch,
        WeaponPresetSaveRequest request
    ) {
        long expectedRevision = parseIfMatch(ifMatch);
        long catalogVersion = request.catalogVersion();
        // FOR UPDATE удерживает текущую ревизию до конца транзакции и защищает от гонок сохранения.
        PresetHeader current = lockWeaponPreset(playerId, classTag, presetSlot, catalogVersion);

        if (current.revision() != expectedRevision) {
            throw new ApiException(
                HttpStatus.PRECONDITION_FAILED,
                "PRECONDITION_FAILED",
                "Weapon preset revision mismatch"
            );
        }

        validateSaveRequest(playerId, classTag, request);
        OffsetDateTime now = OffsetDateTime.now();

        for (SaveWeaponSlotRequest slot : request.slots()) {
            upsertSelectedSlot(playerId, classTag, presetSlot, catalogVersion, slot);
            if (slot.weaponId() != null) {
                upsertWeaponConfig(playerId, classTag, presetSlot, catalogVersion, slot, now);
                replaceModulesForWeaponConfig(playerId, classTag, presetSlot, catalogVersion, slot);
            }
        }

        long newRevision = expectedRevision + 1;
        jdbcTemplate.update(
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
            newRevision,
            now,
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
        outboxService.record(
            "weapon_preset.saved",
            "weapon_preset",
            weaponPresetAggregateId(playerId, classTag, presetSlot, catalogVersion),
            1,
            Map.of(
                "player_id", playerId,
                "class_tag", classTag,
                "preset_slot", presetSlot,
                "catalog_version", catalogVersion,
                "previous_revision", expectedRevision,
                "revision", newRevision,
                "source", "player_save"
            ),
            now
        );

        return new WeaponPresetSaveResponse(
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            newRevision,
            weaponSlots(playerId, classTag, presetSlot, catalogVersion)
        );
    }

    private String weaponPresetAggregateId(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return playerId + ":" + classTag + ":" + presetSlot + ":" + catalogVersion;
    }

    /**
     * Возвращает все presets игрока для страницы loadout.
     * Использует batch-загрузку (5 queries) вместо N+1 (~31 queries).
     */
    public PlayerPresetsResponse getPlayerPresets(UUID playerId) {
        List<WeaponPresetDto> weaponPresets = loadWeaponPresetsBatch(playerId);
        List<OutfitPresetDto> outfitPresets = loadOutfitPresetsBatch(playerId);
        return new PlayerPresetsResponse(playerId, weaponPresets, outfitPresets);
    }

    private List<WeaponPresetDto> loadWeaponPresetsBatch(UUID playerId) {
        List<WeaponPresetDto> presets = jdbcTemplate.query(
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

        if (presets.isEmpty()) return presets;

        Map<WeaponPresetKey, List<WeaponSlotPresetDto>> slotsByPreset = new HashMap<>();
        for (WeaponPresetDto p : presets) {
            slotsByPreset.put(new WeaponPresetKey(p.classTag(), p.presetSlot(), p.catalogVersion()), p.slots());
        }

        Map<SlotAndWeaponKey, List<ModuleSelectionDto>> modulesBySlot = new HashMap<>();

        jdbcTemplate.query(
            """
                SELECT class_tag, preset_slot, catalog_version, weapon_slot_id, selected_weapon_id
                FROM player_weapon_preset_slots
                WHERE player_id = ?
                ORDER BY class_tag, preset_slot, weapon_slot_id
                """,
            (rs, rowNum) -> {
                String classTag = rs.getString("class_tag");
                int presetSlot = rs.getInt("preset_slot");
                long catalogVersion = rs.getLong("catalog_version");
                String weaponSlotId = rs.getString("weapon_slot_id");
                String selectedWeaponId = rs.getString("selected_weapon_id");
                WeaponPresetKey pk = new WeaponPresetKey(classTag, presetSlot, catalogVersion);
                List<WeaponSlotPresetDto> slots = slotsByPreset.get(pk);
                List<ModuleSelectionDto> slotModules = new ArrayList<>();
                WeaponSlotPresetDto slot = new WeaponSlotPresetDto(weaponSlotId, selectedWeaponId, slotModules);
                if (slots != null) slots.add(slot);
                if (selectedWeaponId != null) {
                    modulesBySlot.put(new SlotAndWeaponKey(classTag, presetSlot, catalogVersion, weaponSlotId, selectedWeaponId), slotModules);
                }
                return null;
            },
            playerId
        );

        if (!modulesBySlot.isEmpty()) {
            jdbcTemplate.query(
                """
                    SELECT class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id, mount_id, module_id
                    FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                    ORDER BY class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id, mount_id
                    """,
                (rs, rowNum) -> {
                    String classTag = rs.getString("class_tag");
                    int presetSlot = rs.getInt("preset_slot");
                    long catalogVersion = rs.getLong("catalog_version");
                    String weaponSlotId = rs.getString("weapon_slot_id");
                    String weaponId = rs.getString("weapon_id");
                    String mountId = rs.getString("mount_id");
                    String moduleId = rs.getString("module_id");
                    SlotAndWeaponKey sk = new SlotAndWeaponKey(classTag, presetSlot, catalogVersion, weaponSlotId, weaponId);
                    List<ModuleSelectionDto> slotModules = modulesBySlot.get(sk);
                    if (slotModules != null) {
                        slotModules.add(new ModuleSelectionDto(mountId, moduleId));
                    }
                    return null;
                },
                playerId
            );
        }

        return presets;
    }

    private List<OutfitPresetDto> loadOutfitPresetsBatch(UUID playerId) {
        List<OutfitPresetDto> presets = jdbcTemplate.query(
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

        if (presets.isEmpty()) return presets;

        Map<OutfitPresetKey, List<OutfitItemDto>> itemsByPreset = new HashMap<>();
        for (OutfitPresetDto p : presets) {
            itemsByPreset.put(new OutfitPresetKey(p.teamTag(), p.classTag(), p.outfitPresetSlot(), p.catalogVersion()), p.items());
        }

        jdbcTemplate.query(
            """
                SELECT team_tag, class_tag, outfit_preset_slot, catalog_version, clothing_slot_id, item_id
                FROM player_outfit_preset_items
                WHERE player_id = ?
                ORDER BY team_tag, class_tag, outfit_preset_slot, clothing_slot_id
                """,
            (rs, rowNum) -> {
                OutfitPresetKey pk = new OutfitPresetKey(
                    rs.getString("team_tag"),
                    rs.getString("class_tag"),
                    rs.getInt("outfit_preset_slot"),
                    rs.getLong("catalog_version")
                );
                List<OutfitItemDto> items = itemsByPreset.get(pk);
                if (items != null) {
                    items.add(new OutfitItemDto(rs.getString("clothing_slot_id"), rs.getString("item_id")));
                }
                return null;
            },
            playerId
        );

        return presets;
    }

    private record WeaponPresetKey(String classTag, int presetSlot, long catalogVersion) {}
    private record SlotAndWeaponKey(String classTag, int presetSlot, long catalogVersion, String weaponSlotId, String weaponId) {}
    private record OutfitPresetKey(String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {}

    /**
     * Читает weapon presets вместе с выбранными slots/modules (оригинальный N+1 метод, сохранён для обратной совместимости).
     */
    public List<WeaponPresetDto> weaponPresets(UUID playerId) {
        return loadWeaponPresetsBatch(playerId);
    }

    /**
     * Читает outfit presets вместе с выбранными clothing slots (оригинальный N+1 метод, сохранён для обратной совместимости).
     */
    public List<OutfitPresetDto> outfitPresets(UUID playerId) {
        return loadOutfitPresetsBatch(playerId);
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

    /**
     * Парсит HTTP If-Match как ожидаемую ревизию preset.
     */
    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApiException(
                HttpStatus.PRECONDITION_REQUIRED,
                "PRECONDITION_REQUIRED",
                "If-Match header is required for weapon preset save"
            );
        }

        String revision = ifMatch.trim();
        if (revision.startsWith("W/")) {
            revision = revision.substring(2).trim();
        }
        if (revision.length() >= 2 && revision.startsWith("\"") && revision.endsWith("\"")) {
            revision = revision.substring(1, revision.length() - 1);
        }

        try {
            return Long.parseLong(revision);
        } catch (NumberFormatException exception) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "If-Match must contain the expected numeric preset revision"
            );
        }
    }

    private PresetHeader lockWeaponPreset(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        List<PresetHeader> presets = jdbcTemplate.query(
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
        if (presets.isEmpty()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WEAPON_PRESET_NOT_FOUND",
                "Weapon preset was not found for selected catalog version"
            );
        }
        return presets.getFirst();
    }

    /**
     * Валидирует структуру save-запроса, доступность предметов и совместимость модулей с mount.
     */
    private void validateSaveRequest(UUID playerId, String classTag, WeaponPresetSaveRequest request) {
        Set<String> weaponSlotIds = new HashSet<>();
        for (SaveWeaponSlotRequest slot : request.slots()) {
            if (!weaponSlotIds.add(slot.weaponSlotId())) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Duplicate weapon_slot_id in request: " + slot.weaponSlotId()
                );
            }

            validateWeaponSlotAllowed(classTag, slot.weaponSlotId());

            if (slot.weaponId() == null) {
                if (!slot.modules().isEmpty()) {
                    throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "LOADOUT_VALIDATION_FAILED",
                        "Empty weapon slot cannot contain modules: " + slot.weaponSlotId()
                    );
                }
                continue;
            }

            validateCanUse(playerId, slot.weaponId(), request.catalogVersion(), classTag, "weapon");
            Set<String> mountIds = new HashSet<>();
            for (SaveModuleRequest module : slot.modules()) {
                if (!mountIds.add(module.mountId())) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "Duplicate mount_id in request: " + module.mountId()
                    );
                }
                validateCanUse(playerId, module.moduleId(), request.catalogVersion(), classTag, "module");
                validateMountModuleAllowed(request.catalogVersion(), slot.weaponId(), module.mountId(), module.moduleId());
            }
        }
    }

    private void validateWeaponSlotAllowed(String classTag, String weaponSlotId) {
        Boolean allowed = jdbcTemplate.queryForObject(
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
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Weapon slot is not allowed for class: " + weaponSlotId
            );
        }
    }

    private void validateCanUse(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        Boolean canUse = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items ci
                  JOIN player_item_access pia
                    ON pia.item_id = ci.item_id
                   AND pia.catalog_version = ci.catalog_version
                   AND pia.player_id = ?
                  WHERE ci.item_id = ?
                    AND ci.catalog_version = ?
                    AND ci.item_type = ?
                    AND ci.is_enabled = true
                    AND pia.is_hidden = false
                    AND pia.is_locked_in_shop = false
                    AND pia.is_locked_by_quest = false
                    AND pia.is_disabled = false
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
            playerId,
            itemId,
            catalogVersion,
            itemType,
            classTag,
            classTag
        );
        if (!Boolean.TRUE.equals(canUse)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Item is not usable in preset: " + itemId
            );
        }
    }

    private void validateMountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        Boolean allowed = jdbcTemplate.queryForObject(
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
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Module is not allowed for weapon mount: " + moduleId
            );
        }
    }

    private void upsertSelectedSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot
    ) {
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

    private void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot,
        OffsetDateTime now
    ) {
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

    /**
     * Заменяет набор модулей целиком, чтобы сохранить запрос идемпотентным по содержимому slot.
     */
    private void replaceModulesForWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot
    ) {
        jdbcTemplate.update(
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
            slot.weaponSlotId(),
            slot.weaponId()
        );

        for (SaveModuleRequest module : slot.modules()) {
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
                classTag,
                presetSlot,
                catalogVersion,
                slot.weaponSlotId(),
                slot.weaponId(),
                module.mountId(),
                module.moduleId()
            );
        }
    }

    private record PresetHeader(long revision, boolean sanitized) {
    }
}
