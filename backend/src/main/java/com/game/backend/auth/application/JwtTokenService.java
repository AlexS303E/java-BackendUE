package com.game.backend.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.LinkedHashMap;
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
    private final Map<String, KeyPair> keyRing;
    private final Duration accessTokenTtl;
    private final String issuer;
    private final String audience;
    private final String activeKeyId;

    @Autowired
    public JwtTokenService(
        ObjectMapper objectMapper,
        @Value("${app.auth.jwt-private-key:}") String jwtPrivateKey,
        @Value("${app.auth.jwt-public-key:}") String jwtPublicKey,
        @Value("${app.auth.access-token-ttl:PT15M}") String accessTokenTtl,
        @Value("${app.auth.jwt-issuer:backend-for-ue-local}") String issuer,
        @Value("${app.auth.jwt-audience:backend-for-ue-client}") String audience,
        @Value("${app.auth.jwt-key-id:local-rs256}") String keyId,
        JwtKeyRingProperties keyRingProperties
    ) {
        this.objectMapper = objectMapper;
        KeyRing resolvedKeyRing = resolveKeyRing(jwtPrivateKey, jwtPublicKey, keyId, keyRingProperties);
        this.keyRing = resolvedKeyRing.keys();
        this.activeKeyId = resolvedKeyRing.activeKeyId();
        this.accessTokenTtl = Duration.parse(accessTokenTtl);
        this.issuer = requireClaimValue("app.auth.jwt-issuer", issuer);
        this.audience = requireClaimValue("app.auth.jwt-audience", audience);
    }

    JwtTokenService(ObjectMapper objectMapper, String jwtPrivateKey, String jwtPublicKey, String accessTokenTtl) {
        this(
            objectMapper,
            jwtPrivateKey,
            jwtPublicKey,
            accessTokenTtl,
            "backend-for-ue-local",
            "backend-for-ue-client",
            "local-rs256",
            new JwtKeyRingProperties()
        );
    }

    JwtTokenService(
        ObjectMapper objectMapper,
        String jwtPrivateKey,
        String jwtPublicKey,
        String accessTokenTtl,
        String issuer,
        String audience,
        String keyId
    ) {
        this(
            objectMapper,
            jwtPrivateKey,
            jwtPublicKey,
            accessTokenTtl,
            issuer,
            audience,
            keyId,
            new JwtKeyRingProperties()
        );
    }

    public String issueAccessToken(UUID playerId, String loginName) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenTtl);
        Map<String, Object> header = Map.of(
            "alg", "RS256",
            "typ", "JWT",
            "kid", activeKeyId
        );
        Map<String, Object> payload = Map.of(
            "sub", playerId.toString(),
            "login_name", loginName,
            "iss", issuer,
            "aud", audience,
            "iat", now.getEpochSecond(),
            "nbf", now.getEpochSecond(),
            "exp", expiresAt.getEpochSecond(),
            "jti", UUID.randomUUID().toString(),
            "auth_version", 1
        );

        String headerPart = base64Url(toJson(header));
        String payloadPart = base64Url(toJson(payload));
        String signingInput = headerPart + "." + payloadPart;
        return signingInput + "." + base64Url(sign(signingInput, keyRing.get(activeKeyId).getPrivate()));
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
            if (!"RS256".equals(header.get("alg"))
                || !"JWT".equals(header.get("typ"))
                || !(header.get("kid") instanceof String tokenKeyId)
                || !keyRing.containsKey(tokenKeyId)) {
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

        String tokenKeyId = (String) headerKeyId(token);
        if (tokenKeyId == null || !verify(signingInput, actualSignature, keyRing.get(tokenKeyId).getPublic())) {
            return Optional.empty();
        }

        try {
            Map<String, Object> payload = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[1]),
                MAP_TYPE
            );
            long expiresAt = ((Number) payload.get("exp")).longValue();
            long notBefore = ((Number) payload.get("nbf")).longValue();
            long now = Instant.now().getEpochSecond();
            if (now < notBefore || now >= expiresAt
                || !issuer.equals(payload.get("iss"))
                || !audience.equals(payload.get("aud"))
                || !(payload.get("jti") instanceof String jti) || jti.isBlank()
                || !Integer.valueOf(1).equals(((Number) payload.get("auth_version")).intValue())) {
                return Optional.empty();
            }
            if (!(payload.get("sub") instanceof String subject) || subject.isBlank()
                || !(payload.get("login_name") instanceof String loginName) || loginName.isBlank()) {
                return Optional.empty();
            }
            UUID playerId = UUID.fromString(subject);
            return Optional.of(new AuthenticatedPlayer(playerId, loginName));
        } catch (Exception exception) {
            return Optional.empty();
        }
    }

    public long accessTokenTtlSeconds() {
        return accessTokenTtl.toSeconds();
    }

    private String requireClaimValue(String propertyName, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        return value.trim();
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

    private Object headerKeyId(String token) {
        try {
            return objectMapper.readValue(Base64.getUrlDecoder().decode(token.split("\\.")[0]), MAP_TYPE).get("kid");
        } catch (Exception exception) {
            return null;
        }
    }

    private byte[] sign(String signingInput, PrivateKey privateKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.sign();
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }

    private boolean verify(String signingInput, byte[] actualSignature, PublicKey publicKey) {
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(publicKey);
            signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
            return signature.verify(actualSignature);
        } catch (Exception exception) {
            return false;
        }
    }

    private KeyRing resolveKeyRing(
        String privateKeyPem,
        String publicKeyPem,
        String legacyKeyId,
        JwtKeyRingProperties keyRingProperties
    ) {
        if (!keyRingProperties.getJwtKeys().isEmpty()) {
            Map<String, KeyPair> configuredKeys = new LinkedHashMap<>();
            for (JwtKeyRingProperties.JwtKey configuredKey : keyRingProperties.getJwtKeys()) {
                String keyId = requireClaimValue("app.auth.jwt-keys[].id", configuredKey.getId());
                if (configuredKeys.putIfAbsent(
                    keyId,
                    new KeyPair(readPublicKey(configuredKey.getPublicKey()), readPrivateKey(configuredKey.getPrivateKey()))
                ) != null) {
                    throw new IllegalStateException("app.auth.jwt-keys contains duplicate id: " + keyId);
                }
            }
            String configuredActiveKeyId = requireClaimValue(
                "app.auth.jwt-active-key-id",
                keyRingProperties.getJwtActiveKeyId()
            );
            if (!configuredKeys.containsKey(configuredActiveKeyId)) {
                throw new IllegalStateException("app.auth.jwt-active-key-id must reference configured jwt-keys");
            }
            return new KeyRing(Map.copyOf(configuredKeys), configuredActiveKeyId);
        }

        String keyId = requireClaimValue("app.auth.jwt-key-id", legacyKeyId);
        return new KeyRing(Map.of(keyId, resolveKeyPair(privateKeyPem, publicKeyPem)), keyId);
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

    private record KeyRing(Map<String, KeyPair> keys, String activeKeyId) {
    }
}
