package com.game.backend.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Пишет admin audit события в admin_audit_events.
 */
@Service
public class AdminAuditService {
    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Сохраняет audit event в отдельной транзакции, чтобы попытка админа была видна даже при rollback бизнес-операции.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        AdminIdentity admin,
        String action,
        String targetType,
        String targetId,
        String requestHash,
        String result,
        Map<String, Object> payload
    ) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO admin_audit_events(
                      event_id,
                      actor_id,
                      action,
                      target_type,
                      target_id,
                      request_hash,
                      payload,
                      result,
                      created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                    """,
                UUID.randomUUID(),
                admin.actorId(),
                action,
                targetType,
                targetId,
                requestHash,
                toJson(payload == null ? Map.of() : payload),
                result,
                OffsetDateTime.now()
            );
        } catch (RuntimeException exception) {
            // Audit не должен ломать саму admin-операцию; подробность останется в логах приложения.
            log.warn("Unable to write admin audit event action={} result={}", action, result, exception);
        }
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            return "{\"serialization_error\":true}";
        }
    }
}
