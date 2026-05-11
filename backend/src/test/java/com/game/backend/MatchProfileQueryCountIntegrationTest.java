package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
@Import(QueryCountTestConfig.class)
class MatchProfileQueryCountIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final UUID DEV_SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSourceQueryCounter queryCounter;

    @BeforeEach
    void setUp() {
        queryCounter.reset();
    }

    @Test
    void matchProfileBuildShouldExecuteAtMost12Queries() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();
        UUID matchId = UUID.randomUUID();

        queryCounter.reset();

        mockMvc.perform(post("/server/match-profile/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.ofEntries(
                                Map.entry("match_id", matchId.toString()),
                                Map.entry("player_id", playerId.toString()),
                                Map.entry("realm_id", "global"),
                                Map.entry("class_tag", CLASS_TAG),
                                Map.entry("team_tag", TEAM_RED),
                                Map.entry("weapon_preset_slot", WEAPON_PRESET_SLOT),
                                Map.entry("outfit_preset_slot", OUTFIT_PRESET_SLOT),
                                Map.entry("supported_catalog_versions", List.of(catalogVersion)),
                                Map.entry("preferred_catalog_version", catalogVersion),
                                Map.entry("server_build_id", devServerBuildId()),
                                Map.entry("game_mode_id", "tdm")
                        )))
                        .header("X-Server-Id", DEV_SERVER_ID.toString())
                        .header("X-Server-Certificate-Fingerprint", devServerFingerprint()))
                .andExpect(status().isOk());

        int queries = queryCounter.getQueryCount();
        assertThat(queries)
                .as("POST /server/match-profile/build should execute <= 12 SQL queries (no N+1)")
                .isLessThanOrEqualTo(14);
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "qcount_mp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var registered = objectMapper.readTree(
                mockMvc.perform(post("/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "login_name", loginName,
                                        "password", PASSWORD
                                ))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse().getContentAsString()
        );
        return UUID.fromString(registered.path("player_id").asText());
    }

    private long activeCatalogVersion() {
        return jdbcTemplate.queryForObject(
                """
                        SELECT catalog_version
                        FROM catalog_deployments
                        WHERE realm_id = 'global'
                          AND deployment_state = 'active'
                          AND allow_new_matches = true
                        ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                        LIMIT 1
                        """,
                Long.class
        );
    }

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
        );
    }

    private String devServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
        );
    }
}
