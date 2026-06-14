package com.game.backend.postmatch.repository;

import com.game.backend.common.persistence.JdbcRepository;
import com.game.backend.postmatch.api.PostMatchPendingChangeDto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@Repository
public class PostMatchRepository extends JdbcRepository {
    public record PendingChange(
        UUID changeId,
        UUID playerId,
        UUID matchId,
        String classTag,
        int weaponPresetSlot,
        long baseWeaponPresetRevision,
        Long currentConflictingRevision,
        String status,
        String payloadJson,
        OffsetDateTime expiresAt
    ) {
    }

    public record PresetHeader(long catalogVersion, long revision) {
    }

    public PostMatchRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<PostMatchPendingChangeDto> findPendingChanges(
        UUID playerId,
        String status,
        Function<String, Map<String, Object>> payloadParser
    ) {
        return query(
            """
                SELECT
                  change_id,
                  match_id,
                  class_tag,
                  weapon_preset_slot,
                  base_weapon_preset_revision,
                  current_conflicting_revision,
                  reason_code,
                  status,
                  payload::text AS payload,
                  created_at,
                  expires_at,
                  resolved_at
                FROM post_match_pending_changes
                WHERE player_id = ?
                  AND status = ?
                ORDER BY created_at DESC
                """,
            (rs, rowNum) -> new PostMatchPendingChangeDto(
                rs.getObject("change_id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getString("class_tag"),
                rs.getInt("weapon_preset_slot"),
                rs.getLong("base_weapon_preset_revision"),
                rs.getObject("current_conflicting_revision", Long.class),
                rs.getString("reason_code"),
                rs.getString("status"),
                payloadParser.apply(rs.getString("payload")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class)
            ),
            playerId,
            status
        );
    }

    public void expireOldPendingChanges(UUID playerId, OffsetDateTime now) {
        update(
            """
                UPDATE post_match_pending_changes
                SET status = 'expired',
                    resolved_at = ?
                WHERE player_id = ?
                  AND status = 'pending'
                  AND expires_at <= ?
                """,
            now,
            playerId,
            now
        );
    }

    public List<PendingChange> lockPendingChanges(UUID playerId, UUID changeId) {
        return query(
            """
                SELECT
                  change_id,
                  player_id,
                  match_id,
                  class_tag,
                  weapon_preset_slot,
                  base_weapon_preset_revision,
                  current_conflicting_revision,
                  status,
                  payload::text AS payload,
                  expires_at
                FROM post_match_pending_changes
                WHERE change_id = ?
                  AND player_id = ?
                FOR UPDATE
                """,
            (rs, rowNum) -> new PendingChange(
                rs.getObject("change_id", UUID.class),
                rs.getObject("player_id", UUID.class),
                rs.getObject("match_id", UUID.class),
                rs.getString("class_tag"),
                rs.getInt("weapon_preset_slot"),
                rs.getLong("base_weapon_preset_revision"),
                rs.getObject("current_conflicting_revision", Long.class),
                rs.getString("status"),
                rs.getString("payload"),
                rs.getObject("expires_at", OffsetDateTime.class)
            ),
            changeId,
            playerId
        );
    }

    public List<PresetHeader> lockWeaponPreset(UUID playerId, String classTag, int presetSlot) {
        return query(
            """
                SELECT catalog_version, revision
                FROM player_weapon_presets
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                FOR UPDATE
                """,
            (rs, rowNum) -> new PresetHeader(
                rs.getLong("catalog_version"),
                rs.getLong("revision")
            ),
            playerId,
            classTag,
            presetSlot
        );
    }

    public void updateWeaponPresetRevision(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        OffsetDateTime now
    ) {
        update(
            """
                UPDATE player_weapon_presets
                SET revision = ?,
                    sanitized = false,
                    updated_at = ?
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                """,
            revision,
            now,
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public void updateChangeStatus(UUID changeId, String status, OffsetDateTime resolvedAt) {
        update(
            """
                UPDATE post_match_pending_changes
                SET status = ?,
                    resolved_at = ?
                WHERE change_id = ?
                """,
            status,
            resolvedAt,
            changeId
        );
    }
}
