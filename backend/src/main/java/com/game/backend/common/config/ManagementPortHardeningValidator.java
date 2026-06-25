package com.game.backend.common.config;

import com.game.backend.serverauth.config.ServerMtlsProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

@Component
public class ManagementPortHardeningValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");

    private final Environment environment;
    private final int publicPort;
    private final int managementPort;
    private final ServerMtlsProperties mtlsProperties;

    public ManagementPortHardeningValidator(
        Environment environment,
        @Value("${server.port:8080}") int publicPort,
        @Value("${management.server.port:-1}") int managementPort,
        ServerMtlsProperties mtlsProperties
    ) {
        this.environment = environment;
        this.publicPort = publicPort;
        this.managementPort = managementPort;
        this.mtlsProperties = mtlsProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(
            environment.getActiveProfiles(),
            publicPort,
            managementPort,
            mtlsProperties.getPort()
        );
    }

    public static void validateForStartup(
        String[] activeProfiles,
        int publicPort,
        int managementPort,
        int mtlsPort
    ) {
        if (!hasProductionProfile(activeProfiles)) {
            return;
        }
        if (managementPort <= 0) {
            throw new IllegalStateException("Production profile requires management.server.port");
        }
        if (managementPort == publicPort) {
            throw new IllegalStateException("Production management.server.port must differ from server.port");
        }
        if (managementPort == mtlsPort) {
            throw new IllegalStateException(
                "Production management.server.port must differ from app.server-auth.mtls.port"
            );
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        return Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(PRODUCTION_PROFILES::contains);
    }
}
