package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.outbox.worker-enabled=false",
                // MockMvc does not pass through the real private HTTPS connector.
                // Keep the legacy server header fallback only for this vertical integration test.
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class VerticalFlowIntegrationTest {
    private static final String PASSWORD = "password123";

    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldPassMainPlayerServerRuntimeFlow() throws Exception {
        String loginName = "player_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

        JsonNode registered = json(
                mockMvc.perform(postJson("/auth/register", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.login_name").value(loginName))
                        .andExpect(jsonPath("$.status").value("active"))
                        .andReturn()
        );

        UUID playerId = UUID.fromString(registered.path("player_id").asText());

        JsonNode login = json(
                mockMvc.perform(postJson("/auth/login", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.token_type").value("Bearer"))
                        .andReturn()
        );

        String accessToken = login.path("access_token").asText();
        String refreshToken = login.path("refresh_token").asText();

        mockMvc.perform(get("/me/access")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                .andExpect(jsonPath("$.items").isArray());

        JsonNode presets = json(
                mockMvc.perform(get("/me/presets")
                                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.weapon_presets").isArray())
                        .andExpect(jsonPath("$.outfit_presets").isArray())
                        .andReturn()
        );

        JsonNode weaponPreset = findWeaponPreset(presets, "class.assault", 1);
        long catalogVersion = weaponPreset.path("catalog_version").asLong();
        long initialRevision = weaponPreset.path("revision").asLong();

        Map<String, Object> savePresetBody = weaponPresetSaveBody(catalogVersion);

        JsonNode savedPreset = json(
                mockMvc.perform(putJson("/me/presets/weapons/class.assault/1", savePresetBody)
                                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                                .header(HttpHeaders.IF_MATCH, quoted(initialRevision)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.class_tag").value("class.assault"))
                        .andExpect(jsonPath("$.preset_slot").value(1))
                        .andReturn()
        );

        long savedRevision = savedPreset.path("revision").asLong();
        assertThat(savedRevision).isEqualTo(initialRevision + 1);

        mockMvc.perform(putJson("/me/presets/weapons/class.assault/1", savePresetBody)
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .header(HttpHeaders.IF_MATCH, quoted(initialRevision)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        UUID matchId = UUID.randomUUID();
        Map<String, Object> matchProfileBody = matchProfileBuildBody(
                matchId,
                playerId,
                catalogVersion
        );

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

        JsonNode profile = json(
                mockMvc.perform(postJson("/server/match-profile/build", matchProfileBody)
                                .headers(serverHeaders()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.realm_id").value("global"))
                        .andExpect(jsonPath("$.catalog_version").value(catalogVersion))
                        .andExpect(jsonPath("$.class_tag").value("class.assault"))
                        .andExpect(jsonPath("$.team_tag").value("team.red"))
                        .andReturn()
        );

        JsonNode primary = findWeapon(profile, "primary");
        assertThat(primary.path("weapon_id").asText()).isEqualTo("weapon.ak12");
        assertThat(primary.path("modules").get(0).path("module_id").asText())
                .isEqualTo("module.scope.red_dot_01");

        assertThat(profile.path("dependency_revisions").path("weapon_preset_revision").asLong())
                .isEqualTo(savedRevision);

        UUID runtimeEventId = UUID.randomUUID();
        String runtimeEventIdempotencyKey = "runtime-event:" + runtimeEventId;
        Map<String, Object> runtimeEventBody = runtimeEventBody(
                runtimeEventId,
                1L,
                matchId,
                playerId,
                "loadout_applied"
        );

        mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody)
                        .headers(serverHeaders()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));

        JsonNode runtimeEventRecorded = json(
                mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", runtimeEventIdempotencyKey))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.event_id").value(runtimeEventId.toString()))
                        .andExpect(jsonPath("$.status").value("recorded"))
                        .andExpect(jsonPath("$.duplicate").value(false))
                        .andReturn()
        );

        assertThat(runtimeEventRecorded.path("event_id").asText()).isEqualTo(runtimeEventId.toString());

        mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody)
                        .headers(serverHeaders())
                        .header("Idempotency-Key", runtimeEventIdempotencyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value(runtimeEventId.toString()))
                .andExpect(jsonPath("$.status").value("recorded"))
                .andExpect(jsonPath("$.duplicate").value(true));

        mockMvc.perform(postJson("/server/runtime-events", runtimeEventBody(
                                UUID.randomUUID(),
                                2L,
                                matchId,
                                playerId,
                                "item_used"
                        ))
                        .headers(serverHeaders())
                        .header("Idempotency-Key", runtimeEventIdempotencyKey))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        UUID runtimeOperationId = UUID.randomUUID();
        Map<String, Object> runtimeBody = runtimeSetModuleBody(
                runtimeOperationId,
                1L,
                matchId,
                playerId,
                savedRevision
        );

        JsonNode runtimeApplied = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", runtimeOperationId.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.operation_id").value(runtimeOperationId.toString()))
                        .andExpect(jsonPath("$.status").value("applied"))
                        .andExpect(jsonPath("$.duplicate").value(false))
                        .andReturn()
        );

        long runtimeAppliedRevision = runtimeApplied.path("result_revision").asLong();
        assertThat(runtimeAppliedRevision).isEqualTo(savedRevision + 1);

        JsonNode runtimeReplay = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", runtimeOperationId.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.operation_id").value(runtimeOperationId.toString()))
                        .andExpect(jsonPath("$.status").value("applied"))
                        .andExpect(jsonPath("$.duplicate").value(true))
                        .andReturn()
        );

        assertThat(runtimeReplay.path("result_revision").asLong())
                .isEqualTo(runtimeAppliedRevision);

        UUID conflictOperationId = UUID.randomUUID();
        Map<String, Object> runtimeConflictBody = runtimeClearModuleBody(
                conflictOperationId,
                2L,
                matchId,
                playerId,
                savedRevision
        );

        JsonNode conflict = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeConflictBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", conflictOperationId.toString()))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PRESET_REVISION_CONFLICT"))
                        .andExpect(jsonPath("$.operation_id").value(conflictOperationId.toString()))
                        .andExpect(jsonPath("$.pending_change_id").isNotEmpty())
                        .andReturn()
        );

        String pendingChangeId = conflict.path("pending_change_id").asText();

        JsonNode pendingChanges = json(
                mockMvc.perform(get("/me/post-match-pending-changes")
                                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.changes").isArray())
                        .andReturn()
        );

        JsonNode pendingChange = findPendingChange(pendingChanges, pendingChangeId);
        assertThat(pendingChange.path("status").asText()).isEqualTo("pending");
        assertThat(pendingChange.path("reason_code").asText()).isEqualTo("revision_conflict");
        assertThat(pendingChange.path("base_weapon_preset_revision").asLong())
                .isEqualTo(savedRevision);

        mockMvc.perform(postJson("/auth/logout", Map.of(
                        "refresh_token", refreshToken
                )))
                .andExpect(status().isNoContent());
    }

    private Map<String, Object> weaponPresetSaveBody(long catalogVersion) {
        Map<String, Object> primary = new LinkedHashMap<>();
        primary.put("weapon_slot_id", "primary");
        primary.put("weapon_id", "weapon.ak12");
        primary.put("modules", List.of(Map.of(
                "mount_id", "weapon.ak12.mount.scope.01",
                "module_id", "module.scope.red_dot_01"
        )));

        Map<String, Object> grenade = new LinkedHashMap<>();
        grenade.put("weapon_slot_id", "grenade");
        grenade.put("weapon_id", null);
        grenade.put("modules", List.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("catalog_version", catalogVersion);
        body.put("slots", List.of(primary, grenade));
        return body;
    }

    private Map<String, Object> matchProfileBuildBody(
            UUID matchId,
            UUID playerId,
            long catalogVersion
    ) {
        return Map.ofEntries(
                Map.entry("match_id", matchId.toString()),
                Map.entry("player_id", playerId.toString()),
                Map.entry("realm_id", "global"),
                Map.entry("class_tag", "class.assault"),
                Map.entry("team_tag", "team.red"),
                Map.entry("weapon_preset_slot", 1),
                Map.entry("outfit_preset_slot", 1),
                Map.entry("supported_catalog_versions", List.of(catalogVersion)),
                Map.entry("preferred_catalog_version", catalogVersion),
                Map.entry("server_build_id", devServerBuildId()),
                Map.entry("game_mode_id", "tdm")
        );
    }

    private Map<String, Object> runtimeSetModuleBody(
            UUID operationId,
            long operationSeq,
            UUID matchId,
            UUID playerId,
            long baseRevision
    ) {
        return runtimeBody(
                operationId,
                operationSeq,
                matchId,
                playerId,
                baseRevision,
                Map.of(
                        "op", "set_module",
                        "weapon_slot_id", "primary",
                        "weapon_id", "weapon.ak12",
                        "mount_id", "weapon.ak12.mount.scope.01",
                        "module_id", "module.scope.red_dot_01"
                )
        );
    }

    private Map<String, Object> runtimeClearModuleBody(
            UUID operationId,
            long operationSeq,
            UUID matchId,
            UUID playerId,
            long baseRevision
    ) {
        return runtimeBody(
                operationId,
                operationSeq,
                matchId,
                playerId,
                baseRevision,
                Map.of(
                        "op", "clear_module",
                        "weapon_slot_id", "primary",
                        "weapon_id", "weapon.ak12",
                        "mount_id", "weapon.ak12.mount.scope.01"
                )
        );
    }

    private Map<String, Object> runtimeBody(
            UUID operationId,
            long operationSeq,
            UUID matchId,
            UUID playerId,
            long baseRevision,
            Map<String, Object> change
    ) {
        return Map.of(
                "operation_id", operationId.toString(),
                "operation_seq", operationSeq,
                "match_id", matchId.toString(),
                "player_id", playerId.toString(),
                "class_tag", "class.assault",
                "weapon_preset_slot", 1,
                "base_weapon_preset_revision", baseRevision,
                "runtime_change_payload", Map.of(
                        "schema_version", 1,
                        "changes", List.of(change)
                )
        );
    }

    private Map<String, Object> runtimeEventBody(
            UUID eventId,
            long eventSeq,
            UUID matchId,
            UUID playerId,
            String eventType
    ) {
        return Map.of(
                "event_id", eventId.toString(),
                "event_seq", eventSeq,
                "match_id", matchId.toString(),
                "event_type", eventType,
                "player_id", playerId.toString(),
                "payload_schema_version", 1,
                "occurred_at", OffsetDateTime.now().toString(),
                "payload", Map.of(
                        "source", "vertical_flow_test",
                        "event_type", eventType
                )
        );
    }

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                UUID.fromString(DEV_SERVER_ID)
        );
    }

    private String devServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                UUID.fromString(DEV_SERVER_ID)
        );
    }

    private org.springframework.http.HttpHeaders serverHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Server-Id", DEV_SERVER_ID);
        headers.add("X-Server-Certificate-Fingerprint", devServerFingerprint());
        return headers;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
            String url,
            Object body
    ) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            String url,
            Object body
    ) throws Exception {
        return put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private String quoted(long revision) {
        return "\"" + revision + "\"";
    }

    private JsonNode findWeaponPreset(JsonNode presets, String classTag, int presetSlot) {
        for (JsonNode preset : presets.path("weapon_presets")) {
            if (classTag.equals(preset.path("class_tag").asText())
                    && presetSlot == preset.path("preset_slot").asInt()) {
                return preset;
            }
        }
        throw new AssertionError("Weapon preset not found: " + classTag + "/" + presetSlot);
    }

    private JsonNode findWeapon(JsonNode profile, String weaponSlotId) {
        for (JsonNode weapon : profile.path("weapons")) {
            if (weaponSlotId.equals(weapon.path("weapon_slot_id").asText())) {
                return weapon;
            }
        }
        throw new AssertionError("Weapon slot not found in match profile: " + weaponSlotId);
    }

    private JsonNode findPendingChange(JsonNode pendingChanges, String changeId) {
        for (JsonNode change : pendingChanges.path("changes")) {
            if (changeId.equals(change.path("change_id").asText())) {
                return change;
            }
        }
        throw new AssertionError("Pending change not found: " + changeId);
    }
}
