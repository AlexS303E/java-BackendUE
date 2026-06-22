package com.game.backend.serverauth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Настройки отдельного private mTLS connector для Dedicated Server API.
 */
@ConfigurationProperties(prefix = "app.server-auth.mtls")
public class ServerMtlsProperties {
    /**
     * Включает private mTLS connector. Public connector server.port остается отдельным.
     */
    private boolean enabled;

    /**
     * Private port для /server/* traffic.
     */
    private int port = 9443;

    /**
     * Если включено, /server/* отклоняется на любом порту, кроме private mTLS port.
     */
    private boolean requirePrivatePort = true;

    /**
     * Dev-only fallback на X-Server-Certificate-Fingerprint, когда mTLS выключен.
     */
    private boolean allowHeaderFingerprintFallback = false;

    private String keyStore;
    private String keyStorePassword;
    private String keyStoreType = "PKCS12";
    private String keyAlias;

    private String trustStore;
    private String trustStorePassword;
    private String trustStoreType = "PKCS12";

    private String sslProtocol = "TLS";
    private String enabledProtocols = "TLSv1.3,TLSv1.2";
    private Duration connectionTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isRequirePrivatePort() {
        return requirePrivatePort;
    }

    public void setRequirePrivatePort(boolean requirePrivatePort) {
        this.requirePrivatePort = requirePrivatePort;
    }

    public boolean isAllowHeaderFingerprintFallback() {
        return allowHeaderFingerprintFallback;
    }

    public void setAllowHeaderFingerprintFallback(boolean allowHeaderFingerprintFallback) {
        this.allowHeaderFingerprintFallback = allowHeaderFingerprintFallback;
    }

    public String getKeyStore() {
        return keyStore;
    }

    public void setKeyStore(String keyStore) {
        this.keyStore = keyStore;
    }

    public String getKeyStorePassword() {
        return keyStorePassword;
    }

    public void setKeyStorePassword(String keyStorePassword) {
        this.keyStorePassword = keyStorePassword;
    }

    public String getKeyStoreType() {
        return keyStoreType;
    }

    public void setKeyStoreType(String keyStoreType) {
        this.keyStoreType = keyStoreType;
    }

    public String getKeyAlias() {
        return keyAlias;
    }

    public void setKeyAlias(String keyAlias) {
        this.keyAlias = keyAlias;
    }

    public String getTrustStore() {
        return trustStore;
    }

    public void setTrustStore(String trustStore) {
        this.trustStore = trustStore;
    }

    public String getTrustStorePassword() {
        return trustStorePassword;
    }

    public void setTrustStorePassword(String trustStorePassword) {
        this.trustStorePassword = trustStorePassword;
    }

    public String getTrustStoreType() {
        return trustStoreType;
    }

    public void setTrustStoreType(String trustStoreType) {
        this.trustStoreType = trustStoreType;
    }

    public String getSslProtocol() {
        return sslProtocol;
    }

    public void setSslProtocol(String sslProtocol) {
        this.sslProtocol = sslProtocol;
    }

    public String getEnabledProtocols() {
        return enabledProtocols;
    }

    public void setEnabledProtocols(String enabledProtocols) {
        this.enabledProtocols = enabledProtocols;
    }

    public Duration getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(Duration connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }
}
