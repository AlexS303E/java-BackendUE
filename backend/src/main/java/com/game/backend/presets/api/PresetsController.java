package com.game.backend.presets.api;

import com.game.backend.auth.application.CurrentPlayer;
import com.game.backend.presets.application.ModuleSelection;
import com.game.backend.presets.application.OutfitItem;
import com.game.backend.presets.application.OutfitPreset;
import com.game.backend.presets.application.PlayerPresetsSnapshot;
import com.game.backend.presets.application.PresetsService;
import com.game.backend.presets.application.WeaponPreset;
import com.game.backend.presets.application.WeaponSlotPreset;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Player API для чтения и сохранения loadout presets.
 */
@RestController
public class PresetsController {
    private final PresetsService presetsService;

    public PresetsController(PresetsService presetsService) {
        this.presetsService = presetsService;
    }

    /**
     * Возвращает presets текущего игрока из Bearer JWT.
     */
    @GetMapping("/me/presets")
    PlayerPresetsResponse getMyPresets(Authentication authentication) {
        return toResponse(presetsService.getPlayerPresets(CurrentPlayer.require(authentication).playerId()));
    }

    /**
     * Сохраняет weapon preset с optimistic locking через If-Match revision.
     */
    @PutMapping("/me/presets/weapons/{class_tag}/{preset_slot}")
    ResponseEntity<WeaponPresetSaveResponse> saveWeaponPreset(
            Authentication authentication,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @PathVariable("class_tag") String classTag,
            @PathVariable("preset_slot") int presetSlot,
            @Valid @RequestBody WeaponPresetSaveRequest request
    ) {
        UUID playerId = CurrentPlayer.require(authentication).playerId();
        WeaponPresetSaveResponse response = presetsService.saveWeaponPreset(
                playerId,
                classTag,
                presetSlot,
                ifMatch,
                request
        );
        return ResponseEntity
                .ok()
                .eTag(Long.toString(response.revision()))
                .body(response);
    }

    private PlayerPresetsResponse toResponse(PlayerPresetsSnapshot snapshot) {
        return new PlayerPresetsResponse(
                snapshot.playerId(),
                snapshot.weaponPresets().stream().map(this::toWeaponPresetDto).toList(),
                snapshot.outfitPresets().stream().map(this::toOutfitPresetDto).toList()
        );
    }

    private WeaponPresetDto toWeaponPresetDto(WeaponPreset preset) {
        return new WeaponPresetDto(
                preset.classTag(),
                preset.presetSlot(),
                preset.catalogVersion(),
                preset.revision(),
                preset.sanitized(),
                preset.slots().stream().map(this::toWeaponSlotPresetDto).toList()
        );
    }

    private WeaponSlotPresetDto toWeaponSlotPresetDto(WeaponSlotPreset slot) {
        return new WeaponSlotPresetDto(
                slot.weaponSlotId(),
                slot.selectedWeaponId(),
                slot.modules().stream().map(this::toModuleSelectionDto).toList()
        );
    }

    private ModuleSelectionDto toModuleSelectionDto(ModuleSelection module) {
        return new ModuleSelectionDto(module.mountId(), module.moduleId());
    }

    private OutfitPresetDto toOutfitPresetDto(OutfitPreset preset) {
        return new OutfitPresetDto(
                preset.teamTag(),
                preset.classTag(),
                preset.outfitPresetSlot(),
                preset.catalogVersion(),
                preset.revision(),
                preset.sanitized(),
                preset.items().stream().map(this::toOutfitItemDto).toList()
        );
    }

    private OutfitItemDto toOutfitItemDto(OutfitItem item) {
        return new OutfitItemDto(item.clothingSlotId(), item.itemId());
    }
}
