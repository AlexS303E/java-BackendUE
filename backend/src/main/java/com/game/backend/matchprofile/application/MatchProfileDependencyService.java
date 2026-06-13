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
        List<DependencyRow> rows = repository.query(
            """
                SELECT wp.revision AS weapon_preset_revision,
                       wp.sanitized AS weapon_preset_sanitized,
                       pas.access_revision,
                       op.revision AS outfit_preset_revision,
                       op.sanitized AS outfit_preset_sanitized
                FROM (SELECT 1) seed
                LEFT JOIN player_weapon_presets wp
                  ON wp.player_id = ?
                 AND wp.class_tag = ?
                 AND wp.preset_slot = ?
                 AND wp.catalog_version = ?
                LEFT JOIN player_access_projection_state pas
                  ON pas.player_id = ?
                LEFT JOIN player_outfit_presets op
                  ON op.player_id = ?
                 AND op.team_tag = ?
                 AND op.class_tag = ?
                 AND op.outfit_preset_slot = ?
                 AND op.catalog_version = ?
                """,
            (rs, rowNum) -> new DependencyRow(
                rs.getObject("weapon_preset_revision", Long.class),
                rs.getObject("weapon_preset_sanitized", Boolean.class),
                rs.getObject("access_revision", Long.class),
                rs.getObject("outfit_preset_revision", Long.class),
                rs.getObject("outfit_preset_sanitized", Boolean.class)
            ),
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            request.playerId(),
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MATCH_PROFILE_DEPENDENCY_LOAD_FAILED", "Unable to load match profile dependencies");
        }
        DependencyRow row = rows.getFirst();
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

    private record DependencyRow(
        Long weaponPresetRevision,
        Boolean weaponPresetSanitized,
        Long accessRevision,
        Long outfitPresetRevision,
        Boolean outfitPresetSanitized
    ) {
    }
}
