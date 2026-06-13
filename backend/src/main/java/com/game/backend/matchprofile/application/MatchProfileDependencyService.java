package com.game.backend.matchprofile.application;

import com.game.backend.matchprofile.repository.MatchProfileRepository;

import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MatchProfileDependencyService {
    private final MatchProfileRepository repository;

    public MatchProfileDependencyService(MatchProfileRepository repository) {
        this.repository = repository;
    }

    public DependencyTuple load(BuildMatchProfileRequest request, long catalogVersion) {
        List<MatchProfileRepository.DependencyRow> rows = repository.loadDependencies(
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.teamTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MATCH_PROFILE_DEPENDENCY_LOAD_FAILED", "Unable to load match profile dependencies");
        }
        MatchProfileRepository.DependencyRow row = rows.getFirst();
        if (row.weaponPresetRevision() == null || row.accessRevision() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAPON_PRESET_NOT_FOUND", "Weapon preset was not found for selected catalog version");
        }
        if (row.outfitPresetRevision() == null) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTFIT_PRESET_NOT_FOUND", "Outfit preset was not found for selected team and catalog version");
        }
        return new DependencyTuple(
            row.weaponPresetRevision(),
            Boolean.TRUE.equals(row.weaponPresetSanitized()),
            row.outfitPresetRevision(),
            Boolean.TRUE.equals(row.outfitPresetSanitized()),
            row.accessRevision()
        );
    }

    public record DependencyTuple(
        long weaponPresetRevision,
        boolean weaponPresetSanitized,
        long outfitPresetRevision,
        boolean outfitPresetSanitized,
        long accessRevision
    ) {
    }
}
