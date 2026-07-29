package com.game.backend.common.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class DeploymentProfileValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final Environment environment;

    public DeploymentProfileValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(environment.getActiveProfiles());
    }

    public static void validateForStartup(String[] activeProfiles) {
        Set<String> profiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
        boolean local = profiles.contains("local");
        boolean production = profiles.stream().anyMatch(PRODUCTION_PROFILES::contains);

        if (!local && !production) {
            throw new IllegalStateException(
                "Application requires an explicit deployment profile: set SPRING_PROFILES_ACTIVE=local or prod"
            );
        }
        if (local && production) {
            throw new IllegalStateException("Application cannot activate local and production profiles together");
        }
    }
}
