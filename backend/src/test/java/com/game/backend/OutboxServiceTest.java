package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.outbox.repository.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxServiceTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OutboxService service = new OutboxService(repository, objectMapper);

    @Test
    void catalogLifecycleRecorderShouldPersistExpectedPayloadShape() throws Exception {
        UUID operationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:00:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordCatalogLifecycleChanged(
            "catalog.publish",
            operationId,
            "global",
            1L,
            2L,
            3,
            4,
            5,
            6,
            now
        );

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("catalog.publish"),
            eq("catalog_deployment"),
            eq("global:2"),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("operation_id").asText()).isEqualTo(operationId.toString());
        assertThat(json.path("realm_id").asText()).isEqualTo("global");
        assertThat(json.path("previous_catalog_version").asLong()).isEqualTo(1L);
        assertThat(json.path("active_catalog_version").asLong()).isEqualTo(2L);
        assertThat(json.path("migrated_weapon_presets").asInt()).isEqualTo(3);
        assertThat(json.path("migrated_outfit_presets").asInt()).isEqualTo(4);
        assertThat(json.path("migrated_access_players").asInt()).isEqualTo(5);
        assertThat(json.path("stale_match_profiles").asInt()).isEqualTo(6);
    }

    @Test
    void playerCacheInvalidatedRecorderShouldPersistExpectedPayloadShape() throws Exception {
        UUID playerId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:10:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordPlayerCacheInvalidated(playerId, 7, "admin:test", "manual", now);

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("player_cache.invalidated"),
            eq("player"),
            eq(playerId.toString()),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("player_id").asText()).isEqualTo(playerId.toString());
        assertThat(json.path("stale_match_profiles").asInt()).isEqualTo(7);
        assertThat(json.path("actor_id").asText()).isEqualTo("admin:test");
        assertThat(json.path("reason").asText()).isEqualTo("manual");
    }

    @Test
    void runtimeChangedRecorderShouldPersistExpectedPayloadShape() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:20:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordWeaponPresetRuntimeChanged(
            playerId,
            matchId,
            operationId,
            "class.assault",
            1,
            2L,
            4L,
            5L,
            now
        );

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("weapon_preset.runtime_changed"),
            eq("weapon_preset"),
            eq(playerId + ":class.assault:1:2"),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("player_id").asText()).isEqualTo(playerId.toString());
        assertThat(json.path("match_id").asText()).isEqualTo(matchId.toString());
        assertThat(json.path("operation_id").asText()).isEqualTo(operationId.toString());
        assertThat(json.path("class_tag").asText()).isEqualTo("class.assault");
        assertThat(json.path("preset_slot").asInt()).isEqualTo(1);
        assertThat(json.path("catalog_version").asLong()).isEqualTo(2L);
        assertThat(json.path("base_revision").asLong()).isEqualTo(4L);
        assertThat(json.path("revision").asLong()).isEqualTo(5L);
        assertThat(json.path("source").asText()).isEqualTo("runtime");
    }

    @Test
    void postMatchAppliedRecorderShouldPersistExpectedPayloadShape() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID pendingChangeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:30:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordWeaponPresetPostMatchApplied(
            playerId,
            matchId,
            pendingChangeId,
            "class.assault",
            1,
            2L,
            4L,
            5L,
            now
        );

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("weapon_preset.post_match_applied"),
            eq("weapon_preset"),
            eq("weapon_preset:" + playerId + ":class.assault:1:2"),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("player_id").asText()).isEqualTo(playerId.toString());
        assertThat(json.path("match_id").asText()).isEqualTo(matchId.toString());
        assertThat(json.path("pending_change_id").asText()).isEqualTo(pendingChangeId.toString());
        assertThat(json.path("class_tag").asText()).isEqualTo("class.assault");
        assertThat(json.path("preset_slot").asInt()).isEqualTo(1);
        assertThat(json.path("catalog_version").asLong()).isEqualTo(2L);
        assertThat(json.path("base_revision").asLong()).isEqualTo(4L);
        assertThat(json.path("revision").asLong()).isEqualTo(5L);
        assertThat(json.path("source").asText()).isEqualTo("post_match");
    }

    @Test
    void postMatchResolvedRecorderShouldPersistExpectedPayloadShape() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID pendingChangeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:40:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordPostMatchPendingChangeResolved(
            playerId,
            matchId,
            pendingChangeId,
            "class.assault",
            1,
            4L,
            "apply_if_still_valid",
            "applied",
            5L,
            now
        );

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("post_match_pending_change.resolved"),
            eq("post_match_pending_change"),
            eq(pendingChangeId.toString()),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.path("player_id").asText()).isEqualTo(playerId.toString());
        assertThat(json.path("match_id").asText()).isEqualTo(matchId.toString());
        assertThat(json.path("pending_change_id").asText()).isEqualTo(pendingChangeId.toString());
        assertThat(json.path("class_tag").asText()).isEqualTo("class.assault");
        assertThat(json.path("preset_slot").asInt()).isEqualTo(1);
        assertThat(json.path("base_revision").asLong()).isEqualTo(4L);
        assertThat(json.path("resolution").asText()).isEqualTo("apply_if_still_valid");
        assertThat(json.path("status").asText()).isEqualTo("applied");
        assertThat(json.path("result_revision").asLong()).isEqualTo(5L);
        assertThat(json.path("source").asText()).isEqualTo("post_match");
    }

    @Test
    void postMatchResolvedRecorderShouldOmitNullResultRevision() throws Exception {
        UUID playerId = UUID.randomUUID();
        UUID matchId = UUID.randomUUID();
        UUID pendingChangeId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.parse("2026-07-04T12:50:00Z");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);

        service.recordPostMatchPendingChangeResolved(
            playerId,
            matchId,
            pendingChangeId,
            "class.assault",
            1,
            4L,
            "discard",
            "rejected",
            null,
            now
        );

        verify(repository).insertPendingEvent(
            any(UUID.class),
            eq("post_match_pending_change.resolved"),
            eq("post_match_pending_change"),
            eq(pendingChangeId.toString()),
            payload.capture(),
            eq(1),
            eq(now)
        );
        JsonNode json = objectMapper.readTree(payload.getValue());
        assertThat(json.has("result_revision")).isFalse();
        assertThat(json.path("resolution").asText()).isEqualTo("discard");
        assertThat(json.path("status").asText()).isEqualTo("rejected");
    }
}
