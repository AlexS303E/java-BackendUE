package com.game.backend.runtimechanges.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.presets.application.LoadoutValidationService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Применяет атомарные runtime changes к durable weapon preset.
 */
@Service
public class WeaponPresetRuntimeChangeApplier {
    private final JdbcTemplate jdbcTemplate;
    private final LoadoutValidationService loadoutValidationService;

    public WeaponPresetRuntimeChangeApplier(
        JdbcTemplate jdbcTemplate,
        LoadoutValidationService loadoutValidationService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.loadoutValidationService = loadoutValidationService;
    }

    /**
     * Применяет список изменений в рамках уже открытой транзакции вызывающего сервиса.
     */
    public void apply(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangePayload payload,
        OffsetDateTime now
    ) {
        for (RuntimePresetChangeStep change : payload.changes()) {
            applyChange(playerId, classTag, weaponPresetSlot, catalogVersion, change, now);
        }
    }

    private void applyChange(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        switch (change.op()) {
            case "set_weapon" -> setWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, change, now);
            case "clear_weapon" -> clearWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, change);
            case "set_module" -> setModule(playerId, classTag, weaponPresetSlot, catalogVersion, change, now);
            case "clear_module" -> clearModule(playerId, classTag, weaponPresetSlot, catalogVersion, change);
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported runtime preset change op: " + change.op()
            );
        }
    }

    private void setWeapon(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        requireField(change.weaponId(), "weapon_id", change.op());
        loadoutValidationService.validateForRuntimeSetWeapon(
            playerId,
            classTag,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId()
        );
        upsertSelectedSlot(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), change.weaponId());
        upsertWeaponConfig(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), change.weaponId(), now);
    }

    private void clearWeapon(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change
    ) {
        loadoutValidationService.validateForRuntimeClearWeapon(classTag, change.weaponSlotId());
        upsertSelectedSlot(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), null);
    }

    private void setModule(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        requireField(change.weaponId(), "weapon_id", change.op());
        requireField(change.mountId(), "mount_id", change.op());
        requireField(change.moduleId(), "module_id", change.op());
        loadoutValidationService.validateForRuntimeSetModule(
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId(),
            change.moduleId()
        );
        upsertWeaponConfig(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), change.weaponId(), now);
        replaceSingleModule(playerId, classTag, weaponPresetSlot, catalogVersion, change);
    }

    private void clearModule(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change
    ) {
        requireField(change.weaponId(), "weapon_id", change.op());
        requireField(change.mountId(), "mount_id", change.op());
        loadoutValidationService.validateForRuntimeClearModule(
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId()
        );
        jdbcTemplate.update(
            """
                DELETE FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_slot_id = ?
                  AND weapon_id = ?
                  AND mount_id = ?
                """,
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId()
        );
    }

    private void requireField(String value, String fieldName, String op) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                fieldName + " is required for op " + op
            );
        }
    }

    private void upsertSelectedSlot(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
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
            weaponPresetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    private void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
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
            weaponPresetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            now
        );
    }

    private void replaceSingleModule(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        RuntimePresetChangeStep change
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
                  AND mount_id = ?
                """,
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId()
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
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId(),
            change.moduleId()
        );
    }
}
