package com.game.backend.common.config;

import com.game.backend.serverauth.config.ServerMtlsProperties;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.Locale;

@Component
public class ManagementPortHardeningValidator implements SmartInitializingSingleton {
    private static final Set<String> PRODUCTION_PROFILES = Set.of("prod", "production");
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "::1", "localhost");

    private final Environment environment;
    private final int publicPort;
    private final int managementPort;
    private final String managementAddress;
    private final ServerMtlsProperties mtlsProperties;

    public ManagementPortHardeningValidator(
        Environment environment,
        @Value("${server.port:8080}") int publicPort,
        @Value("${management.server.port:-1}") int managementPort,
        @Value("${management.server.address:}") String managementAddress,
        ServerMtlsProperties mtlsProperties
    ) {
        this.environment = environment;
        this.publicPort = publicPort;
        this.managementPort = managementPort;
        this.managementAddress = managementAddress;
        this.mtlsProperties = mtlsProperties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        validateForStartup(
            environment.getActiveProfiles(),
            publicPort,
            managementPort,
            mtlsProperties.getPort(),
            managementAddress
        );
    }

    public static void validateForStartup(
        String[] activeProfiles,
        int publicPort,
        int managementPort,
        int mtlsPort
    ) {
        validateForStartup(activeProfiles, publicPort, managementPort, mtlsPort, "127.0.0.1");
    }

    public static void validateForStartup(
        String[] activeProfiles,
        int publicPort,
        int managementPort,
        int mtlsPort,
        String managementAddress
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
        if (managementAddress == null || managementAddress.isBlank()
            || !LOOPBACK_ADDRESSES.contains(managementAddress.trim().toLowerCase(Locale.ROOT))) {
            throw new IllegalStateException("Production management.server.address must bind loopback only");
        }
    }

    private static boolean hasProductionProfile(String[] activeProfiles) {
        return Arrays.stream(activeProfiles == null ? new String[0] : activeProfiles)
            .map(profile -> profile.toLowerCase(Locale.ROOT))
            .anyMatch(PRODUCTION_PROFILES::contains);
    }
}
