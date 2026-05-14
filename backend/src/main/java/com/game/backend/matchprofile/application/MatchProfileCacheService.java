package com.game.backend.matchprofile.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class MatchProfileCacheService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MatchProfileCacheService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public MatchProfileResponse findByDependencyTuple(
        BuildMatchProfileRequest request,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        List<String> payloads = jdbcTemplate.queryForList(
            """
                SELECT payload
                FROM player_match_profiles
                WHERE player_id = ?
                  AND realm_id = ?
                  AND class_tag = ?
                  AND team_tag = ?
                  AND weapon_preset_slot = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_preset_revision = ?
                  AND outfit_preset_revision = ?
                  AND access_revision = ?
                  AND is_stale = false
                  AND expires_at > NOW()
                ORDER BY generated_at DESC
                LIMIT 1
                """,
            String.class,
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        );
        if (payloads.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(payloads.getFirst(), MatchProfileResponse.class);
        } catch (Exception exception) {
            return null;
        }
    }

    public void save(BuildMatchProfileRequest request, MatchProfileResponse response) {
        OffsetDateTime now = OffsetDateTime.now();
        String payload = toJson(response);

        jdbcTemplate.update(
            """
                INSERT INTO player_match_profiles(
                  profile_id,
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version,
                  profile_revision,
                  payload,
                  payload_schema_version,
                  is_stale,
                  generated_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, 1, false, ?, ?)
                ON CONFLICT (
                  player_id,
                  realm_id,
                  class_tag,
                  team_tag,
                  weapon_preset_slot,
                  outfit_preset_slot,
                  weapon_preset_revision,
                  outfit_preset_revision,
                  access_revision,
                  catalog_version
                )
                DO UPDATE SET
                  profile_revision = EXCLUDED.profile_revision,
                  payload = EXCLUDED.payload,
                  payload_schema_version = EXCLUDED.payload_schema_version,
                  is_stale = false,
                  stale_reason = null,
                  stale_at = null,
                  generated_at = EXCLUDED.generated_at,
                  expires_at = EXCLUDED.expires_at
                """,
            UUID.randomUUID(),
            request.playerId(),
            response.realmId(),
            response.classTag(),
            response.teamTag(),
            response.weaponPresetSlot(),
            response.outfitPresetSlot(),
            response.dependencyRevisions().weaponPresetRevision(),
            response.dependencyRevisions().outfitPresetRevision(),
            response.dependencyRevisions().accessRevision(),
            response.catalogVersion(),
            response.dependencyRevisions().profileRevision(),
            payload,
            now,
            now.plusMinutes(10)
        );
    }

    private String toJson(MatchProfileResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "MATCH_PROFILE_SERIALIZATION_FAILED",
                "Unable to serialize match profile"
            );
        }
    }
}
