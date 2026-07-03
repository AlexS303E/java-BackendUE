package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.game.backend.common.api.ApiException;
import com.game.backend.presets.application.LoadoutValidationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Применяет атомарные runtime changes к durable weapon preset.
 */
@Service
public class WeaponPresetRuntimeChangeApplier {
    private final RuntimeChangesRepository repository;
    private final LoadoutValidationService loadoutValidationService;

    public WeaponPresetRuntimeChangeApplier(
        RuntimeChangesRepository repository,
        LoadoutValidationService loadoutValidationService
    ) {
        this.repository = repository;
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
        repository.deleteWeaponConfigModule(
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
        repository.upsertSelectedWeaponSlot(
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
        repository.upsertWeaponConfig(
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
        repository.deleteWeaponConfigModule(
            playerId,
            classTag,
            weaponPresetSlot,
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId()
        );

        repository.insertWeaponConfigModule(
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
