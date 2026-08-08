package com.game.backend.serverauth.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fail-fast проверка mTLS-настроек для production-профилей.
 */
@Component
public class ServerMtlsHardeningValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final ServerMtlsProperties properties;
    private final Environment environment;

    public ServerMtlsHardeningValidator(ServerMtlsProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(environment.getActiveProfiles(), properties);
    }

    public static void validateForStartup(String[] activeProfiles, ServerMtlsProperties properties) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }

        if (!properties.isEnabled()) {
            throw new IllegalStateException("Production profile requires app.server-auth.mtls.enabled=true");
        }
        if (!properties.isRequirePrivatePort()) {
            throw new IllegalStateException("Production profile requires app.server-auth.mtls.require-private-port=true");
        }
        if (properties.isAllowHeaderFingerprintFallback()) {
            throw new IllegalStateException("Production profile forbids app.server-auth.mtls.allow-header-fingerprint-fallback=true");
        }
        requireFileSecret("app.server-auth.mtls.key-store-password", properties.getKeyStorePassword());
        requireFileSecret("app.server-auth.mtls.trust-store-password", properties.getTrustStorePassword());
    }

    private static void requireFileSecret(String propertyName, String value) {
        if (value == null || !value.trim().startsWith("file:")) {
            throw new IllegalStateException("Production profile requires " + propertyName + " to use file: external secret material");
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        Set<String> normalizedProfiles = Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
                .map(profile -> profile.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return normalizedProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
    }
}
