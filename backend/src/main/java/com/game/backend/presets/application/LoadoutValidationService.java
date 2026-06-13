package com.game.backend.presets.application;

import com.game.backend.presets.repository.PresetsRepository;

import com.game.backend.access.application.ItemAccessPolicy;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class LoadoutValidationService {
    private final PresetsRepository repository;
    private final ItemAccessPolicy itemAccessPolicy;

    public LoadoutValidationService(
        PresetsRepository repository,
        ItemAccessPolicy itemAccessPolicy
    ) {
        this.repository = repository;
        this.itemAccessPolicy = itemAccessPolicy;
    }

    public void validateForPresetSave(
        UUID playerId,
        String classTag,
        long catalogVersion,
        List<WeaponSlotSelection> slots
    ) {
        for (WeaponSlotSelection slot : slots) {
            validateWeaponSlotAllowed(classTag, slot.weaponSlotId());
            if (slot.weaponId() == null) {
                if (!slot.modules().isEmpty()) {
                    throw loadoutValidationFailed("Empty weapon slot cannot contain modules: " + slot.weaponSlotId());
                }
                continue;
            }

            validateCanUseForPresetSave(playerId, slot.weaponId(), catalogVersion, classTag, "weapon");
            for (ModuleSelection module : slot.modules()) {
                validateCanUseForPresetSave(playerId, module.moduleId(), catalogVersion, classTag, "module");
                validateMountModuleAllowed(catalogVersion, slot.weaponId(), module.mountId(), module.moduleId());
            }
        }
    }

    public void validateForRuntimeSetWeapon(
        UUID playerId,
        String classTag,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        validateWeaponSlotAllowed(classTag, weaponSlotId);
        validateCanUseForRuntimePresetChange(playerId, weaponId, catalogVersion, classTag, "weapon");
    }

    public void validateForRuntimeClearWeapon(String classTag, String weaponSlotId) {
        validateWeaponSlotAllowed(classTag, weaponSlotId);
    }

    public void validateForRuntimeSetModule(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
        validateWeaponSlotAllowed(classTag, weaponSlotId);
        validateSelectedWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, weaponSlotId, weaponId);
        validateCanUseForRuntimePresetChange(playerId, weaponId, catalogVersion, classTag, "weapon");
        validateCanUseForRuntimePresetChange(playerId, moduleId, catalogVersion, classTag, "module");
        validateMountModuleAllowed(catalogVersion, weaponId, mountId, moduleId);
    }

    public void validateForRuntimeClearModule(
        UUID playerId,
        String classTag,
        int weaponPresetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        validateWeaponSlotAllowed(classTag, weaponSlotId);
        validateSelectedWeapon(playerId, classTag, weaponPresetSlot, catalogVersion, weaponSlotId, weaponId);
    }

    private void validateWeaponSlotAllowed(String classTag, String weaponSlotId) {
        Boolean allowed = repository.queryForObject(
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
            throw loadoutValidationFailed("Weapon slot is not allowed for class: " + weaponSlotId);
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
        Boolean matches = repository.queryForObject(
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
            throw loadoutValidationFailed("Runtime module change targets a weapon that is not selected in slot: " + weaponSlotId);
        }
    }

    private void validateCanUseForPresetSave(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        if (!itemAccessPolicy.canUseForPresetSave(playerId, itemId, catalogVersion, classTag, itemType)) {
            throw loadoutValidationFailed("Item is not usable in preset: " + itemId);
        }
    }

    private void validateCanUseForRuntimePresetChange(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        if (!itemAccessPolicy.canUseForRuntimePresetChange(playerId, itemId, catalogVersion, classTag, itemType)) {
            throw loadoutValidationFailed("Item is not usable in runtime preset change: " + itemId);
        }
    }

    private void validateMountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        Boolean allowed = repository.queryForObject(
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
            throw loadoutValidationFailed("Module is not allowed for weapon mount: " + moduleId);
        }
    }

    private ApiException loadoutValidationFailed(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", message);
    }

    public record WeaponSlotSelection(String weaponSlotId, String weaponId, List<ModuleSelection> modules) {
    }

    public record ModuleSelection(String mountId, String moduleId) {
    }
}
