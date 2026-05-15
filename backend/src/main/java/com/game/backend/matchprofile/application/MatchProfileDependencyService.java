package com.game.backend.matchprofile.application;

import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MatchProfileDependencyService {
    private final JdbcTemplate jdbcTemplate;

    public MatchProfileDependencyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public DependencyTuple load(BuildMatchProfileRequest request, long catalogVersion) {
        WeaponPresetAndAccess weaponPreset = weaponPresetWithAccess(request, catalogVersion);
        PresetHeader outfitPreset = outfitPreset(request, catalogVersion);
        return new DependencyTuple(
            weaponPreset.revision(),
            weaponPreset.sanitized(),
            outfitPreset.revision(),
            outfitPreset.sanitized(),
            weaponPreset.accessRevision()
        );
    }

    private WeaponPresetAndAccess weaponPresetWithAccess(BuildMatchProfileRequest request, long catalogVersion) {
        List<WeaponPresetAndAccess> rows = jdbcTemplate.query(
            """
                SELECT wp.revision, wp.sanitized, pas.access_revision
                FROM player_weapon_presets wp
                JOIN player_access_projection_state pas ON pas.player_id = wp.player_id
                WHERE wp.player_id = ?
                  AND wp.class_tag = ?
                  AND wp.preset_slot = ?
                  AND wp.catalog_version = ?
                """,
            (rs, rowNum) -> new WeaponPresetAndAccess(
                rs.getLong("revision"),
                rs.getBoolean("sanitized"),
                rs.getLong("access_revision")
            ),
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAPON_PRESET_NOT_FOUND", "Weapon preset was not found for selected catalog version");
        }
        return rows.getFirst();
    }

    private PresetHeader outfitPreset(BuildMatchProfileRequest request, long catalogVersion) {
        List<PresetHeader> presets = jdbcTemplate.query(
            """
                SELECT revision, sanitized
                FROM player_outfit_presets
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                """,
            (rs, rowNum) -> new PresetHeader(rs.getLong("revision"), rs.getBoolean("sanitized")),
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
        if (presets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "OUTFIT_PRESET_NOT_FOUND", "Outfit preset was not found for selected team and catalog version");
        }
        return presets.getFirst();
    }

    public record DependencyTuple(
        long weaponPresetRevision,
        boolean weaponPresetSanitized,
        long outfitPresetRevision,
        boolean outfitPresetSanitized,
        long accessRevision
    ) {
    }

    private record PresetHeader(long revision, boolean sanitized) {
    }

    private record WeaponPresetAndAccess(long revision, boolean sanitized, long accessRevision) {
    }
}
