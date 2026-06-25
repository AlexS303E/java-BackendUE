package com.game.backend;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingConfigurationTest {
    private static final Path LOGBACK_CONFIG = Path.of("src/main/resources/logback-spring.xml");

    @Test
    void debugLoggingShouldBeLimitedToLocalAndDevProfiles() throws IOException {
        String config = Files.readString(LOGBACK_CONFIG);

        assertThat(config)
            .contains("""
                  <springProfile name="local,dev">
                    <root level="INFO">
                """)
            .contains("""
                    <logger name="com.game.backend" level="DEBUG"/>
                  </springProfile>
                """)
            .doesNotContain("LOG_LEVEL_ROOT")
            .doesNotContain("LOG_LEVEL_APP");
    }

    @Test
    void productionAndDefaultProfilesShouldUseInfoLogging() throws IOException {
        String config = Files.readString(LOGBACK_CONFIG);

        assertThat(config)
            .contains("""
                  <springProfile name="prod,production">
                    <root level="INFO">
                """)
            .contains("""
                  <springProfile name="!prod &amp; !production &amp; !local &amp; !dev">
                    <root level="INFO">
                """);
    }
}
