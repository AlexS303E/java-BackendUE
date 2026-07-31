package com.game.backend.auth.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
        assertThat(header.get("typ")).isEqualTo("JWT");
        assertThat(header.get("kid")).isEqualTo("local-rs256");
        Map<String, Object> payload = objectMapper.readValue(
            Base64.getUrlDecoder().decode(token.split("\\.")[1]),
            new TypeReference<>() {
            }
        );
        assertThat(payload)
            .containsEntry("iss", "backend-for-ue-local")
            .containsEntry("aud", "backend-for-ue-client")
            .containsEntry("auth_version", 1)
            .containsKeys("jti", "nbf");
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

    @Test
    void shouldRejectTokenForAnotherIssuerOrAudience() throws Exception {
        KeyPair keyPair = keyPair();
        JwtTokenService issuingService = new JwtTokenService(
            objectMapper,
            privatePem(keyPair),
            publicPem(keyPair),
            "PT15M",
            "https://issuer.example",
            "game-client",
            "key-2026-07"
        );
        JwtTokenService validatingService = new JwtTokenService(
            objectMapper,
            privatePem(keyPair),
            publicPem(keyPair),
            "PT15M",
            "https://other-issuer.example",
            "game-client",
            "key-2026-07"
        );

        assertThat(validatingService.validate(issuingService.issueAccessToken(UUID.randomUUID(), "player"))).isEmpty();
    }

    @Test
    void shouldRejectSignedTokenWithoutUsablePlayerIdentityClaims() throws Exception {
        KeyPair keyPair = keyPair();
        JwtTokenService service = new JwtTokenService(
            objectMapper, privatePem(keyPair), publicPem(keyPair), "PT15M", "issuer", "audience", "key-2026-07"
        );
        long now = Instant.now().getEpochSecond();
        String token = signedToken(keyPair, Map.of(
            "sub", UUID.randomUUID().toString(),
            "iss", "issuer",
            "aud", "audience",
            "iat", now,
            "nbf", now,
            "exp", now + 60,
            "jti", UUID.randomUUID().toString(),
            "auth_version", 1
        ));

        assertThat(service.validate(token)).isEmpty();
    }

    @Test
    void shouldValidateTokenIssuedByPreviousKeyDuringRotation() throws Exception {
        KeyPair previousKey = keyPair();
        KeyPair activeKey = keyPair();
        JwtKeyRingProperties previousActive = keyRing("previous", previousKey, activeKey);
        JwtKeyRingProperties nextActive = keyRing("active", previousKey, activeKey);

        JwtTokenService oldIssuer = new JwtTokenService(
            objectMapper, "", "", "PT15M", "issuer", "audience", "legacy", previousActive
        );
        JwtTokenService rotatedService = new JwtTokenService(
            objectMapper, "", "", "PT15M", "issuer", "audience", "legacy", nextActive
        );

        String previousToken = oldIssuer.issueAccessToken(UUID.randomUUID(), "player");
        assertThat(rotatedService.validate(previousToken)).isPresent();
        String activeToken = rotatedService.issueAccessToken(UUID.randomUUID(), "player");
        Map<String, Object> activeHeader = objectMapper.readValue(
            Base64.getUrlDecoder().decode(activeToken.split("\\.")[0]),
            new TypeReference<>() {
            }
        );
        assertThat(activeHeader).containsEntry("kid", "active");
    }

    private JwtKeyRingProperties keyRing(String activeKeyId, KeyPair previousKey, KeyPair activeKey) {
        JwtKeyRingProperties properties = new JwtKeyRingProperties();
        properties.setJwtActiveKeyId(activeKeyId);
        properties.setJwtKeys(List.of(
            key("previous", previousKey),
            key("active", activeKey)
        ));
        return properties;
    }

    private JwtKeyRingProperties.JwtKey key(String id, KeyPair keyPair) {
        JwtKeyRingProperties.JwtKey key = new JwtKeyRingProperties.JwtKey();
        key.setId(id);
        key.setPrivateKey(privatePem(keyPair));
        key.setPublicKey(publicPem(keyPair));
        return key;
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private String signedToken(KeyPair keyPair, Map<String, Object> payload) throws Exception {
        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
            objectMapper.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT", "kid", "key-2026-07"))
        );
        String body = Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(payload));
        String signingInput = header + "." + body;
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
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
