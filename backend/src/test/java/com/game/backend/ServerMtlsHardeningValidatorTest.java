package com.game.backend;

import com.game.backend.serverauth.config.ServerMtlsHardeningValidator;
import com.game.backend.serverauth.config.ServerMtlsProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerMtlsHardeningValidatorTest {
    @Test
    void shouldRejectUnsafeProductionServerMtlsConfiguration() {
        ServerMtlsProperties mtlsDisabled = new ServerMtlsProperties();
        mtlsDisabled.setEnabled(false);
        mtlsDisabled.setRequirePrivatePort(true);
        mtlsDisabled.setAllowHeaderFingerprintFallback(false);

        assertThatThrownBy(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"prod"}, mtlsDisabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.server-auth.mtls.enabled=true");

        ServerMtlsProperties publicPortAllowed = productionSafeBase();
        publicPortAllowed.setRequirePrivatePort(false);
        assertThatThrownBy(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"production"}, publicPortAllowed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("require-private-port=true");

        ServerMtlsProperties fallbackAllowed = productionSafeBase();
        fallbackAllowed.setAllowHeaderFingerprintFallback(true);
        assertThatThrownBy(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"prod"}, fallbackAllowed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allow-header-fingerprint-fallback=true");
    }

    @Test
    void shouldAllowSafeProductionAndNonProductionConfigurations() {
        assertThatCode(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"prod"}, productionSafeBase()))
                .doesNotThrowAnyException();

        ServerMtlsProperties localFallback = new ServerMtlsProperties();
        localFallback.setEnabled(false);
        localFallback.setRequirePrivatePort(false);
        localFallback.setAllowHeaderFingerprintFallback(true);

        assertThatCode(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"local"}, localFallback))
                .doesNotThrowAnyException();
    }

    private ServerMtlsProperties productionSafeBase() {
        ServerMtlsProperties properties = new ServerMtlsProperties();
        properties.setEnabled(true);
        properties.setRequirePrivatePort(true);
        properties.setAllowHeaderFingerprintFallback(false);
        return properties;
    }
}
