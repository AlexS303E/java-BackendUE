package com.game.backend.runtimechanges.application;

import com.game.backend.runtimechanges.repository.RuntimeChangesRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeChangeConflictService {
    private static final int PENDING_TTL_DAYS = 7;
    private static final String REVISION_CONFLICT_REASON = "revision_conflict";

    private final RuntimeChangesRepository repository;
    private final ObjectMapper objectMapper;
    private final OutboxService outboxService;
    private final PlayerNotificationService playerNotificationService;

    public RuntimeChangeConflictService(
        RuntimeChangesRepository repository,
        ObjectMapper objectMapper,
        OutboxService outboxService,
        PlayerNotificationService playerNotificationService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.outboxService = outboxService;
        this.playerNotificationService = playerNotificationService;
    }

    public UUID createRevisionConflict(
        RuntimePresetChangeRequest request,
        long currentRevision,
        OffsetDateTime now
    ) {
        UUID pendingChangeId = createPendingChange(request, currentRevision, now);
        recordPendingChangeCreated(request, currentRevision, pendingChangeId, now);
        return pendingChangeId;
    }

    private UUID createPendingChange(
        RuntimePresetChangeRequest request,
        long currentRevision,
        OffsetDateTime now
    ) {
        UUID changeId = UUID.randomUUID();
        repository.update(
            """
                INSERT INTO post_match_pending_changes(
                  change_id,
                  player_id,
                  match_id,
                  class_tag,
                  weapon_preset_slot,
                  base_weapon_preset_revision,
                  current_conflicting_revision,
                  reason_code,
                  status,
                  payload,
                  payload_schema_version,
                  created_at,
                  expires_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'pending', ?::jsonb, 1, ?, ?)
                """,
            changeId,
            request.playerId(),
            request.matchId(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.baseWeaponPresetRevision(),
            currentRevision,
            REVISION_CONFLICT_REASON,
            pendingPayload(request, currentRevision),
            now,
            now.plusDays(PENDING_TTL_DAYS)
        );
        return changeId;
    }

    private void recordPendingChangeCreated(
        RuntimePresetChangeRequest request,
        long currentRevision,
        UUID pendingChangeId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", request.playerId(),
            "match_id", request.matchId(),
            "operation_id", request.operationId(),
            "class_tag", request.classTag(),
            "preset_slot", request.weaponPresetSlot(),
            "base_revision", request.baseWeaponPresetRevision(),
            "current_revision", currentRevision,
            "pending_change_id", pendingChangeId,
            "status", "pending",
            "source", "runtime"
        );
        outboxService.record(
            "post_match_pending_change.created",
            "post_match_pending_change",
            pendingChangeId.toString(),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            request.playerId(),
            "post_match_pending_change.created",
            "post_match_pending_change",
            pendingChangeId.toString(),
            1,
            payload,
            now
        );
    }

    private String pendingPayload(RuntimePresetChangeRequest request, long currentRevision) {
        Map<String, Object> payload = Map.of(
            "schema_version", 1,
            "runtime_change_payload", request.runtimeChangePayload(),
            "conflict", Map.of(
                "reason_code", REVISION_CONFLICT_REASON,
                "base_weapon_preset_revision", request.baseWeaponPresetRevision(),
                "current_weapon_preset_revision", currentRevision
            ),
            "resolution_options", List.of("apply_if_still_valid", "discard", "manual_merge")
        );
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RUNTIME_CHANGE_SERIALIZATION_FAILED",
                "Unable to serialize runtime preset change conflict"
            );
        }
    }
}
