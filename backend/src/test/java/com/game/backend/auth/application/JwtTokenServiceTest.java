package com.game.backend.auth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldIssueAndValidateRs256Tokens() throws Exception {
        KeyPair keyPair = keyPair();
        JwtTokenService service = new JwtTokenService(
            objectMapper,
            privatePem(keyPair),
            publicPem(keyPair),
            "PT15M"
        );

        UUID playerId = UUID.randomUUID();
        String token = service.issueAccessToken(playerId, "player");

        Map<String, Object> header = objectMapper.readValue(
            Base64.getUrlDecoder().decode(token.split("\\.")[0]),
            new TypeReference<>() {
            }
        );
        assertThat(header.get("alg")).isEqualTo("RS256");
        assertThat(service.validate(token)).contains(new AuthenticatedPlayer(playerId, "player"));
    }

    @Test
    void shouldRejectTamperedTokens() throws Exception {
        KeyPair keyPair = keyPair();
        JwtTokenService service = new JwtTokenService(
            objectMapper,
            privatePem(keyPair),
            publicPem(keyPair),
            "PT15M"
        );

        String token = service.issueAccessToken(UUID.randomUUID(), "player");
        String[] segments = token.split("\\.");
        char replacement = segments[2].charAt(0) == 'A' ? 'B' : 'A';
        String tampered = segments[0] + "." + segments[1] + "." + replacement + segments[2].substring(1);

        assertThat(service.validate(tampered)).isEmpty();
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String privatePem(KeyPair keyPair) {
        return pem("PRIVATE KEY", keyPair.getPrivate().getEncoded());
    }

    private String publicPem(KeyPair keyPair) {
        return pem("PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    private String pem(String label, byte[] der) {
        return "-----BEGIN " + label + "-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
            + "\n-----END " + label + "-----";
    }
}
