package com.game.backend.matchprofile.api;

import com.game.backend.matchprofile.application.MatchProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MatchProfileController {
    private final MatchProfileService matchProfileService;

    public MatchProfileController(MatchProfileService matchProfileService) {
        this.matchProfileService = matchProfileService;
    }

    @PostMapping("/server/match-profile/build")
    MatchProfileResponse buildMatchProfile(@Valid @RequestBody BuildMatchProfileRequest request) {
        return matchProfileService.build(request);
    }
}
