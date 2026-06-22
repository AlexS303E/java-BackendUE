package com.game.backend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

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
class OperationalTimeoutConfigurationTest {
    @Autowired
    private Environment environment;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldExposeOperationalTimeoutDefaults() {
        assertThat(environment.getProperty("server.tomcat.connection-timeout")).isEqualTo("5s");
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
    void buildShouldDeclareJavaTwentyOneToolchain() throws IOException {
        String buildFile = Files.readString(Path.of("build.gradle.kts"));

        assertThat(buildFile)
            .contains("toolchain")
            .contains("languageVersion = JavaLanguageVersion.of(21)");
    }
}
