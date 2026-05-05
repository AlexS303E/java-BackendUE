package com.game.backend.serverauth.application;

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
 * Пишет аудит server endpoints в server_audit_events.
 */
@Service
public class ServerAuditService {
    private static final Logger log = LoggerFactory.getLogger(ServerAuditService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ServerAuditService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Пишет audit event в отдельной транзакции, чтобы аудит сохранялся даже при rollback бизнес-операции.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
        ServerIdentity identity,
        UUID matchId,
        String action,
        String scope,
        String result,
        Map<String, Object> payload
    ) {
        record(identity.serverId(), matchId, action, scope, result, payload);
    }

    /**
     * Пишет denied audit event для запросов, где server_id распознан, но principal еще не создан
     * из-за revoked/expired identity, неправильного fingerprint или неверного mTLS канала.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAuthenticationFailure(
        UUID serverId,
        String action,
        String scope,
        Map<String, Object> payload
    ) {
        record(serverId, null, action, scope, "denied", payload);
    }

    private void record(
        UUID serverId,
        UUID matchId,
        String action,
        String scope,
        String result,
        Map<String, Object> payload
    ) {
        try {
            jdbcTemplate.update(
                """
                    INSERT INTO server_audit_events(
                      event_id,
                      server_id,
                      match_id,
                      action,
                      scope,
                      result,
                      payload,
                      created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """,
                UUID.randomUUID(),
                serverId,
                matchId,
                action,
                scope,
                result,
                toJson(payload == null ? Map.of() : payload),
                OffsetDateTime.now()
            );
        } catch (RuntimeException exception) {
            // Аудит не должен ломать пользовательский сценарий; сбой виден в логах.
            log.warn("Unable to write server audit event action={} result={}", action, result, exception);
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
