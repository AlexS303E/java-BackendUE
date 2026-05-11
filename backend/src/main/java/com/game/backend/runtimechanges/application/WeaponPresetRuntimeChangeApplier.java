package com.game.backend.runtimechanges.application;

import com.game.backend.common.api.ApiException;
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

    public WeaponPresetRuntimeChangeApplier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        validateWeaponSlotAllowed(classTag, change.weaponSlotId());
        validateCanUse(playerId, change.weaponId(), catalogVersion, classTag, "weapon");
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
        validateWeaponSlotAllowed(classTag, change.weaponSlotId());
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
        validateWeaponSlotAllowed(classTag, change.weaponSlotId());
        validateSelectedWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), change.weaponId());
        validateCanUse(playerId, change.weaponId(), catalogVersion, classTag, "weapon");
        validateCanUse(playerId, change.moduleId(), catalogVersion, classTag, "module");
        validateMountModuleAllowed(catalogVersion, change.weaponId(), change.mountId(), change.moduleId());
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
        validateWeaponSlotAllowed(classTag, change.weaponSlotId());
        validateSelectedWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, change.weaponSlotId(), change.weaponId());
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

    private void validateSelectedWeapon(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        Boolean matches = jdbcTemplate.queryForObject(
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
        if (!Boolean.TRUE.equals(matches)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Runtime module change targets a weapon that is not selected in slot: " + weaponSlotId
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
                "Item is not usable in runtime preset change: " + itemId
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
