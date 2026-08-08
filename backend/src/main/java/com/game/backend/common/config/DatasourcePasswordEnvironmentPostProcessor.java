package com.game.backend.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/** Resolves file-mounted datasource passwords before DataSource auto-configuration starts. */
public class DatasourcePasswordEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String DATASOURCE_PASSWORD = "spring.datasource.password";
    private static final String PROPERTY_SOURCE_NAME = "externalDatasourcePassword";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configuredValue = environment.getProperty(DATASOURCE_PASSWORD);
        String resolvedValue = ExternalSecretMaterialResolver.resolve(DATASOURCE_PASSWORD, configuredValue);
        if (configuredValue != null && !configuredValue.equals(resolvedValue)) {
            environment.getPropertySources().addFirst(
                new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(DATASOURCE_PASSWORD, resolvedValue))
            );
        }
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
