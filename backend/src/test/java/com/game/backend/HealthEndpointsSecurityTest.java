package com.game.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
    "app.outbox.worker-enabled=false",
    "app.audit.retention.enabled=false",
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@AutoConfigureMockMvc
class HealthEndpointsSecurityTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeHealthReadinessAndLivenessWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/liveness"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness"))
            .andExpect(status().isOk());
    }
}
