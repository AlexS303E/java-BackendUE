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
        if (!repository.isWeaponSlotAllowed(classTag, weaponSlotId)) {
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
        if (!repository.isSelectedWeapon(
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        )) {
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
        if (!repository.isMountModuleAllowed(catalogVersion, weaponId, mountId, moduleId)) {
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
