package com.game.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "app.outbox.worker-enabled=false",
    "app.audit.retention.enabled=false",
    "spring.flyway.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
})
@ActiveProfiles("local")
class OperationalTimeoutConfigurationTest {
    @Autowired
    private Environment environment;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeOperationalTimeoutDefaults() {
        assertThat(environment.getProperty("server.tomcat.connection-timeout")).isEqualTo("5s");
        assertThat(environment.getProperty("server.tomcat.keep-alive-timeout")).isEqualTo("30s");
        assertThat(environment.getProperty("server.tomcat.max-keep-alive-requests")).isEqualTo("100");
        assertThat(environment.getProperty("server.tomcat.mbeanregistry.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("spring.datasource.hikari.connection-timeout")).isEqualTo("5000");
        assertThat(environment.getProperty("spring.datasource.hikari.maximum-pool-size")).isEqualTo("30");
        assertThat(environment.getProperty("spring.datasource.hikari.minimum-idle")).isEqualTo("10");
        assertThat(environment.getProperty("spring.datasource.hikari.idle-timeout")).isEqualTo("300000");
        assertThat(environment.getProperty("spring.datasource.hikari.max-lifetime")).isEqualTo("600000");
        assertThat(environment.getProperty("spring.data.redis.connect-timeout")).isEqualTo("500ms");
        assertThat(environment.getProperty("spring.data.redis.timeout")).isEqualTo("500ms");
        assertThat(environment.getProperty("app.outbox.processing-timeout-seconds")).isEqualTo("60");
    }

    @Test
    void shouldExposeDefaultInternalMetricsAndRestrictProductionHttpActuator() throws IOException {
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
            .isEqualTo("health,info,metrics,prometheus");

        String productionConfig = Files.readString(Path.of("src/main/resources/application-prod.yml"));
        assertThat(productionConfig)
            .contains("port: ${MANAGEMENT_SERVER_PORT:8081}")
            .contains("address: ${MANAGEMENT_SERVER_ADDRESS:127.0.0.1}")
            .contains("keep-alive-timeout: ${HTTP_KEEP_ALIVE_TIMEOUT:30s}")
            .contains("max-keep-alive-requests: ${HTTP_MAX_KEEP_ALIVE_REQUESTS:100}")
            .contains("include: health,info")
            .doesNotContain("include: health,info,metrics,prometheus");
    }

    @Test
    void buildShouldDeclareJavaTwentyOneToolchain() throws IOException {
        String buildFile = Files.readString(Path.of("build.gradle.kts"));

        assertThat(buildFile)
            .contains("toolchain")
            .contains("languageVersion = JavaLanguageVersion.of(21)");
    }
}
