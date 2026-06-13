package com.game.backend.runtimechanges.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public class RuntimeChangesRepository extends JdbcRepository {
    public RuntimeChangesRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void ensureOperationStream(UUID matchId, UUID playerId) {
        update(
            """
                INSERT INTO runtime_operation_streams (match_id, player_id, last_applied_seq)
                VALUES (?, ?, 0)
                ON CONFLICT (match_id, player_id) DO NOTHING
                """,
            matchId,
            playerId
        );
    }

    public Long lockOperationStream(UUID matchId, UUID playerId) {
        return queryForObject(
            """
                SELECT last_applied_seq
                FROM runtime_operation_streams
                WHERE match_id = ?
                  AND player_id = ?
                FOR UPDATE
                """,
            Long.class,
            matchId,
            playerId
        );
    }

    public void advanceOperationStream(UUID matchId, UUID playerId, long operationSeq) {
        update(
            """
                UPDATE runtime_operation_streams
                SET last_applied_seq = ?
                WHERE match_id = ?
                  AND player_id = ?
                """,
            operationSeq,
            matchId,
            playerId
        );
    }

    public List<RuntimeOperationRecord> findOperation(UUID operationId) {
        return query(
            """
                SELECT status, result_revision, pending_change_id, request_hash
                FROM runtime_preset_change_operations
                WHERE operation_id = ?
                """,
            (rs, rowNum) -> new RuntimeOperationRecord(
                rs.getString("status"),
                rs.getObject("result_revision", Long.class),
                rs.getObject("pending_change_id", UUID.class),
                rs.getString("request_hash")
            ),
            operationId
        );
    }

    public int insertProcessingOperation(
        UUID operationId,
        UUID matchId,
        UUID playerId,
        long operationSeq,
        String classTag,
        int weaponPresetSlot,
        long baseWeaponPresetRevision,
        String requestHash,
        OffsetDateTime createdAt
    ) {
        return update(
            """
                INSERT INTO runtime_preset_change_operations(
                  operation_id, match_id, player_id, operation_seq,
                  class_tag, weapon_preset_slot, base_weapon_preset_revision,
                  status, result_revision, pending_change_id,
                  request_hash, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'processing', NULL, NULL, ?, ?)
                ON CONFLICT (operation_id) DO NOTHING
                """,
            operationId,
            matchId,
            playerId,
            operationSeq,
            classTag,
            weaponPresetSlot,
            baseWeaponPresetRevision,
            requestHash,
            createdAt
        );
    }

    public void updateOperationStatus(
        UUID operationId,
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        OffsetDateTime updatedAt
    ) {
        update(
            """
                UPDATE runtime_preset_change_operations
                SET status = ?,
                    result_revision = ?,
                    pending_change_id = ?,
                    updated_at = ?
                WHERE operation_id = ?
                """,
            status, resultRevision, pendingChangeId, updatedAt, operationId
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
        OffsetDateTime updatedAt
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
            updatedAt,
            playerId,
            classTag,
            presetSlot,
            catalogVersion
        );
    }

    public void insertPostMatchPendingChange(
        UUID changeId,
        UUID playerId,
        UUID matchId,
        String classTag,
        int weaponPresetSlot,
        long baseWeaponPresetRevision,
        long currentConflictingRevision,
        String reasonCode,
        String payload,
        OffsetDateTime createdAt,
        OffsetDateTime expiresAt
    ) {
        update(
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
            playerId,
            matchId,
            classTag,
            weaponPresetSlot,
            baseWeaponPresetRevision,
            currentConflictingRevision,
            reasonCode,
            payload,
            createdAt,
            expiresAt
        );
    }

    public void upsertSelectedWeaponSlot(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId
        );
    }

    public void upsertWeaponConfig(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        OffsetDateTime lastUsedAt
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            lastUsedAt
        );
    }

    public void deleteWeaponConfigModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            mountId
        );
    }

    public void insertWeaponConfigModule(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        String weaponSlotId,
        String weaponId,
        String mountId,
        String moduleId
    ) {
        update(
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
            playerId,
            classTag,
            presetSlot,
            catalogVersion,
            weaponSlotId,
            weaponId,
            mountId,
            moduleId
        );
    }

    public record RuntimeOperationRecord(
        String status,
        Long resultRevision,
        UUID pendingChangeId,
        String requestHash
    ) {
    }

    public record PresetHeader(long catalogVersion, long revision) {
    }
}
