package com.game.backend.serverauth.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.StringUtils;

import java.io.File;
import java.io.IOException;

/**
 * Добавляет отдельный HTTPS connector с обязательным client certificate для private /server/* трафика.
 * Основной server.port остается public connector без требования клиентского сертификата.
 */
@Configuration
@EnableConfigurationProperties(ServerMtlsProperties.class)
public class PrivateMtlsTomcatConnectorConfig {
    private static final String DEFAULT_SSL_HOST_CONFIG_NAME = "_default_";

    private final ServerMtlsProperties properties;
    private final ResourceLoader resourceLoader;

    public PrivateMtlsTomcatConnectorConfig(ServerMtlsProperties properties, ResourceLoader resourceLoader) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    @Bean
    TomcatServletWebServerFactory tomcatServletWebServerFactory() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory();
        if (properties.isEnabled()) {
            factory.addAdditionalTomcatConnectors(privateMtlsConnector());
        }
        return factory;
    }

    private Connector privateMtlsConnector() {
        requireText(properties.getKeyStore(), "app.server-auth.mtls.key-store is required when mTLS is enabled");
        requireText(properties.getKeyStorePassword(), "app.server-auth.mtls.key-store-password is required when mTLS is enabled");
        requireText(properties.getTrustStore(), "app.server-auth.mtls.trust-store is required when mTLS is enabled");
        requireText(properties.getTrustStorePassword(), "app.server-auth.mtls.trust-store-password is required when mTLS is enabled");

        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(properties.getPort());
        connector.setScheme("https");
        connector.setSecure(true);

        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();
        protocol.setSSLEnabled(true);

        SSLHostConfig sslHostConfig = new SSLHostConfig();
        sslHostConfig.setHostName(DEFAULT_SSL_HOST_CONFIG_NAME);
        sslHostConfig.setCertificateVerification("required");
        sslHostConfig.setSslProtocol(properties.getSslProtocol());
        if (StringUtils.hasText(properties.getEnabledProtocols())) {
            sslHostConfig.setProtocols(properties.getEnabledProtocols());
        }

        sslHostConfig.setTruststoreFile(resolveToFilePath(properties.getTrustStore()));
        sslHostConfig.setTruststorePassword(properties.getTrustStorePassword());
        sslHostConfig.setTruststoreType(properties.getTrustStoreType());

        SSLHostConfigCertificate certificate = new SSLHostConfigCertificate(
                sslHostConfig,
                SSLHostConfigCertificate.Type.UNDEFINED
        );
        certificate.setCertificateKeystoreFile(resolveToFilePath(properties.getKeyStore()));
        certificate.setCertificateKeystorePassword(properties.getKeyStorePassword());
        certificate.setCertificateKeystoreType(properties.getKeyStoreType());
        if (StringUtils.hasText(properties.getKeyAlias())) {
            certificate.setCertificateKeyAlias(properties.getKeyAlias());
        }

        sslHostConfig.addCertificate(certificate);
        protocol.addSslHostConfig(sslHostConfig);

        return connector;
    }

    private String resolveToFilePath(String location) {
        try {
            Resource resource = resourceLoader.getResource(location);
            File file = resource.getFile();
            return file.getAbsolutePath();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot resolve mTLS resource to file: " + location, exception);
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(message);
        }
    }
}
