package com.game.backend.presets.api;

import com.game.backend.presets.application.PresetsService;
import org.springframework.web.bind.annotation.GetMapping;
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
}
