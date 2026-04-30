package com.game.backend.matchprofile.application;

import com.game.backend.outbox.application.OutboxService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Помечает сохраненные match profile snapshots устаревшими после изменения access/loadout зависимостей.
 */
@Service
public class MatchProfileInvalidationService {
    private final JdbcTemplate jdbcTemplate;
    private final OutboxService outboxService;

    public MatchProfileInvalidationService(JdbcTemplate jdbcTemplate, OutboxService outboxService) {
        this.jdbcTemplate = jdbcTemplate;
        this.outboxService = outboxService;
    }

    /**
     * Инвалидирует все еще активные snapshots игрока для указанной версии каталога.
     */
    public int invalidateForPlayerAccessChange(
        UUID playerId,
        long catalogVersion,
        String staleReason,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<StaleProfile> staleProfiles = jdbcTemplate.query(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE player_id = ?
                  AND catalog_version = ?
                  AND is_stale = false
                RETURNING profile_id, realm_id, class_tag, team_tag, weapon_preset_slot, outfit_preset_slot, profile_revision
                """,
            (rs, rowNum) -> new StaleProfile(
                rs.getObject("profile_id", UUID.class),
                rs.getString("realm_id"),
                rs.getString("class_tag"),
                rs.getString("team_tag"),
                rs.getInt("weapon_preset_slot"),
                rs.getInt("outfit_preset_slot"),
                rs.getLong("profile_revision")
            ),
            staleReason,
            now,
            playerId,
            catalogVersion
        );

        if (!staleProfiles.isEmpty()) {
            outboxService.record(
                "match_profile.staled",
                "player_match_profile",
                playerId.toString(),
                1,
                Map.of(
                    "player_id", playerId,
                    "catalog_version", catalogVersion,
                    "stale_reason", staleReason,
                    "stale_profiles", staleProfiles.size(),
                    "source_event_id", sourceEventId,
                    "source", "access_change"
                ),
                now
            );
        }
        return staleProfiles.size();
    }

    /**
     * Инвалидирует все активные snapshots игрока независимо от версии каталога.
     */
    public int invalidateForPlayer(
        UUID playerId,
        String staleReason,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<UUID> staleProfileIds = jdbcTemplate.queryForList(
            """
                UPDATE player_match_profiles
                SET is_stale = true,
                    stale_reason = ?,
                    stale_at = ?
                WHERE player_id = ?
                  AND is_stale = false
                RETURNING profile_id
                """,
            UUID.class,
            staleReason,
            now,
            playerId
        );

        if (!staleProfileIds.isEmpty()) {
            outboxService.record(
                "match_profile.staled",
                "player_match_profile",
                playerId.toString(),
                1,
                Map.of(
                    "player_id", playerId,
                    "stale_reason", staleReason,
                    "stale_profiles", staleProfileIds.size(),
                    "source_event_id", sourceEventId,
                    "source", "admin_control"
                ),
                now
            );
        }
        return staleProfileIds.size();
    }

    private record StaleProfile(
        UUID profileId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long profileRevision
    ) {
    }
}
