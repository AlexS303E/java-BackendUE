package com.game.backend.matchprofile.api;

import com.game.backend.matchprofile.application.MatchProfileService;
import com.game.backend.serverauth.application.CurrentServer;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Server API для сборки loadout snapshot, который Dedicated Server применяет в матче.
 */
@RestController
public class MatchProfileController {
    private final MatchProfileService matchProfileService;

    public MatchProfileController(MatchProfileService matchProfileService) {
        this.matchProfileService = matchProfileService;
    }

    /**
     * Собирает match profile только для server identity, прошедшей scope и match assignment проверки.
     */
    @PostMapping("/server/match-profile/build")
    MatchProfileResponse buildMatchProfile(
        Authentication authentication,
        @Valid @RequestBody BuildMatchProfileRequest request
    ) {
        return matchProfileService.build(CurrentServer.require(authentication), request);
    }
}
