package com.game.backend.matchprofile.application;

import com.game.backend.matchprofile.repository.MatchProfileRepository;

import com.game.backend.outbox.application.OutboxService;
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
    private final MatchProfileRepository repository;
    private final OutboxService outboxService;

    public MatchProfileInvalidationService(MatchProfileRepository repository, OutboxService outboxService) {
        this.repository = repository;
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
        List<MatchProfileRepository.StaleProfile> staleProfiles = repository.staleProfilesForPlayerAccessChange(
            playerId,
            catalogVersion,
            staleReason,
            now
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
        List<UUID> staleProfileIds = repository.staleProfileIdsForPlayer(
            playerId,
            staleReason,
            now
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
}
