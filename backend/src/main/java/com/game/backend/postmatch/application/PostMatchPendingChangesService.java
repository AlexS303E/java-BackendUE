package com.game.backend.postmatch.application;

import com.game.backend.postmatch.repository.PostMatchRepository;
import com.game.backend.postmatch.repository.PostMatchRepository.PendingChange;
import com.game.backend.postmatch.repository.PostMatchRepository.PendingChangeSummary;
import com.game.backend.postmatch.repository.PostMatchRepository.PresetHeader;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.application.WeaponPresetRuntimeChangeApplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Управляет просмотром и решением pending changes, которые появились из runtime conflicts.
 */
@Service
public class PostMatchPendingChangesService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final Set<String> READABLE_STATUSES = Set.of(
        "pending",
        "applied",
        "rejected",
        "expired",
        "superseded"
    );

    private final PostMatchRepository repository;
    private final ObjectMapper objectMapper;
    private final WeaponPresetRuntimeChangeApplier runtimeChangeApplier;
    private final OutboxService outboxService;
    private final PlayerNotificationService playerNotificationService;

    public PostMatchPendingChangesService(
        PostMatchRepository repository,
        ObjectMapper objectMapper,
        WeaponPresetRuntimeChangeApplier runtimeChangeApplier,
        OutboxService outboxService,
        PlayerNotificationService playerNotificationService
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.runtimeChangeApplier = runtimeChangeApplier;
        this.outboxService = outboxService;
        this.playerNotificationService = playerNotificationService;
    }

    /**
     * Возвращает changes игрока по статусу и по пути истечает просроченные pending rows.
     */
    @Transactional
    public PostMatchPendingChangePage getChanges(UUID playerId, String status) {
        String normalizedStatus = normalizeStatus(status);
        expireOldPendingChanges(playerId, OffsetDateTime.now());

        List<PostMatchPendingChangeEntry> changes = toPendingChangeEntries(
            repository.findPendingChanges(playerId, normalizedStatus)
        );
        return new PostMatchPendingChangePage(playerId, changes);
    }

    /**
     * Применяет решение игрока: применить изменение, если preset не ушел вперед, или отклонить его.
     */
    @Transactional
    public PostMatchPendingChangeResolution resolve(
        UUID playerId,
        UUID changeId,
        String requestedResolution
    ) {
        PendingChange change = lockPendingChange(playerId, changeId);
        if (change == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PENDING_CHANGE_NOT_FOUND", "Pending change was not found");
        }

        OffsetDateTime now = OffsetDateTime.now();
        ensurePending(change);
        if (!change.expiresAt().isAfter(now)) {
            updateChangeStatus(change.changeId(), "expired", now);
            throw new ApiException(HttpStatus.CONFLICT, "PENDING_CHANGE_EXPIRED", "Pending change is expired");
        }

        String resolution = requestedResolution.toLowerCase(Locale.ROOT);
        return switch (resolution) {
            case "discard" -> discard(change, now);
            case "apply_if_still_valid" -> applyIfStillValid(change, now);
            case "manual_merge" -> throw new ApiException(
                HttpStatus.NOT_IMPLEMENTED,
                "MANUAL_MERGE_NOT_SUPPORTED",
                "Manual merge resolution is not implemented yet"
            );
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported post-match resolution: " + requestedResolution
            );
        };
    }

    private PostMatchPendingChangeResolution discard(PendingChange change, OffsetDateTime now) {
        updateChangeStatus(change.changeId(), "rejected", now);
        recordPendingChangeResolved(change, "discard", "rejected", null, now);
        return new PostMatchPendingChangeResolution(change.changeId(), "rejected", null, now);
    }

    private PostMatchPendingChangeResolution applyIfStillValid(PendingChange change, OffsetDateTime now) {
        PendingPayload payload = parsePendingPayload(change.payloadJson());
        validatePendingPayload(payload);

        PresetHeader preset = lockWeaponPreset(change);
        if (change.currentConflictingRevision() != null && preset.revision() != change.currentConflictingRevision()) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "PENDING_CHANGE_PRESET_REVISION_MOVED",
                "Weapon preset changed after pending change was created"
            );
        }

        runtimeChangeApplier.apply(
            change.playerId(),
            change.classTag(),
            change.weaponPresetSlot(),
            preset.catalogVersion(),
            payload.runtimeChangePayload(),
            now
        );

        long resultRevision = preset.revision() + 1;
        repository.updateWeaponPresetRevision(
            change.playerId(),
            change.classTag(),
            change.weaponPresetSlot(),
            preset.catalogVersion(),
            resultRevision,
            now
        );
        outboxService.record(
            "weapon_preset.post_match_applied",
            "weapon_preset",
            weaponPresetAggregateId(change.playerId(), change.classTag(), change.weaponPresetSlot(), preset.catalogVersion()),
            1,
            Map.of(
                "player_id", change.playerId(),
                "match_id", change.matchId(),
                "pending_change_id", change.changeId(),
                "class_tag", change.classTag(),
                "preset_slot", change.weaponPresetSlot(),
                "catalog_version", preset.catalogVersion(),
                "base_revision", change.baseWeaponPresetRevision(),
                "revision", resultRevision,
                "source", "post_match"
            ),
            now
        );
        updateChangeStatus(change.changeId(), "applied", now);
        recordPendingChangeResolved(change, "apply_if_still_valid", "applied", resultRevision, now);
        return new PostMatchPendingChangeResolution(change.changeId(), "applied", resultRevision, now);
    }

    private void recordPendingChangeResolved(
        PendingChange change,
        String resolution,
        String status,
        Long resultRevision,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player_id", change.playerId());
        payload.put("match_id", change.matchId());
        payload.put("pending_change_id", change.changeId());
        payload.put("class_tag", change.classTag());
        payload.put("preset_slot", change.weaponPresetSlot());
        payload.put("base_revision", change.baseWeaponPresetRevision());
        payload.put("resolution", resolution);
        payload.put("status", status);
        payload.put("source", "post_match");
        if (resultRevision != null) {
            payload.put("result_revision", resultRevision);
        }

        outboxService.record(
            "post_match_pending_change.resolved",
            "post_match_pending_change",
            change.changeId().toString(),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            change.playerId(),
            "post_match_pending_change.resolved",
            "post_match_pending_change",
            change.changeId().toString(),
            1,
            payload,
            now
        );
    }

    private void expireOldPendingChanges(UUID playerId, OffsetDateTime now) {
        repository.expireOldPendingChanges(playerId, now);
    }

    private String normalizeStatus(String status) {
        String normalized = status == null ? "pending" : status.toLowerCase(Locale.ROOT);
        if (!READABLE_STATUSES.contains(normalized)) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported pending change status: " + status
            );
        }
        return normalized;
    }

    private PendingChange lockPendingChange(UUID playerId, UUID changeId) {
        List<PendingChange> changes = repository.lockPendingChanges(playerId, changeId);
        return changes.isEmpty() ? null : changes.getFirst();
    }

    private PresetHeader lockWeaponPreset(PendingChange change) {
        List<PresetHeader> presets = repository.lockWeaponPreset(change.playerId(), change.classTag(), change.weaponPresetSlot());
        if (presets.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAPON_PRESET_NOT_FOUND", "Weapon preset was not found");
        }
        return presets.getFirst();
    }

    private void ensurePending(PendingChange change) {
        if (!"pending".equals(change.status())) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "PENDING_CHANGE_ALREADY_RESOLVED",
                "Pending change status is already " + change.status()
            );
        }
    }

    private void updateChangeStatus(UUID changeId, String status, OffsetDateTime resolvedAt) {
        repository.updateChangeStatus(changeId, status, resolvedAt);
    }

    private List<PostMatchPendingChangeEntry> toPendingChangeEntries(List<PendingChangeSummary> changes) {
        return changes.stream()
            .map(change -> new PostMatchPendingChangeEntry(
                change.changeId(),
                change.matchId(),
                change.classTag(),
                change.weaponPresetSlot(),
                change.baseWeaponPresetRevision(),
                change.currentConflictingRevision(),
                change.reasonCode(),
                change.status(),
                parsePayloadMap(change.payloadJson()),
                change.createdAt(),
                change.expiresAt(),
                change.resolvedAt()
            ))
            .toList();
    }

    private Map<String, Object> parsePayloadMap(String payload) {
        try {
            return objectMapper.readValue(payload, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PENDING_CHANGE_PAYLOAD_PARSE_FAILED", "Unable to parse pending change payload");
        }
    }

    private PendingPayload parsePendingPayload(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            RuntimePresetChangePayload runtimePayload = objectMapper.treeToValue(
                root.get("runtime_change_payload"),
                RuntimePresetChangePayload.class
            );
            return new PendingPayload(
                root.path("schema_version").asInt(),
                runtimePayload
            );
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "PENDING_CHANGE_PAYLOAD_PARSE_FAILED", "Unable to parse pending change payload");
        }
    }

    private String weaponPresetAggregateId(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return "weapon_preset:" + playerId + ":" + classTag + ":" + presetSlot + ":" + catalogVersion;
    }

    private void validatePendingPayload(PendingPayload payload) {
        if (payload.schemaVersion() != 1 || payload.runtimeChangePayload() == null || payload.runtimeChangePayload().schemaVersion() != 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Only pending change payload schema_version=1 is supported");
        }
    }

    private record PendingPayload(
        int schemaVersion,
        RuntimePresetChangePayload runtimeChangePayload
    ) {
    }

}
