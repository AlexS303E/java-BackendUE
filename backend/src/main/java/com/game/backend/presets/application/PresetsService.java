package com.game.backend.presets.application;

import com.game.backend.presets.repository.PresetsRepository;
import com.game.backend.presets.repository.PresetsRepository.ModuleSelectionRecord;
import com.game.backend.presets.repository.PresetsRepository.OutfitItemRecord;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetRecord;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetItemRow;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetKey;
import com.game.backend.presets.repository.PresetsRepository.PresetHeader;
import com.game.backend.presets.repository.PresetsRepository.SaveModuleCommand;
import com.game.backend.presets.repository.PresetsRepository.SaveWeaponSlotCommand;
import com.game.backend.presets.repository.PresetsRepository.SlotAndWeaponKey;
import com.game.backend.presets.repository.PresetsRepository.WeaponConfigModuleRow;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetRecord;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetKey;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetSlotRow;
import com.game.backend.presets.repository.PresetsRepository.WeaponSlotRecord;

import com.game.backend.common.api.ApiException;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
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
    private final PresetsRepository repository;
    private final OutboxService outboxService;
    private final LoadoutValidationService loadoutValidationService;

    public PresetsService(
        PresetsRepository repository,
        OutboxService outboxService,
        LoadoutValidationService loadoutValidationService
    ) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.loadoutValidationService = loadoutValidationService;
    }

    /**
     * Полностью сохраняет weapon preset, если If-Match совпал с текущей ревизией.
     */
    @Transactional
    public WeaponPresetSaveResult saveWeaponPreset(
        UUID playerId,
        String classTag,
        int presetSlot,
        String ifMatch,
        WeaponPresetSaveCommand command
    ) {
        long expectedRevision = parseIfMatch(ifMatch);
        long catalogVersion = command.catalogVersion();
        // FOR UPDATE удерживает текущую ревизию до конца транзакции и защищает от гонок сохранения.
        PresetHeader current = lockWeaponPreset(playerId, classTag, presetSlot, catalogVersion);

        if (current.revision() != expectedRevision) {
            throw new ApiException(
                HttpStatus.PRECONDITION_FAILED,
                "PRECONDITION_FAILED",
                "Weapon preset revision mismatch"
            );
        }

        validateSaveCommand(playerId, classTag, command);
        OffsetDateTime now = OffsetDateTime.now();

        for (WeaponSlotSave slot : command.slots()) {
            upsertSelectedSlot(playerId, classTag, presetSlot, catalogVersion, slot);
            if (slot.weaponId() != null) {
                upsertWeaponConfig(playerId, classTag, presetSlot, catalogVersion, slot, now);
                replaceModulesForWeaponConfig(playerId, classTag, presetSlot, catalogVersion, slot);
            }
        }

        long newRevision = expectedRevision + 1;
        repository.updateWeaponPresetRevision(playerId, classTag, presetSlot, catalogVersion, newRevision, now);
        outboxService.recordWeaponPresetSaved(
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            expectedRevision,
            newRevision,
            "player_save",
            now
        );

        return new WeaponPresetSaveResult(
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            newRevision,
            weaponSlots(playerId, classTag, presetSlot, catalogVersion)
        );
    }

    /**
     * Возвращает все presets игрока для страницы loadout.
     * Использует batch-загрузку (5 queries) вместо N+1 (~31 queries).
     */
    public PlayerPresetsSnapshot getPlayerPresets(UUID playerId) {
        List<WeaponPreset> weaponPresets = loadWeaponPresetsBatch(playerId);
        List<OutfitPreset> outfitPresets = loadOutfitPresetsBatch(playerId);
        return new PlayerPresetsSnapshot(playerId, weaponPresets, outfitPresets);
    }

    private List<WeaponPreset> loadWeaponPresetsBatch(UUID playerId) {
        List<WeaponPreset> presets = repository.findWeaponPresets(playerId)
            .stream()
            .map(this::toWeaponPreset)
            .toList();

        if (presets.isEmpty()) return presets;

        Map<WeaponPresetKey, List<WeaponSlotPreset>> slotsByPreset = new HashMap<>();
        for (WeaponPreset p : presets) {
            slotsByPreset.put(new WeaponPresetKey(p.classTag(), p.presetSlot(), p.catalogVersion()), p.slots());
        }

        Map<SlotAndWeaponKey, List<ModuleSelection>> modulesBySlot = new HashMap<>();

        for (WeaponPresetSlotRow row : repository.findWeaponPresetSlotRows(playerId)) {
            WeaponPresetKey pk = new WeaponPresetKey(row.classTag(), row.presetSlot(), row.catalogVersion());
            List<WeaponSlotPreset> slots = slotsByPreset.get(pk);
            List<ModuleSelection> slotModules = new ArrayList<>();
            WeaponSlotPreset slot = new WeaponSlotPreset(row.weaponSlotId(), row.selectedWeaponId(), slotModules);
            if (slots != null) slots.add(slot);
            if (row.selectedWeaponId() != null) {
                modulesBySlot.put(
                    new SlotAndWeaponKey(row.classTag(), row.presetSlot(), row.catalogVersion(), row.weaponSlotId(), row.selectedWeaponId()),
                    slotModules
                );
            }
        }

        if (!modulesBySlot.isEmpty()) {
            for (WeaponConfigModuleRow row : repository.findWeaponConfigModuleRows(playerId)) {
                SlotAndWeaponKey sk = new SlotAndWeaponKey(
                    row.classTag(),
                    row.presetSlot(),
                    row.catalogVersion(),
                    row.weaponSlotId(),
                    row.weaponId()
                );
                List<ModuleSelection> slotModules = modulesBySlot.get(sk);
                if (slotModules != null) {
                    slotModules.add(new ModuleSelection(row.mountId(), row.moduleId()));
                }
            }
        }

        return presets;
    }

    private List<OutfitPreset> loadOutfitPresetsBatch(UUID playerId) {
        List<OutfitPreset> presets = repository.findOutfitPresets(playerId)
            .stream()
            .map(this::toOutfitPreset)
            .toList();

        if (presets.isEmpty()) return presets;

        Map<OutfitPresetKey, List<OutfitItem>> itemsByPreset = new HashMap<>();
        for (OutfitPreset p : presets) {
            itemsByPreset.put(new OutfitPresetKey(p.teamTag(), p.classTag(), p.outfitPresetSlot(), p.catalogVersion()), p.items());
        }

        for (OutfitPresetItemRow row : repository.findOutfitPresetItemRows(playerId)) {
            OutfitPresetKey pk = new OutfitPresetKey(row.teamTag(), row.classTag(), row.outfitPresetSlot(), row.catalogVersion());
            List<OutfitItem> items = itemsByPreset.get(pk);
            if (items != null) {
                items.add(new OutfitItem(row.clothingSlotId(), row.itemId()));
            }
        }

        return presets;
    }

    /**
     * Читает weapon presets вместе с выбранными slots/modules (оригинальный N+1 метод, сохранён для обратной совместимости).
     */
    public List<WeaponPreset> weaponPresets(UUID playerId) {
        return loadWeaponPresetsBatch(playerId);
    }

    /**
     * Читает outfit presets вместе с выбранными clothing slots (оригинальный N+1 метод, сохранён для обратной совместимости).
     */
    public List<OutfitPreset> outfitPresets(UUID playerId) {
        return loadOutfitPresetsBatch(playerId);
    }

    public List<WeaponSlotPreset> weaponSlots(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return repository.findWeaponSlots(playerId, classTag, presetSlot, catalogVersion).stream()
            .map(slot -> toWeaponSlotPreset(playerId, classTag, presetSlot, catalogVersion, slot))
            .toList();
    }

    public List<ModuleSelection> modules(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        return repository.findModules(playerId, classTag, presetSlot, catalogVersion, weaponSlotId, weaponId)
            .stream()
            .map(this::toModuleSelectionDto)
            .toList();
    }

    public List<OutfitItem> outfitItems(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
        return repository.findOutfitItems(playerId, teamTag, classTag, outfitPresetSlot, catalogVersion)
            .stream()
            .map(this::toOutfitItemDto)
            .toList();
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
        List<PresetHeader> presets = repository.lockWeaponPreset(playerId, classTag, presetSlot, catalogVersion);
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
    private void validateSaveCommand(UUID playerId, String classTag, WeaponPresetSaveCommand command) {
        Set<String> weaponSlotIds = new HashSet<>();
        List<LoadoutValidationService.WeaponSlotSelection> slots = new ArrayList<>();
        for (WeaponSlotSave slot : command.slots()) {
            if (!weaponSlotIds.add(slot.weaponSlotId())) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Duplicate weapon_slot_id in request: " + slot.weaponSlotId()
                );
            }

            Set<String> mountIds = new HashSet<>();
            List<LoadoutValidationService.ModuleSelection> modules = new ArrayList<>();
            for (ModuleSave module : slot.modules()) {
                if (!mountIds.add(module.mountId())) {
                    throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_ERROR",
                        "Duplicate mount_id in request: " + module.mountId()
                    );
                }
                modules.add(new LoadoutValidationService.ModuleSelection(module.mountId(), module.moduleId()));
            }
            slots.add(new LoadoutValidationService.WeaponSlotSelection(slot.weaponSlotId(), slot.weaponId(), modules));
        }
        loadoutValidationService.validateForPresetSave(playerId, classTag, command.catalogVersion(), slots);
    }

    private void upsertSelectedSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        WeaponSlotSave slot
    ) {
        repository.upsertSelectedSlot(playerId, classTag, presetSlot, catalogVersion, toSaveWeaponSlotCommand(slot));
    }

    private void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        WeaponSlotSave slot,
        OffsetDateTime now
    ) {
        repository.upsertWeaponConfig(playerId, classTag, presetSlot, catalogVersion, toSaveWeaponSlotCommand(slot), now);
    }

    /**
     * Заменяет набор модулей целиком, чтобы сохранить запрос идемпотентным по содержимому slot.
     */
    private void replaceModulesForWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        WeaponSlotSave slot
    ) {
        repository.deleteWeaponConfigModules(
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            slot.weaponSlotId(),
            slot.weaponId()
        );

        for (ModuleSave module : slot.modules()) {
            repository.insertWeaponConfigModule(
                playerId,
                classTag,
                presetSlot,
                catalogVersion,
                slot.weaponSlotId(),
                slot.weaponId(),
                toSaveModuleCommand(module)
            );
        }
    }

    private WeaponPreset toWeaponPreset(WeaponPresetRecord preset) {
        return new WeaponPreset(
            preset.classTag(),
            preset.presetSlot(),
            preset.catalogVersion(),
            preset.revision(),
            preset.sanitized(),
            new ArrayList<>()
        );
    }

    private OutfitPreset toOutfitPreset(OutfitPresetRecord preset) {
        return new OutfitPreset(
            preset.teamTag(),
            preset.classTag(),
            preset.outfitPresetSlot(),
            preset.catalogVersion(),
            preset.revision(),
            preset.sanitized(),
            new ArrayList<>()
        );
    }

    private WeaponSlotPreset toWeaponSlotPreset(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        WeaponSlotRecord slot
    ) {
        return new WeaponSlotPreset(
            slot.weaponSlotId(),
            slot.selectedWeaponId(),
            slot.selectedWeaponId() == null
                ? List.of()
                : modules(playerId, classTag, presetSlot, catalogVersion, slot.weaponSlotId(), slot.selectedWeaponId())
        );
    }

    private ModuleSelection toModuleSelectionDto(ModuleSelectionRecord module) {
        return new ModuleSelection(module.mountId(), module.moduleId());
    }

    private OutfitItem toOutfitItemDto(OutfitItemRecord item) {
        return new OutfitItem(item.clothingSlotId(), item.itemId());
    }

    private SaveWeaponSlotCommand toSaveWeaponSlotCommand(WeaponSlotSave slot) {
        return new SaveWeaponSlotCommand(slot.weaponSlotId(), slot.weaponId());
    }

    private SaveModuleCommand toSaveModuleCommand(ModuleSave module) {
        return new SaveModuleCommand(module.mountId(), module.moduleId());
    }
}
