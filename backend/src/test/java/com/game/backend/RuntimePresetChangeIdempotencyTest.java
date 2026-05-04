package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RuntimePresetChangeIdempotencyTest {
    private static final String PASSWORD = "password123";
    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String MODULE_ID = "module.scope.red_dot_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldHandleRuntimePresetChangeIdempotencyAndConflicts() throws Exception {
        UUID playerId = registerPlayer();
        UUID matchId = UUID.randomUUID();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        long initialRevision = weaponPresetRevision(playerId);

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(matchId, playerId, catalogVersion))
                        .headers(serverHeaders()))
                .andExpect(status().isOk());

        assertThat(moduleCount(playerId, catalogVersion)).isEqualTo(1);

        UUID appliedOperationId = UUID.randomUUID();
        Map<String, Object> clearModuleBody = runtimeBody(
                appliedOperationId,
                1,
                matchId,
                playerId,
                initialRevision,
                clearModuleChange()
        );

        JsonNode applied = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", clearModuleBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", appliedOperationId.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.operation_id").value(appliedOperationId.toString()))
                        .andExpect(jsonPath("$.status").value("applied"))
                        .andExpect(jsonPath("$.duplicate").value(false))
                        .andReturn()
        );

        long appliedRevision = applied.path("result_revision").asLong();
        assertThat(appliedRevision).isEqualTo(initialRevision + 1);
        assertThat(weaponPresetRevision(playerId)).isEqualTo(appliedRevision);
        assertThat(moduleCount(playerId, catalogVersion)).isZero();

        JsonNode replay = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", clearModuleBody)
                                .headers(serverHeaders())
                                .header("Idempotency-Key", appliedOperationId.toString()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.operation_id").value(appliedOperationId.toString()))
                        .andExpect(jsonPath("$.status").value("applied"))
                        .andExpect(jsonPath("$.duplicate").value(true))
                        .andReturn()
        );

        assertThat(replay.path("result_revision").asLong()).isEqualTo(appliedRevision);
        assertThat(weaponPresetRevision(playerId)).isEqualTo(appliedRevision);
        assertThat(moduleCount(playerId, catalogVersion)).isZero();

        mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody(
                                appliedOperationId,
                                99,
                                matchId,
                                playerId,
                                initialRevision,
                                setModuleChange()
                        ))
                        .headers(serverHeaders())
                        .header("Idempotency-Key", appliedOperationId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST"));

        UUID mismatchedOperationId = UUID.randomUUID();
        mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody(
                                mismatchedOperationId,
                                2,
                                matchId,
                                playerId,
                                appliedRevision,
                                setModuleChange()
                        ))
                        .headers(serverHeaders())
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_OPERATION_ID_MISMATCH"));

        UUID staleOperationId = UUID.randomUUID();
        JsonNode staleConflict = json(
                mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody(
                                        staleOperationId,
                                        2,
                                        matchId,
                                        playerId,
                                        initialRevision,
                                        setModuleChange()
                                ))
                                .headers(serverHeaders())
                                .header("Idempotency-Key", staleOperationId.toString()))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("PRESET_REVISION_CONFLICT"))
                        .andExpect(jsonPath("$.operation_id").value(staleOperationId.toString()))
                        .andExpect(jsonPath("$.pending_change_id").isNotEmpty())
                        .andReturn()
        );

        UUID pendingChangeId = UUID.fromString(staleConflict.path("pending_change_id").asText());
        assertThat(pendingChangeStatus(pendingChangeId)).isEqualTo("pending");
        assertThat(weaponPresetRevision(playerId)).isEqualTo(appliedRevision);
        assertThat(moduleCount(playerId, catalogVersion)).isZero();

        UUID repeatedSeqOperationId = UUID.randomUUID();
        mockMvc.perform(postJson("/server/runtime-preset-changes", runtimeBody(
                                repeatedSeqOperationId,
                                1,
                                matchId,
                                playerId,
                                appliedRevision,
                                setModuleChange()
                        ))
                        .headers(serverHeaders())
                        .header("Idempotency-Key", repeatedSeqOperationId.toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNTIME_OPERATION_SEQ_ALREADY_USED"));

        assertThat(weaponPresetRevision(playerId)).isEqualTo(appliedRevision);
        assertThat(moduleCount(playerId, catalogVersion)).isZero();
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "runtime_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode registered = json(
                mockMvc.perform(postJson("/auth/register", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isCreated())
                        .andReturn()
        );
        return UUID.fromString(registered.path("player_id").asText());
    }

    private Map<String, Object> matchProfileBuildBody(UUID matchId, UUID playerId, long catalogVersion) {
        return Map.of(
                "match_id", matchId.toString(),
                "player_id", playerId.toString(),
                "realm_id", "global",
                "class_tag", CLASS_TAG,
                "team_tag", "team.red",
                "weapon_preset_slot", WEAPON_PRESET_SLOT,
                "outfit_preset_slot", 1,
                "supported_catalog_versions", List.of(catalogVersion),
                "preferred_catalog_version", catalogVersion,
                "server_build_id", devServerBuildId()
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
                "class_tag", CLASS_TAG,
                "weapon_preset_slot", WEAPON_PRESET_SLOT,
                "base_weapon_preset_revision", baseRevision,
                "runtime_change_payload", Map.of(
                        "schema_version", 1,
                        "changes", List.of(change)
                )
        );
    }

    private Map<String, Object> clearModuleChange() {
        return Map.of(
                "op", "clear_module",
                "weapon_slot_id", WEAPON_SLOT_ID,
                "weapon_id", WEAPON_ID,
                "mount_id", MOUNT_ID
        );
    }

    private Map<String, Object> setModuleChange() {
        return Map.of(
                "op", "set_module",
                "weapon_slot_id", WEAPON_SLOT_ID,
                "weapon_id", WEAPON_ID,
                "mount_id", MOUNT_ID,
                "module_id", MODULE_ID
        );
    }

    private long weaponPresetCatalogVersion(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT catalog_version
                    FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                Long.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT
        );
    }

    private long weaponPresetRevision(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT revision
                    FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                Long.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT
        );
    }

    private int moduleCount(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_slot_id = ?
                      AND weapon_id = ?
                      AND mount_id = ?
                      AND module_id = ?
                    """,
                Integer.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                WEAPON_ID,
                MOUNT_ID,
                MODULE_ID
        );
    }

    private String pendingChangeStatus(UUID pendingChangeId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM post_match_pending_changes WHERE change_id = ?",
                String.class,
                pendingChangeId
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

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
