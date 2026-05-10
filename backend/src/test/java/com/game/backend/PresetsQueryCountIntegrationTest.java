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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.hamcrest.Matchers.greaterThan;
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
@Import(QueryCountTestConfig.class)
class PresetsQueryCountIntegrationTest {
    private static final String PASSWORD = "password123";

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
    void presetsEndpointShouldExecuteAtMost8Queries() throws Exception {
        UUID playerId = registerPlayer();
        String token = loginToken(playerId);

        queryCounter.reset();

        mockMvc.perform(get("/me/presets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.weapon_presets").isArray())
                .andExpect(jsonPath("$.weapon_presets.length()").value(greaterThan(0)));

        int queries = queryCounter.getQueryCount();
        assertThat(queries)
                .as("GET /me/presets should execute <= 8 SQL queries (no N+1)")
                .isLessThanOrEqualTo(8);
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "qcount_p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private String loginToken(UUID playerId) throws Exception {
        String loginName = jdbcTemplate.queryForObject(
                "SELECT login_name FROM player_accounts WHERE player_id = ?",
                String.class,
                playerId
        );
        var loginResult = objectMapper.readTree(
                mockMvc.perform(post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "login_name", loginName,
                                        "password", PASSWORD
                                ))))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse().getContentAsString()
        );
        return loginResult.path("access_token").asText();
    }
}
