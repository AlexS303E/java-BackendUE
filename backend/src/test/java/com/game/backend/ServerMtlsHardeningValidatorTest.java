package com.game.backend;

import com.game.backend.serverauth.config.ServerMtlsHardeningValidator;
import com.game.backend.serverauth.config.ServerMtlsProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerMtlsHardeningValidatorTest {
    @Test
    void shouldExposeBoundedPrivateConnectorTimeoutDefault() {
        ServerMtlsProperties properties = new ServerMtlsProperties();

        assertThat(properties.getConnectionTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.getKeepAliveTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getMaxKeepAliveRequests()).isEqualTo(100);
    }

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

        ServerMtlsProperties literalPassword = productionSafeBase();
        literalPassword.setKeyStorePassword("changeit");
        assertThatThrownBy(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"prod"}, literalPassword))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key-store-password to use file: external secret material");

        ServerMtlsProperties classpathKeyStore = productionSafeBase();
        classpathKeyStore.setKeyStore("classpath:backend.p12");
        assertThatThrownBy(() -> ServerMtlsHardeningValidator.validateForStartup(new String[]{"prod"}, classpathKeyStore))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("key-store to use file: external secret material");
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
        properties.setConnectionTimeout(Duration.ofSeconds(5));
        properties.setKeepAliveTimeout(Duration.ofSeconds(30));
        properties.setMaxKeepAliveRequests(100);
        properties.setKeyStore("file:/run/secrets/backend-keystore.p12");
        properties.setKeyStorePassword("file:/run/secrets/backend-keystore-password");
        properties.setTrustStore("file:/run/secrets/backend-truststore.p12");
        properties.setTrustStorePassword("file:/run/secrets/backend-truststore-password");
        return properties;
    }
}
