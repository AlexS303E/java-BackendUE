package com.game.backend.matchprofile.api;

import com.game.backend.matchprofile.application.MatchProfileBuildCommand;
import com.game.backend.matchprofile.application.MatchProfileDependencyRevisions;
import com.game.backend.matchprofile.application.MatchProfileModule;
import com.game.backend.matchprofile.application.MatchProfileOutfitItem;
import com.game.backend.matchprofile.application.MatchProfileService;
import com.game.backend.matchprofile.application.MatchProfileSnapshot;
import com.game.backend.matchprofile.application.MatchProfileWeapon;
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
        MatchProfileSnapshot snapshot = matchProfileService.build(
            CurrentServer.require(authentication),
            new MatchProfileBuildCommand(
                request.matchId(),
                request.playerId(),
                request.realmId(),
                request.classTag(),
                request.teamTag(),
                request.weaponPresetSlot(),
                request.outfitPresetSlot(),
                request.supportedCatalogVersions(),
                request.preferredCatalogVersion(),
                request.serverBuildId(),
                request.gameModeId()
            )
        );
        return toResponse(snapshot);
    }

    private MatchProfileResponse toResponse(MatchProfileSnapshot snapshot) {
        return new MatchProfileResponse(
            snapshot.schemaVersion(),
            snapshot.playerId(),
            snapshot.realmId(),
            snapshot.catalogVersion(),
            snapshot.classTag(),
            snapshot.teamTag(),
            snapshot.weaponPresetSlot(),
            snapshot.outfitPresetSlot(),
            snapshot.weapons().stream().map(this::toWeapon).toList(),
            snapshot.outfit().stream().map(this::toOutfitItem).toList(),
            snapshot.sanitizedWarnings(),
            toDependencyRevisions(snapshot.dependencyRevisions())
        );
    }

    private MatchWeaponDto toWeapon(MatchProfileWeapon weapon) {
        return new MatchWeaponDto(
            weapon.weaponSlotId(),
            weapon.weaponId(),
            weapon.modules().stream().map(this::toModule).toList()
        );
    }

    private MatchModuleDto toModule(MatchProfileModule module) {
        return new MatchModuleDto(module.mountId(), module.moduleId());
    }

    private MatchOutfitItemDto toOutfitItem(MatchProfileOutfitItem item) {
        return new MatchOutfitItemDto(item.clothingSlotId(), item.itemId());
    }

    private DependencyRevisionsDto toDependencyRevisions(MatchProfileDependencyRevisions revisions) {
        return new DependencyRevisionsDto(
            revisions.weaponPresetRevision(),
            revisions.outfitPresetRevision(),
            revisions.accessRevision(),
            revisions.profileRevision()
        );
    }
}
