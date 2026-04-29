package com.game.backend.auth.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Минимальный HMAC-SHA256 JWT сервис для access token MVP.
 */
@Service
public class JwtTokenService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final byte[] secret;
    private final Duration accessTokenTtl;

    public JwtTokenService(
        ObjectMapper objectMapper,
        @Value("${app.auth.jwt-secret:dev-only-change-me-dev-only-change-me}") String jwtSecret,
        @Value("${app.auth.access-token-ttl:PT15M}") String accessTokenTtl
    ) {
        this.objectMapper = objectMapper;
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.accessTokenTtl = Duration.parse(accessTokenTtl);
    }

    /**
     * Формирует JWT с player_id в sub, login_name и временем истечения.
     */
    public String issueAccessToken(UUID playerId, String loginName) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(accessTokenTtl);
        Map<String, Object> header = Map.of(
            "alg", "HS256",
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

    /**
     * Проверяет подпись, срок действия и извлекает AuthenticatedPlayer.
     */
    public Optional<AuthenticatedPlayer> validate(String token) {
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }

        String signingInput = parts[0] + "." + parts[1];
        byte[] expectedSignature = sign(signingInput);
        byte[] actualSignature;
        try {
            actualSignature = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }

        // Константное сравнение защищает подпись от timing-утечек.
        if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
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

    /**
     * Возвращает TTL access token в секундах для auth response.
     */
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
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to sign JWT", exception);
        }
    }
}
