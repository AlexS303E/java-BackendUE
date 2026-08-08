package com.game.backend.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DatasourcePasswordEnvironmentPostProcessorTest {

    @Test
    void shouldResolveDatasourcePasswordFromSecretFileBeforeAutoConfiguration() throws Exception {
        Path passwordFile = Files.createTempFile("datasource-password-", ".txt");
        Files.writeString(passwordFile, "database-secret\n");
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.password", "file:" + passwordFile);

        try {
            new DatasourcePasswordEnvironmentPostProcessor()
                    .postProcessEnvironment(environment, new SpringApplication());

            assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("database-secret");
        } finally {
            Files.deleteIfExists(passwordFile);
        }
    }
}
