package com.game.backend.runtimechanges.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeRequest;
import com.game.backend.runtimechanges.api.RuntimePresetChangeResponse;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimePresetChangeService {
    private static final int PENDING_TTL_DAYS = 7;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public RuntimePresetChangeService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RuntimePresetChangeResponse submit(String idempotencyKey, RuntimePresetChangeRequest request) {
        validateIdempotencyKey(idempotencyKey, request);
        validatePayload(request.runtimeChangePayload());

        String requestHash = requestHash(request);
        ExistingOperation existing = existingOperation(request.operationId());
        if (existing != null) {
            return replayExistingOperation(request, requestHash, existing);
        }
        ensureOperationSequenceIsUnused(request);

        OffsetDateTime now = OffsetDateTime.now();
        PresetHeader preset = lockWeaponPreset(request);
        if (preset.revision() != request.baseWeaponPresetRevision()) {
            UUID pendingChangeId = createPendingChange(request, preset.revision(), now);
            insertOperation(request, "conflict", null, pendingChangeId, requestHash, now);
            return new RuntimePresetChangeResponse(
                request.operationId(),
                "conflict",
                null,
                pendingChangeId,
                false,
                "PRESET_REVISION_CONFLICT"
            );
        }

        for (RuntimePresetChangeStep change : request.runtimeChangePayload().changes()) {
            applyChange(request, preset.catalogVersion(), change, now);
        }

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
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            preset.catalogVersion()
        );

        insertOperation(request, "applied", resultRevision, null, requestHash, now);
        return new RuntimePresetChangeResponse(
            request.operationId(),
            "applied",
            resultRevision,
            null,
            false,
            null
        );
    }

    private void validateIdempotencyKey(String idempotencyKey, RuntimePresetChangeRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required"
            );
        }
        if (!idempotencyKey.equalsIgnoreCase(request.operationId().toString())) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_OPERATION_ID_MISMATCH",
                "Idempotency-Key must equal body.operation_id"
            );
        }
    }

    private void validatePayload(RuntimePresetChangePayload payload) {
        if (payload.schemaVersion() != 1) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Only RuntimePresetChangePayload schema_version=1 is supported"
            );
        }
    }

    private RuntimePresetChangeResponse replayExistingOperation(
        RuntimePresetChangeRequest request,
        String requestHash,
        ExistingOperation existing
    ) {
        if (!existing.requestHash().equals(requestHash)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                "Runtime operation id was reused with a different request body"
            );
        }
        return new RuntimePresetChangeResponse(
            request.operationId(),
            existing.status(),
            existing.resultRevision(),
            existing.pendingChangeId(),
            true,
            "conflict".equals(existing.status()) ? "PRESET_REVISION_CONFLICT" : null
        );
    }

    private ExistingOperation existingOperation(UUID operationId) {
        List<ExistingOperation> operations = jdbcTemplate.query(
            """
                SELECT status, result_revision, pending_change_id, request_hash
                FROM runtime_preset_change_operations
                WHERE operation_id = ?
                """,
            (rs, rowNum) -> new ExistingOperation(
                rs.getString("status"),
                rs.getObject("result_revision", Long.class),
                rs.getObject("pending_change_id", UUID.class),
                rs.getString("request_hash")
            ),
            operationId
        );
        return operations.isEmpty() ? null : operations.getFirst();
    }

    private void ensureOperationSequenceIsUnused(RuntimePresetChangeRequest request) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM runtime_preset_change_operations
                  WHERE match_id = ?
                    AND player_id = ?
                    AND operation_seq = ?
                )
                """,
            Boolean.class,
            request.matchId(),
            request.playerId(),
            request.operationSeq()
        );
        if (Boolean.TRUE.equals(exists)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUNTIME_OPERATION_SEQ_ALREADY_USED",
                "Runtime operation sequence was already used for this match and player"
            );
        }
    }

    private PresetHeader lockWeaponPreset(RuntimePresetChangeRequest request) {
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
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot()
        );
        if (presets.isEmpty()) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "WEAPON_PRESET_NOT_FOUND",
                "Weapon preset was not found"
            );
        }
        return presets.getFirst();
    }

    private UUID createPendingChange(RuntimePresetChangeRequest request, long currentRevision, OffsetDateTime now) {
        UUID changeId = UUID.randomUUID();
        jdbcTemplate.update(
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
                VALUES (?, ?, ?, ?, ?, ?, ?, 'revision_conflict', 'pending', ?::jsonb, 1, ?, ?)
                """,
            changeId,
            request.playerId(),
            request.matchId(),
            request.classTag(),
            request.weaponPresetSlot(),
            request.baseWeaponPresetRevision(),
            currentRevision,
            pendingPayload(request, currentRevision),
            now,
            now.plusDays(PENDING_TTL_DAYS)
        );
        return changeId;
    }

    private String pendingPayload(RuntimePresetChangeRequest request, long currentRevision) {
        Map<String, Object> payload = Map.of(
            "schema_version", 1,
            "runtime_change_payload", request.runtimeChangePayload(),
            "conflict", Map.of(
                "reason_code", "revision_conflict",
                "base_weapon_preset_revision", request.baseWeaponPresetRevision(),
                "current_weapon_preset_revision", currentRevision
            ),
            "resolution_options", List.of("apply_if_still_valid", "discard", "manual_merge")
        );
        return toJson(payload);
    }

    private void applyChange(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        switch (change.op()) {
            case "set_weapon" -> setWeapon(request, catalogVersion, change, now);
            case "clear_weapon" -> clearWeapon(request, catalogVersion, change);
            case "set_module" -> setModule(request, catalogVersion, change, now);
            case "clear_module" -> clearModule(request, catalogVersion, change);
            default -> throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "Unsupported runtime preset change op: " + change.op()
            );
        }
    }

    private void setWeapon(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        requireField(change.weaponId(), "weapon_id", change.op());
        validateWeaponSlotAllowed(request.classTag(), change.weaponSlotId());
        validateCanUse(request.playerId(), change.weaponId(), catalogVersion, request.classTag(), "all", "weapon");
        upsertSelectedSlot(request, catalogVersion, change.weaponSlotId(), change.weaponId());
        upsertWeaponConfig(request, catalogVersion, change.weaponSlotId(), change.weaponId(), now);
    }

    private void clearWeapon(RuntimePresetChangeRequest request, long catalogVersion, RuntimePresetChangeStep change) {
        validateWeaponSlotAllowed(request.classTag(), change.weaponSlotId());
        upsertSelectedSlot(request, catalogVersion, change.weaponSlotId(), null);
    }

    private void setModule(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        RuntimePresetChangeStep change,
        OffsetDateTime now
    ) {
        requireField(change.weaponId(), "weapon_id", change.op());
        requireField(change.mountId(), "mount_id", change.op());
        requireField(change.moduleId(), "module_id", change.op());
        validateWeaponSlotAllowed(request.classTag(), change.weaponSlotId());
        validateSelectedWeapon(request, catalogVersion, change.weaponSlotId(), change.weaponId());
        validateCanUse(request.playerId(), change.weaponId(), catalogVersion, request.classTag(), "all", "weapon");
        validateCanUse(request.playerId(), change.moduleId(), catalogVersion, request.classTag(), "all", "module");
        validateMountModuleAllowed(catalogVersion, change.weaponId(), change.mountId(), change.moduleId());
        upsertWeaponConfig(request, catalogVersion, change.weaponSlotId(), change.weaponId(), now);
        replaceSingleModule(request, catalogVersion, change);
    }

    private void clearModule(RuntimePresetChangeRequest request, long catalogVersion, RuntimePresetChangeStep change) {
        requireField(change.weaponId(), "weapon_id", change.op());
        requireField(change.mountId(), "mount_id", change.op());
        validateWeaponSlotAllowed(request.classTag(), change.weaponSlotId());
        validateSelectedWeapon(request, catalogVersion, change.weaponSlotId(), change.weaponId());
        jdbcTemplate.update(
            """
                DELETE FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_slot_id = ?
                  AND weapon_id = ?
                  AND mount_id = ?
                """,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId()
        );
    }

    private void requireField(String value, String fieldName, String op) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                fieldName + " is required for op " + op
            );
        }
    }

    private void validateWeaponSlotAllowed(String classTag, String weaponSlotId) {
        Boolean allowed = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM class_weapon_slot_rules
                  WHERE class_tag = ?
                    AND weapon_slot_id = ?
                    AND is_allowed = true
                )
                """,
            Boolean.class,
            classTag,
            weaponSlotId
        );
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Weapon slot is not allowed for class: " + weaponSlotId
            );
        }
    }

    private void validateSelectedWeapon(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        Boolean matches = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM player_weapon_preset_slots
                  WHERE player_id = ?
                    AND class_tag = ?
                    AND preset_slot = ?
                    AND catalog_version = ?
                    AND weapon_slot_id = ?
                    AND selected_weapon_id = ?
                )
                """,
            Boolean.class,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            weaponSlotId,
            weaponId
        );
        if (!Boolean.TRUE.equals(matches)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Runtime module change targets a weapon that is not selected in slot: " + weaponSlotId
            );
        }
    }

    private void validateCanUse(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String teamTag,
        String itemType
    ) {
        Boolean canUse = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items ci
                  JOIN player_item_access pia
                    ON pia.item_id = ci.item_id
                   AND pia.catalog_version = ci.catalog_version
                   AND pia.player_id = ?
                  WHERE ci.item_id = ?
                    AND ci.catalog_version = ?
                    AND ci.item_type = ?
                    AND ci.is_enabled = true
                    AND pia.is_hidden = false
                    AND pia.is_locked_in_shop = false
                    AND pia.is_locked_by_quest = false
                    AND pia.is_disabled = false
                    AND EXISTS (
                      SELECT 1
                      FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'allow'
                    )
                    AND EXISTS (
                      SELECT 1
                      FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                        AND (
                          itr.team_scope = 'all'
                          OR (itr.team_scope = 'specific' AND itr.team_tag = ?)
                        )
                    )
                )
                """,
            Boolean.class,
            playerId,
            itemId,
            catalogVersion,
            itemType,
            classTag,
            teamTag
        );
        if (!Boolean.TRUE.equals(canUse)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Item is not usable in runtime preset change: " + itemId
            );
        }
    }

    private void validateMountModuleAllowed(long catalogVersion, String weaponId, String mountId, String moduleId) {
        Boolean allowed = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM weapon_module_mounts wmm
                  JOIN weapon_mount_allowed_modules wmam
                    ON wmam.mount_id = wmm.mount_id
                   AND wmam.catalog_version = wmm.catalog_version
                  WHERE wmm.catalog_version = ?
                    AND wmm.weapon_id = ?
                    AND wmm.mount_id = ?
                    AND wmam.module_id = ?
                )
                """,
            Boolean.class,
            catalogVersion,
            weaponId,
            mountId,
            moduleId
        );
        if (!Boolean.TRUE.equals(allowed)) {
            throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "LOADOUT_VALIDATION_FAILED",
                "Module is not allowed for weapon mount: " + moduleId
            );
        }
    }

    private void upsertSelectedSlot(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO player_weapon_preset_slots(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  selected_weapon_id
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (player_id, class_tag, preset_slot, catalog_version, weapon_slot_id)
                DO UPDATE SET selected_weapon_id = EXCLUDED.selected_weapon_id
                """,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    private void upsertWeaponConfig(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        OffsetDateTime now
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO player_weapon_preset_weapon_configs(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  weapon_id,
                  config_revision,
                  last_used_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id)
                DO UPDATE SET
                  config_revision = player_weapon_preset_weapon_configs.config_revision + 1,
                  last_used_at = EXCLUDED.last_used_at
                """,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            weaponSlotId,
            weaponId,
            now
        );
    }

    private void replaceSingleModule(
        RuntimePresetChangeRequest request,
        long catalogVersion,
        RuntimePresetChangeStep change
    ) {
        jdbcTemplate.update(
            """
                DELETE FROM player_weapon_preset_weapon_config_modules
                WHERE player_id = ?
                  AND class_tag = ?
                  AND preset_slot = ?
                  AND catalog_version = ?
                  AND weapon_slot_id = ?
                  AND weapon_id = ?
                  AND mount_id = ?
                """,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId()
        );

        jdbcTemplate.update(
            """
                INSERT INTO player_weapon_preset_weapon_config_modules(
                  player_id,
                  class_tag,
                  preset_slot,
                  catalog_version,
                  weapon_slot_id,
                  weapon_id,
                  mount_id,
                  module_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion,
            change.weaponSlotId(),
            change.weaponId(),
            change.mountId(),
            change.moduleId()
        );
    }

    private void insertOperation(
        RuntimePresetChangeRequest request,
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash,
        OffsetDateTime now
    ) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO runtime_preset_change_operations(
                      operation_id,
                      match_id,
                      player_id,
                      operation_seq,
                      class_tag,
                      weapon_preset_slot,
                      base_weapon_preset_revision,
                      status,
                      result_revision,
                      pending_change_id,
                      request_hash,
                      created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                request.operationId(),
                request.matchId(),
                request.playerId(),
                request.operationSeq(),
                request.classTag(),
                request.weaponPresetSlot(),
                request.baseWeaponPresetRevision(),
                status,
                resultRevision,
                pendingChangeId,
                requestHash,
                now
            );
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "RUNTIME_OPERATION_ALREADY_RECORDED",
                "Runtime preset change operation was already recorded"
            );
        }
    }

    private String requestHash(RuntimePresetChangeRequest request) {
        return sha256(toJson(request));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "RUNTIME_CHANGE_SERIALIZATION_FAILED",
                "Unable to serialize runtime preset change"
            );
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "REQUEST_HASH_FAILED",
                "Unable to hash runtime preset change request"
            );
        }
    }

    private record PresetHeader(long catalogVersion, long revision) {
    }

    private record ExistingOperation(
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash
    ) {
    }
}
