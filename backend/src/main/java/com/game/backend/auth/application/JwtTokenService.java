package com.game.backend.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Minimal RS256 JWT service for player access tokens.
 */
@Service
public class JwtTokenService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Duration accessTokenTtl;

    public JwtTokenService(
        ObjectMapper objectMapper,
        @Value("${app.auth.jwt-private-key:}") String jwtPrivateKey,
        @Value("${app.auth.jwt-public-key:}") String jwtPublicKey,
        @Value("${app.auth.access-token-ttl:PT15M}") String accessTokenTtl
    ) {
        this.objectMapper = objectMapper;
        KeyPair keyPair = resolveKeyPair(jwtPrivateKey, jwtPublicKey);
        this.privateKey = keyPair.getPrivate();
        this.publicKey = keyPair.getPublic();
        this.accessTokenTtl = Duration.parse(accessTokenTtl);
    }

    public String issueAccessToken(UUID playerId, String loginName) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenTtl);
        Map<String, Object> header = Map.of(
            "alg", "RS256",
            "typ", "JWT"
        );
        Map<String, Object> payload = Map.of(
            "sub", playerId.toString(),
            "login_name", loginName,
            "iat", now.getEpochSecond(),
            "exp", expiresAt.getEpochSecond()
        );

        String headerPart = base64Url(toJson(header));
        String payloadPart = base64Url(toJson(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + base64Url(sign(signingInput));
    }

    public Optional<AuthenticatedPlayer> validate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        try {
            Map<String, Object> header = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[0]),
                MAP_TYPE
            );
            if (!"RS256".equals(header.get("alg"))) {
                return Optional.empty();
            }
        } catch (Exception exception) {
            return Optional.empty();
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] actualSignature;
        try {
            actualSignature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        if (!verify(signingInput, actualSignature)) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]),
                MAP_TYPE
            );
            long expiresAt = ((Number) payload.get("exp")).longValue();
            if (Instant.now().getEpochSecond() >= expiresAt) {
                return Optional.empty();
            }
            UUID playerId = UUID.fromString((String) payload.get("sub"));
            String loginName = (String) payload.get("login_name");
            return Optional.of(new AuthenticatedPlayer(playerId, loginName));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize JWT payload", exception);
        }
    }

    private String base64Url(String value) {
        return base64Url(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] sign(String signingInput) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }

    private boolean verify(String signingInput, byte[] actualSignature) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(actualSignature);
        } catch (Exception exception) {
            return false;
        }
    }

    private KeyPair resolveKeyPair(String privateKeyPem, String publicKeyPem) {
        if (privateKeyPem == null || privateKeyPem.isBlank() || publicKeyPem == null || publicKeyPem.isBlank()) {
            return generateLocalOnlyKeyPair();
        }
        return new KeyPair(readPublicKey(publicKeyPem), readPrivateKey(privateKeyPem));
    }

    private KeyPair generateLocalOnlyKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate local JWT RSA key pair", exception);
        }
    }

    private PrivateKey readPrivateKey(String value) {
        try {
            byte[] keyBytes = decodePem(value, "PRIVATE KEY");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read app.auth.jwt-private-key as PKCS#8 RSA private key", exception);
        }
    }

    private PublicKey readPublicKey(String value) {
        try {
            byte[] keyBytes = decodePem(value, "PUBLIC KEY");
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to read app.auth.jwt-public-key as X.509 RSA public key", exception);
        }
    }

    private byte[] decodePem(String value, String label) throws Exception {
        String pem = resolvePemValue(value).replace("\\n", "\n").trim();
        String normalized = pem
            .replace("-----BEGIN " + label + "-----", "")
            .replace("-----END " + label + "-----", "")
            .replaceAll("\\s", "");
        return Base64.getDecoder().decode(normalized);
    }

    private String resolvePemValue(String value) throws Exception {
        if (value.startsWith("file:")) {
            return Files.readString(Path.of(value.substring("file:".length())), StandardCharsets.UTF_8);
        }
        return value;
    }
}
