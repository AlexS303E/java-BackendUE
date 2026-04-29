package com.game.backend.postmatch.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.postmatch.api.PostMatchPendingChangeDto;
import com.game.backend.postmatch.api.PostMatchPendingChangeResolutionRequest;
import com.game.backend.postmatch.api.PostMatchPendingChangeResolutionResponse;
import com.game.backend.postmatch.api.PostMatchPendingChangesResponse;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.application.WeaponPresetRuntimeChangeApplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final WeaponPresetRuntimeChangeApplier runtimeChangeApplier;

    public PostMatchPendingChangesService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        WeaponPresetRuntimeChangeApplier runtimeChangeApplier
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.runtimeChangeApplier = runtimeChangeApplier;
    }

    /**
     * Возвращает changes игрока по статусу и по пути истечает просроченные pending rows.
     */
    @Transactional
    public PostMatchPendingChangesResponse getChanges(UUID playerId, String status) {
        String normalizedStatus = normalizeStatus(status);
        expireOldPendingChanges(playerId, OffsetDateTime.now());

        List<PostMatchPendingChangeDto> changes = jdbcTemplate.query(
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
                parsePayloadMap(rs.getString("payload")),
                rs.getObject("created_at", OffsetDateTime.class),
                rs.getObject("expires_at", OffsetDateTime.class),
                rs.getObject("resolved_at", OffsetDateTime.class)
            ),
            playerId,
            normalizedStatus
        );
        return new PostMatchPendingChangesResponse(playerId, changes);
    }

    /**
     * Применяет решение игрока: применить изменение, если preset не ушел вперед, или отклонить его.
     */
    @Transactional
    public PostMatchPendingChangeResolutionResponse resolve(
        UUID playerId,
        UUID changeId,
        PostMatchPendingChangeResolutionRequest request
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

        String resolution = request.resolution().toLowerCase(Locale.ROOT);
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
                "Unsupported post-match resolution: " + request.resolution()
            );
        };
    }

    private PostMatchPendingChangeResolutionResponse discard(PendingChange change, OffsetDateTime now) {
        updateChangeStatus(change.changeId(), "rejected", now);
        return new PostMatchPendingChangeResolutionResponse(change.changeId(), "rejected", null, now);
    }

    private PostMatchPendingChangeResolutionResponse applyIfStillValid(PendingChange change, OffsetDateTime now) {
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
        jdbcTemplate.update(
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
            resultRevision,
            now,
            change.playerId(),
            change.classTag(),
            change.weaponPresetSlot(),
            preset.catalogVersion()
        );
        updateChangeStatus(change.changeId(), "applied", now);
        return new PostMatchPendingChangeResolutionResponse(change.changeId(), "applied", resultRevision, now);
    }

    private void expireOldPendingChanges(UUID playerId, OffsetDateTime now) {
        jdbcTemplate.update(
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
        List<PendingChange> changes = jdbcTemplate.query(
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
        return changes.isEmpty() ? null : changes.getFirst();
    }

    private PresetHeader lockWeaponPreset(PendingChange change) {
        List<PresetHeader> presets = jdbcTemplate.query(
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
            change.playerId(),
            change.classTag(),
            change.weaponPresetSlot()
        );
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
        jdbcTemplate.update(
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

    private record PendingChange(
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

    private record PresetHeader(long catalogVersion, long revision) {
    }
}
