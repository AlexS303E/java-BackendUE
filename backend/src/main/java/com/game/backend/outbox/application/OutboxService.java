package com.game.backend.outbox.application;

import com.game.backend.outbox.repository.OutboxRepository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Записывает доменные события в outbox_events внутри текущей транзакции.
 */
@Service
public class OutboxService {
    private final OutboxRepository repository;
    private final ObjectMapper objectMapper;

    public OutboxService(OutboxRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Создает pending outbox event, который позже сможет обработать отдельный worker.
     */
    public void record(
        String eventType,
        String aggregateType,
        String aggregateId,
        int payloadSchemaVersion,
        Map<String, Object> payload,
        OffsetDateTime now
    ) {
        repository.insertPendingEvent(
            UUID.randomUUID(),
            eventType,
            aggregateType,
            aggregateId,
            toJson(payload),
            payloadSchemaVersion,
            now
        );
    }

    public void recordPlayerAccessChanged(
        UUID playerId,
        String itemId,
        long catalogVersion,
        long accessRevision,
        UUID ledgerEventId,
        String actorId,
        String source,
        OffsetDateTime now
    ) {
        record(
            "player_access.changed",
            "player_access",
            playerId.toString(),
            1,
            Map.of(
                "player_id", playerId,
                "item_id", itemId,
                "catalog_version", catalogVersion,
                "access_revision", accessRevision,
                "ledger_event_id", ledgerEventId,
                "actor_id", actorId,
                "source", source
            ),
            now
        );
    }

    public void recordMatchProfileStaled(
        UUID playerId,
        Long catalogVersion,
        String staleReason,
        int staleProfiles,
        UUID sourceEventId,
        String source,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player_id", playerId);
        if (catalogVersion != null) {
            payload.put("catalog_version", catalogVersion);
        }
        payload.put("stale_reason", staleReason);
        payload.put("stale_profiles", staleProfiles);
        payload.put("source_event_id", sourceEventId);
        payload.put("source", source);

        record(
            "match_profile.staled",
            "player_match_profile",
            playerId.toString(),
            1,
            payload,
            now
        );
    }

    public void recordWeaponPresetSaved(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long previousRevision,
        long revision,
        String source,
        OffsetDateTime now
    ) {
        record(
            "weapon_preset.saved",
            "weapon_preset",
            weaponPresetAggregateId(playerId, classTag, presetSlot, catalogVersion),
            1,
            Map.of(
                "player_id", playerId,
                "class_tag", classTag,
                "preset_slot", presetSlot,
                "catalog_version", catalogVersion,
                "previous_revision", previousRevision,
                "revision", revision,
                "source", source
            ),
            now
        );
    }

    public void recordWeaponPresetSanitized(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        String removedItemId,
        String removedItemType,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        record(
            "weapon_preset.sanitized",
            "weapon_preset",
            weaponPresetAggregateId(playerId, classTag, presetSlot, catalogVersion),
            1,
            presetSanitizedPayload(
                playerId,
                classTag,
                presetSlot,
                catalogVersion,
                revision,
                removedItemId,
                removedItemType,
                source,
                sourceEventId
            ),
            now
        );
    }

    public void recordOutfitPresetSanitized(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        String removedItemId,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        record(
            "outfit_preset.sanitized",
            "outfit_preset",
            outfitPresetAggregateId(playerId, teamTag, classTag, outfitPresetSlot, catalogVersion),
            1,
            outfitPresetSanitizedPayload(
                playerId,
                teamTag,
                classTag,
                outfitPresetSlot,
                catalogVersion,
                revision,
                removedItemId,
                source,
                sourceEventId
            ),
            now
        );
    }

    public void recordCatalogLifecycleChanged(
        String eventType,
        UUID operationId,
        String realmId,
        long previousCatalogVersion,
        long activeCatalogVersion,
        int migratedWeaponPresets,
        int migratedOutfitPresets,
        int migratedAccessPlayers,
        int staleMatchProfiles,
        OffsetDateTime now
    ) {
        if (!"catalog.publish".equals(eventType) && !"catalog.rollback".equals(eventType)) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OUTBOX_EVENT_TYPE_INVALID", "Unsupported catalog lifecycle outbox event");
        }
        record(
            eventType,
            "catalog_deployment",
            realmId + ":" + activeCatalogVersion,
            1,
            Map.of(
                "operation_id", operationId,
                "realm_id", realmId,
                "previous_catalog_version", previousCatalogVersion,
                "active_catalog_version", activeCatalogVersion,
                "migrated_weapon_presets", migratedWeaponPresets,
                "migrated_outfit_presets", migratedOutfitPresets,
                "migrated_access_players", migratedAccessPlayers,
                "stale_match_profiles", staleMatchProfiles
            ),
            now
        );
    }

    private Map<String, Object> presetSanitizedPayload(
        UUID playerId,
        String classTag,
        int presetSlot,
        long catalogVersion,
        long revision,
        String removedItemId,
        String removedItemType,
        String source,
        UUID sourceEventId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player_id", playerId);
        payload.put("class_tag", classTag);
        payload.put("preset_slot", presetSlot);
        payload.put("catalog_version", catalogVersion);
        payload.put("revision", revision);
        payload.put("removed_item_id", removedItemId);
        payload.put("removed_item_type", removedItemType);
        payload.put("source", source);
        payload.put("source_event_id", sourceEventId);
        return payload;
    }

    private Map<String, Object> outfitPresetSanitizedPayload(
        UUID playerId,
        String teamTag,
        String classTag,
        int outfitPresetSlot,
        long catalogVersion,
        long revision,
        String removedItemId,
        String source,
        UUID sourceEventId
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player_id", playerId);
        payload.put("team_tag", teamTag);
        payload.put("class_tag", classTag);
        payload.put("outfit_preset_slot", outfitPresetSlot);
        payload.put("catalog_version", catalogVersion);
        payload.put("revision", revision);
        payload.put("removed_item_id", removedItemId);
        payload.put("removed_item_type", "clothing");
        payload.put("source", source);
        payload.put("source_event_id", sourceEventId);
        return payload;
    }

    private String weaponPresetAggregateId(UUID playerId, String classTag, int presetSlot, long catalogVersion) {
        return playerId + ":" + classTag + ":" + presetSlot + ":" + catalogVersion;
    }

    private String outfitPresetAggregateId(UUID playerId, String teamTag, String classTag, int outfitPresetSlot, long catalogVersion) {
        return playerId + ":" + teamTag + ":" + classTag + ":" + outfitPresetSlot + ":" + catalogVersion;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "OUTBOX_PAYLOAD_SERIALIZATION_FAILED", "Unable to serialize outbox payload");
        }
    }
}
