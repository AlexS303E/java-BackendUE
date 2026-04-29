package com.game.backend.presets.api;

import com.game.backend.presets.application.PresetsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class PresetsController {
    private final PresetsService presetsService;

    public PresetsController(PresetsService presetsService) {
        this.presetsService = presetsService;
    }

    @GetMapping("/me/presets")
    PlayerPresetsResponse getMyPresets(@RequestHeader("X-Player-Id") UUID playerId) {
        return presetsService.getPlayerPresets(playerId);
    }

    @PutMapping("/me/presets/weapons/{classTag}/{presetSlot}")
    ResponseEntity<WeaponPresetSaveResponse> saveWeaponPreset(
        @RequestHeader("X-Player-Id") UUID playerId,
        @RequestHeader(value = "If-Match", required = false) String ifMatch,
        @PathVariable String classTag,
        @PathVariable int presetSlot,
        @Valid @RequestBody WeaponPresetSaveRequest request
    ) {
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
}
