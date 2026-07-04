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
}
