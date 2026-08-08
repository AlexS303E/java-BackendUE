package com.game.backend.serverauth.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.Http11NioProtocol;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateMtlsTomcatConnectorConfigTest {
    @Test
    void shouldApplyPrivateConnectorKeepAliveSettings() throws Exception {
        Path keyStore = Files.createTempFile("backend-mtls-keystore", ".p12");
        Path trustStore = Files.createTempFile("backend-mtls-truststore", ".p12");
        Path keyStorePassword = Files.createTempFile("backend-mtls-keystore-password", ".txt");
        Path trustStorePassword = Files.createTempFile("backend-mtls-truststore-password", ".txt");
        Files.writeString(keyStorePassword, "changeit\n");
        Files.writeString(trustStorePassword, "changeit\n");

        ServerMtlsProperties properties = new ServerMtlsProperties();
        properties.setEnabled(true);
        properties.setPort(19443);
        properties.setKeyStore(keyStore.toUri().toString());
        properties.setKeyStorePassword("file:" + keyStorePassword);
        properties.setTrustStore(trustStore.toUri().toString());
        properties.setTrustStorePassword("file:" + trustStorePassword);
        properties.setConnectionTimeout(Duration.ofSeconds(5));
        properties.setKeepAliveTimeout(Duration.ofSeconds(30));
        properties.setMaxKeepAliveRequests(100);

        PrivateMtlsTomcatConnectorConfig config = new PrivateMtlsTomcatConnectorConfig(
                properties,
                new DefaultResourceLoader()
        );

        Method method = PrivateMtlsTomcatConnectorConfig.class.getDeclaredMethod("privateMtlsConnector");
        method.setAccessible(true);
        Connector connector = (Connector) method.invoke(config);
        Http11NioProtocol protocol = (Http11NioProtocol) connector.getProtocolHandler();

        assertThat(protocol.getConnectionTimeout()).isEqualTo(5000);
        assertThat(protocol.getKeepAliveTimeout()).isEqualTo(30000);
        assertThat(maxKeepAliveRequests(protocol)).isEqualTo(100);
    }

    private int maxKeepAliveRequests(Http11NioProtocol protocol) throws Exception {
        Method getEndpoint = protocol.getClass().getSuperclass().getSuperclass().getDeclaredMethod("getEndpoint");
        getEndpoint.setAccessible(true);
        Object endpoint = getEndpoint.invoke(protocol);
        Field field = AbstractEndpoint.class.getDeclaredField("maxKeepAliveRequests");
        field.setAccessible(true);
        return field.getInt(endpoint);
    }
}
