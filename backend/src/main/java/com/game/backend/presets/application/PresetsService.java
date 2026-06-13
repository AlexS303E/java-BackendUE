package com.game.backend.presets.application;

import com.game.backend.presets.repository.PresetsRepository;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetItemRow;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetKey;
import com.game.backend.presets.repository.PresetsRepository.PresetHeader;
import com.game.backend.presets.repository.PresetsRepository.SlotAndWeaponKey;
import com.game.backend.presets.repository.PresetsRepository.WeaponConfigModuleRow;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetKey;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetSlotRow;

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
        repository.updateWeaponPresetRevision(playerId, classTag, presetSlot, catalogVersion, newRevision, now);
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
        List<WeaponPresetDto> presets = repository.findWeaponPresets(playerId);

        if (presets.isEmpty()) return presets;

        Map<WeaponPresetKey, List<WeaponSlotPresetDto>> slotsByPreset = new HashMap<>();
        for (WeaponPresetDto p : presets) {
            slotsByPreset.put(new WeaponPresetKey(p.classTag(), p.presetSlot(), p.catalogVersion()), p.slots());
        }

        Map<SlotAndWeaponKey, List<ModuleSelectionDto>> modulesBySlot = new HashMap<>();

        for (WeaponPresetSlotRow row : repository.findWeaponPresetSlotRows(playerId)) {
            WeaponPresetKey pk = new WeaponPresetKey(row.classTag(), row.presetSlot(), row.catalogVersion());
            List<WeaponSlotPresetDto> slots = slotsByPreset.get(pk);
            List<ModuleSelectionDto> slotModules = new ArrayList<>();
            WeaponSlotPresetDto slot = new WeaponSlotPresetDto(row.weaponSlotId(), row.selectedWeaponId(), slotModules);
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
                List<ModuleSelectionDto> slotModules = modulesBySlot.get(sk);
                if (slotModules != null) {
                    slotModules.add(new ModuleSelectionDto(row.mountId(), row.moduleId()));
                }
            }
        }

        return presets;
    }

    private List<OutfitPresetDto> loadOutfitPresetsBatch(UUID playerId) {
        List<OutfitPresetDto> presets = repository.findOutfitPresets(playerId);

        if (presets.isEmpty()) return presets;

        Map<OutfitPresetKey, List<OutfitItemDto>> itemsByPreset = new HashMap<>();
        for (OutfitPresetDto p : presets) {
            itemsByPreset.put(new OutfitPresetKey(p.teamTag(), p.classTag(), p.outfitPresetSlot(), p.catalogVersion()), p.items());
        }

        for (OutfitPresetItemRow row : repository.findOutfitPresetItemRows(playerId)) {
            OutfitPresetKey pk = new OutfitPresetKey(row.teamTag(), row.classTag(), row.outfitPresetSlot(), row.catalogVersion());
            List<OutfitItemDto> items = itemsByPreset.get(pk);
            if (items != null) {
                items.add(new OutfitItemDto(row.clothingSlotId(), row.itemId()));
            }
        }

        return presets;
    }

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
        return repository.findWeaponSlots(playerId, classTag, presetSlot, catalogVersion).stream()
            .map(slot -> new WeaponSlotPresetDto(
                slot.weaponSlotId(),
                slot.selectedWeaponId(),
                slot.selectedWeaponId() == null
                    ? List.of()
                    : modules(playerId, classTag, presetSlot, catalogVersion, slot.weaponSlotId(), slot.selectedWeaponId())
            ))
            .toList();
    }

    public List<ModuleSelectionDto> modules(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        return repository.findModules(playerId, classTag, presetSlot, catalogVersion, weaponSlotId, weaponId);
    }

    public List<OutfitItemDto> outfitItems(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
        return repository.findOutfitItems(playerId, teamTag, classTag, outfitPresetSlot, catalogVersion);
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
    private void validateSaveRequest(UUID playerId, String classTag, WeaponPresetSaveRequest request) {
        Set<String> weaponSlotIds = new HashSet<>();
        List<LoadoutValidationService.WeaponSlotSelection> slots = new ArrayList<>();
        for (SaveWeaponSlotRequest slot : request.slots()) {
            if (!weaponSlotIds.add(slot.weaponSlotId())) {
                throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    "Duplicate weapon_slot_id in request: " + slot.weaponSlotId()
                );
            }

            Set<String> mountIds = new HashSet<>();
            List<LoadoutValidationService.ModuleSelection> modules = new ArrayList<>();
            for (SaveModuleRequest module : slot.modules()) {
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
        loadoutValidationService.validateForPresetSave(playerId, classTag, request.catalogVersion(), slots);
    }

    private void upsertSelectedSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot
    ) {
        repository.upsertSelectedSlot(playerId, classTag, presetSlot, catalogVersion, slot);
    }

    private void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        SaveWeaponSlotRequest slot,
        OffsetDateTime now
    ) {
        repository.upsertWeaponConfig(playerId, classTag, presetSlot, catalogVersion, slot, now);
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
        repository.deleteWeaponConfigModules(
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            slot.weaponSlotId(),
            slot.weaponId()
        );

        for (SaveModuleRequest module : slot.modules()) {
            repository.insertWeaponConfigModule(
                playerId,
                classTag,
                presetSlot,
                catalogVersion,
                slot.weaponSlotId(),
                slot.weaponId(),
                module
            );
        }
    }
}
