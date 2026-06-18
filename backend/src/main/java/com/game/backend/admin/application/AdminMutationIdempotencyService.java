package com.game.backend.admin.application;

import com.game.backend.admin.repository.AdminRepository;
import com.game.backend.admin.repository.AdminRepository.ExistingAdminIdempotencyRecord;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.function.Supplier;

/**
 * Общая idempotency-обертка для admin mutation endpoints, которые не пишут entitlement_ledger напрямую.
 */
@Service
public class AdminMutationIdempotencyService {
    private static final int SUCCESS_STATUS_CODE = 200;

    private final AdminRepository repository;
    private final ObjectMapper objectMapper;

    public AdminMutationIdempotencyService(AdminRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    /**
     * Выполняет admin mutation один раз по Idempotency-Key и возвращает сохраненный ответ при replay.
     */
    @Transactional
    public <T> T execute(
        AdminIdentity admin,
        String operationScope,
        String routeFingerprint,
        String idempotencyKey,
        Object request,
        Class<T> responseType,
        Supplier<T> action
    ) {
        validateIdempotencyKey(idempotencyKey);
        OffsetDateTime now = OffsetDateTime.now();
        deleteExpiredRecord(operationScope, admin.actorId(), idempotencyKey, now);
        String requestHash = requestHash(operationScope, routeFingerprint, request);

        ExistingAdminIdempotencyRecord existing = existingRecord(operationScope, admin.actorId(), idempotencyKey);
        if (existing != null) {
            if (!requestHash.equals(existing.requestHash())) {
                throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                    "Idempotency-Key was reused with a different admin request body"
                );
            }
            return readResponse(existing.responseBody(), responseType);
        }

        T response = action.get();
        insertRecord(operationScope, admin.actorId(), routeFingerprint, idempotencyKey, requestHash, response, now);
        return response;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency-Key header is required");
        }
    }

    private void deleteExpiredRecord(String operationScope, String actorId, String idempotencyKey, OffsetDateTime now) {
        repository.deleteExpiredAdminIdempotencyRecord(operationScope, actorId, idempotencyKey, now);
    }

    private ExistingAdminIdempotencyRecord existingRecord(String operationScope, String actorId, String idempotencyKey) {
        var records = repository.findAdminIdempotencyRecords(operationScope, actorId, idempotencyKey);
        return records.isEmpty() ? null : records.getFirst();
    }

    private void insertRecord(
        String operationScope,
        String actorId,
        String routeFingerprint,
        String idempotencyKey,
        String requestHash,
        Object response,
        OffsetDateTime now
    ) {
        try {
            repository.insertAdminIdempotencyRecord(
                operationScope,
                actorId,
                routeFingerprint,
                idempotencyKey,
                requestHash,
                SUCCESS_STATUS_CODE,
                objectMapper.writeValueAsString(response),
                now,
                now.plusHours(24)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize admin idempotency response", exception);
        }
    }

    private String requestHash(String operationScope, String routeFingerprint, Object request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(operationScope.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(routeFingerprint.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(objectMapper.writeValueAsBytes(request));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash admin idempotency request", exception);
        }
    }

    private <T> T readResponse(String responseBody, Class<T> responseType) {
        try {
            return objectMapper.readValue(responseBody, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize admin idempotency response", exception);
        }
    }
}
