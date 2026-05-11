package com.game.backend.admin.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.admin.api.AdminItemAccessUpdateRequest;
import com.game.backend.admin.api.AdminItemAccessUpdateResponse;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.application.MatchProfileInvalidationService;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import com.game.backend.presets.application.LoadoutSanitizationResult;
import com.game.backend.presets.application.LoadoutSanitizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Применяет admin override к access ledger и player_item_access projection.
 */
@Service
public class AdminPlayerAccessService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };
    private static final String AUDIT_ACTION = "player_access.item_update";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminAuditService adminAuditService;
    private final OutboxService outboxService;
    private final LoadoutSanitizationService loadoutSanitizationService;
    private final MatchProfileInvalidationService matchProfileInvalidationService;
    private final PlayerNotificationService playerNotificationService;
    private final RedisCacheService cacheService;

    public AdminPlayerAccessService(
        JdbcTemplate jdbcTemplate,
        ObjectMapper objectMapper,
        AdminAuditService adminAuditService,
        OutboxService outboxService,
        LoadoutSanitizationService loadoutSanitizationService,
        MatchProfileInvalidationService matchProfileInvalidationService,
        PlayerNotificationService playerNotificationService,
        RedisCacheService cacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adminAuditService = adminAuditService;
        this.outboxService = outboxService;
        this.loadoutSanitizationService = loadoutSanitizationService;
        this.matchProfileInvalidationService = matchProfileInvalidationService;
        this.playerNotificationService = playerNotificationService;
        this.cacheService = cacheService;
    }

    /**
     * Валидирует команду, пишет ledger, обновляет projection revision и публикует событие для downstream-систем.
     */
    @Transactional
    public AdminItemAccessUpdateResponse updateItemAccess(
        AdminIdentity admin,
        String idempotencyKey,
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request
    ) {
        String targetId = playerId + ":" + itemId + ":" + request.catalogVersion();
        String requestHash = null;
        try {
            validateIdempotencyKey(idempotencyKey);
            validateRequest(request);
            requestHash = requestHash(playerId, itemId, request);

            ensurePlayerExists(playerId);
            ensureCatalogItemExists(itemId, request.catalogVersion());
            long currentRevision = lockAccessProjection(playerId);

            ExistingLedgerEvent existing = existingLedgerEvent(playerId, idempotencyKey);
            if (existing != null) {
                if (!requestHash.equals(existing.requestHash())) {
                    throw new ApiException(
                        HttpStatus.CONFLICT,
                        "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                        "Idempotency-Key was reused with a different admin access request"
                    );
                }
                AdminItemAccessUpdateResponse response = duplicateResponse(playerId, itemId, request, existing);
                auditSuccess(admin, targetId, requestHash, request, response);
                return response;
            }

            OffsetDateTime now = OffsetDateTime.now();
            UUID ledgerEventId = UUID.randomUUID();
            long accessRevision = currentRevision + 1;
            upsertProjection(playerId, itemId, request, now);
            updateAccessRevision(playerId, accessRevision, ledgerEventId, now);
            cacheService.evictPlayerAccess(playerId);
            LoadoutSanitizationResult sanitization = sanitizeIfUnavailable(playerId, itemId, request, ledgerEventId, now);
            int staleMatchProfiles = matchProfileInvalidationService.invalidateForPlayerAccessChange(
                playerId,
                request.catalogVersion(),
                "access_changed",
                ledgerEventId,
                now
            );
            insertLedgerEvent(admin, playerId, itemId, idempotencyKey, requestHash, ledgerEventId, accessRevision, sanitization, staleMatchProfiles, request, now);
            recordOutboxEvent(admin, playerId, itemId, request, accessRevision, ledgerEventId, now);
            recordPlayerAccessNotification(playerId, itemId, request, accessRevision, ledgerEventId, sanitization, staleMatchProfiles, now);

            AdminItemAccessUpdateResponse response = new AdminItemAccessUpdateResponse(
                playerId,
                itemId,
                request.catalogVersion(),
                accessRevision,
                request.hidden(),
                request.lockedInShop(),
                request.lockedByQuest(),
                request.disabled(),
                normalizedDisabledReason(request),
                normalized(request.unlockHintCode()),
                request.unlockHintPayload(),
                canUse(request),
                ledgerEventId,
                sanitization.sanitizedWeaponPresets(),
                sanitization.sanitizedOutfitPresets(),
                staleMatchProfiles,
                false
            );
            auditSuccess(admin, targetId, requestHash, request, response);
            return response;
        } catch (ApiException exception) {
            auditFailure(admin, targetId, requestHash, request, exception.code(), exception.status().value());
            throw exception;
        } catch (RuntimeException exception) {
            auditFailure(admin, targetId, requestHash, request, exception.getClass().getSimpleName(), 500);
            throw exception;
        }
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key header is required"
            );
        }
    }

    private void validateRequest(AdminItemAccessUpdateRequest request) {
        if (Boolean.TRUE.equals(request.disabled()) && normalized(request.disabledReason()) == null) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_ERROR",
                "disabled_reason is required when disabled=true"
            );
        }
    }

    private ExistingLedgerEvent existingLedgerEvent(UUID playerId, String idempotencyKey) {
        List<ExistingLedgerEvent> events = jdbcTemplate.query(
            """
                SELECT
                  ledger_event_id,
                  payload->>'request_hash' AS request_hash,
                  (payload->>'result_access_revision')::bigint AS result_access_revision,
                  (payload->>'sanitized_weapon_presets')::int AS sanitized_weapon_presets,
                  (payload->>'sanitized_outfit_presets')::int AS sanitized_outfit_presets,
                  (payload->>'stale_match_profiles')::int AS stale_match_profiles
                FROM entitlement_ledger
                WHERE player_id = ?
                  AND idempotency_key = ?
                """,
            (rs, rowNum) -> new ExistingLedgerEvent(
                rs.getObject("ledger_event_id", UUID.class),
                rs.getString("request_hash"),
                rs.getObject("result_access_revision", Long.class),
                rs.getObject("sanitized_weapon_presets", Integer.class),
                rs.getObject("sanitized_outfit_presets", Integer.class),
                rs.getObject("stale_match_profiles", Integer.class)
            ),
            playerId,
            idempotencyKey
        );
        return events.isEmpty() ? null : events.getFirst();
    }

    private void ensurePlayerExists(UUID playerId) {
        Boolean exists = jdbcTemplate.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM player_accounts WHERE player_id = ?)",
            Boolean.class,
            playerId
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "PLAYER_NOT_FOUND", "Player was not found");
        }
    }

    private void ensureCatalogItemExists(String itemId, long catalogVersion) {
        Boolean exists = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items
                  WHERE item_id = ?
                    AND catalog_version = ?
                )
                """,
            Boolean.class,
            itemId,
            catalogVersion
        );
        if (!Boolean.TRUE.equals(exists)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CATALOG_ITEM_NOT_FOUND", "Catalog item was not found");
        }
    }

    private long lockAccessProjection(UUID playerId) {
        List<Long> revisions = jdbcTemplate.queryForList(
            """
                SELECT access_revision
                FROM player_access_projection_state
                WHERE player_id = ?
                FOR UPDATE
                """,
            Long.class,
            playerId
        );
        if (revisions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCESS_PROJECTION_NOT_FOUND", "Access projection was not found");
        }
        return revisions.getFirst();
    }

    private void insertLedgerEvent(
        AdminIdentity admin,
        UUID playerId,
        String itemId,
        String idempotencyKey,
        String requestHash,
        UUID ledgerEventId,
        long accessRevision,
        LoadoutSanitizationResult sanitization,
        int staleMatchProfiles,
        AdminItemAccessUpdateRequest request,
        OffsetDateTime now
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO entitlement_ledger(
                  ledger_event_id,
                  player_id,
                  item_id,
                  catalog_version,
                  event_type,
                  source_type,
                  source_ref,
                  actor_type,
                  actor_id,
                  idempotency_key,
                  payload,
                  created_at
                )
                VALUES (?, ?, ?, ?, ?, 'admin', ?, 'admin', ?, ?, ?::jsonb, ?)
                """,
            ledgerEventId,
            playerId,
            itemId,
            request.catalogVersion(),
            resolvedEventType(request.eventType()),
            request.reason(),
            admin.actorId(),
            idempotencyKey,
            toJson(ledgerPayload(admin, requestHash, accessRevision, sanitization, staleMatchProfiles, request)),
            now
        );
    }

    private void upsertProjection(
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request,
        OffsetDateTime now
    ) {
        jdbcTemplate.update(
            """
                INSERT INTO player_item_access(
                  player_id,
                  item_id,
                  catalog_version,
                  is_hidden,
                  is_locked_in_shop,
                  is_locked_by_quest,
                  is_disabled,
                  disabled_reason,
                  unlock_hint_code,
                  unlock_hint_payload,
                  updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (player_id, item_id, catalog_version)
                DO UPDATE SET
                  is_hidden = EXCLUDED.is_hidden,
                  is_locked_in_shop = EXCLUDED.is_locked_in_shop,
                  is_locked_by_quest = EXCLUDED.is_locked_by_quest,
                  is_disabled = EXCLUDED.is_disabled,
                  disabled_reason = EXCLUDED.disabled_reason,
                  unlock_hint_code = EXCLUDED.unlock_hint_code,
                  unlock_hint_payload = EXCLUDED.unlock_hint_payload,
                  updated_at = EXCLUDED.updated_at
                """,
            playerId,
            itemId,
            request.catalogVersion(),
            request.hidden(),
            request.lockedInShop(),
            request.lockedByQuest(),
            request.disabled(),
            normalizedDisabledReason(request),
            normalized(request.unlockHintCode()),
            toJsonOrNull(request.unlockHintPayload()),
            now
        );
    }

    private void updateAccessRevision(UUID playerId, long accessRevision, UUID ledgerEventId, OffsetDateTime now) {
        jdbcTemplate.update(
            """
                UPDATE player_access_projection_state
                SET access_revision = ?,
                    projection_rebuilt_at = ?,
                    last_ledger_event_id = ?
                WHERE player_id = ?
                """,
            accessRevision,
            now,
            ledgerEventId,
            playerId
        );
    }

    private AdminItemAccessUpdateResponse duplicateResponse(
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request,
        ExistingLedgerEvent existing
    ) {
        if (existing.resultAccessRevision() == null) {
            return responseFromProjection(
                playerId,
                itemId,
                request.catalogVersion(),
                existing.ledgerEventId(),
                true
            );
        }
        return new AdminItemAccessUpdateResponse(
            playerId,
            itemId,
            request.catalogVersion(),
            existing.resultAccessRevision(),
            request.hidden(),
            request.lockedInShop(),
            request.lockedByQuest(),
            request.disabled(),
            normalizedDisabledReason(request),
            normalized(request.unlockHintCode()),
            request.unlockHintPayload(),
            canUse(request),
            existing.ledgerEventId(),
            sanitizedWeaponPresets(existing),
            sanitizedOutfitPresets(existing),
            staleMatchProfiles(existing),
            true
        );
    }

    private void recordOutboxEvent(
        AdminIdentity admin,
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request,
        long accessRevision,
        UUID ledgerEventId,
        OffsetDateTime now
    ) {
        outboxService.record(
            "player_access.changed",
            "player_access",
            playerId.toString(),
            1,
            Map.of(
                "player_id", playerId,
                "item_id", itemId,
                "catalog_version", request.catalogVersion(),
                "access_revision", accessRevision,
                "ledger_event_id", ledgerEventId,
                "actor_id", admin.actorId(),
                "source", "admin"
            ),
            now
        );
    }

    private void recordPlayerAccessNotification(
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request,
        long accessRevision,
        UUID ledgerEventId,
        LoadoutSanitizationResult sanitization,
        int staleMatchProfiles,
        OffsetDateTime now
    ) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("hidden", request.hidden());
        flags.put("locked_in_shop", request.lockedInShop());
        flags.put("locked_by_quest", request.lockedByQuest());
        flags.put("disabled", request.disabled());
        flags.put("disabled_reason", normalizedDisabledReason(request));
        flags.put("unlock_hint_code", normalized(request.unlockHintCode()));
        flags.put("unlock_hint_payload", request.unlockHintPayload());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("player_id", playerId);
        payload.put("item_id", itemId);
        payload.put("catalog_version", request.catalogVersion());
        payload.put("access_revision", accessRevision);
        payload.put("ledger_event_id", ledgerEventId);
        payload.put("player_can_use", canUse(request));
        payload.put("flags", flags);
        payload.put("sanitized_weapon_presets", sanitization.sanitizedWeaponPresets());
        payload.put("sanitized_outfit_presets", sanitization.sanitizedOutfitPresets());
        payload.put("stale_match_profiles", staleMatchProfiles);
        payload.put("source", "access_update");

        playerNotificationService.record(
            playerId,
            "player_access.changed",
            "player_access",
            playerId.toString(),
            1,
            payload,
            now
        );
    }

    private AdminItemAccessUpdateResponse responseFromProjection(
        UUID playerId,
        String itemId,
        long catalogVersion,
        UUID ledgerEventId,
        boolean duplicate
    ) {
        List<ProjectionRow> rows = jdbcTemplate.query(
            """
                SELECT
                  ps.access_revision,
                  pia.is_hidden,
                  pia.is_locked_in_shop,
                  pia.is_locked_by_quest,
                  pia.is_disabled,
                  pia.disabled_reason,
                  pia.unlock_hint_code,
                  pia.unlock_hint_payload::text AS unlock_hint_payload
                FROM player_access_projection_state ps
                JOIN player_item_access pia
                  ON pia.player_id = ps.player_id
                WHERE pia.player_id = ?
                  AND pia.item_id = ?
                  AND pia.catalog_version = ?
                """,
            (rs, rowNum) -> new ProjectionRow(
                rs.getLong("access_revision"),
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled"),
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                parsePayload(rs.getString("unlock_hint_payload"))
            ),
            playerId,
            itemId,
            catalogVersion
        );
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCESS_ITEM_NOT_FOUND", "Access item was not found");
        }
        ProjectionRow row = rows.getFirst();
        return new AdminItemAccessUpdateResponse(
            playerId,
            itemId,
            catalogVersion,
            row.accessRevision(),
            row.hidden(),
            row.lockedInShop(),
            row.lockedByQuest(),
            row.disabled(),
            row.disabledReason(),
            row.unlockHintCode(),
            row.unlockHintPayload(),
            !row.hidden() && !row.lockedInShop() && !row.lockedByQuest() && !row.disabled(),
            ledgerEventId,
            0,
            0,
            0,
            duplicate
        );
    }

    private void auditSuccess(
        AdminIdentity admin,
        String targetId,
        String requestHash,
        AdminItemAccessUpdateRequest request,
        AdminItemAccessUpdateResponse response
    ) {
        adminAuditService.record(
            admin,
            AUDIT_ACTION,
            "player_item_access",
            targetId,
            requestHash,
            "success",
            Map.of(
                "catalog_version", response.catalogVersion(),
                "access_revision", response.accessRevision(),
                "ledger_event_id", response.ledgerEventId(),
                "sanitized_weapon_presets", response.sanitizedWeaponPresets(),
                "sanitized_outfit_presets", response.sanitizedOutfitPresets(),
                "stale_match_profiles", response.staleMatchProfiles(),
                "duplicate", response.duplicate(),
                "reason", request.reason()
            )
        );
    }

    private void auditFailure(
        AdminIdentity admin,
        String targetId,
        String requestHash,
        AdminItemAccessUpdateRequest request,
        String code,
        int status
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("catalog_version", request.catalogVersion());
        payload.put("reason", request.reason());
        payload.put("code", code);
        payload.put("status", status);
        adminAuditService.record(
            admin,
            AUDIT_ACTION,
            "player_item_access",
            targetId,
            requestHash,
            status == HttpStatus.FORBIDDEN.value() ? "denied" : "failed",
            payload
        );
    }

    private Map<String, Object> ledgerPayload(
        AdminIdentity admin,
        String requestHash,
        long accessRevision,
        LoadoutSanitizationResult sanitization,
        int staleMatchProfiles,
        AdminItemAccessUpdateRequest request
    ) {
        Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("hidden", request.hidden());
        flags.put("locked_in_shop", request.lockedInShop());
        flags.put("locked_by_quest", request.lockedByQuest());
        flags.put("disabled", request.disabled());
        flags.put("disabled_reason", normalizedDisabledReason(request));
        flags.put("unlock_hint_code", normalized(request.unlockHintCode()));
        flags.put("unlock_hint_payload", request.unlockHintPayload());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema_version", 1);
        payload.put("request_hash", requestHash);
        payload.put("result_access_revision", accessRevision);
        payload.put("sanitized_weapon_presets", sanitization.sanitizedWeaponPresets());
        payload.put("sanitized_outfit_presets", sanitization.sanitizedOutfitPresets());
        payload.put("stale_match_profiles", staleMatchProfiles);
        payload.put("actor_id", admin.actorId());
        payload.put("reason", request.reason());
        payload.put("flags", flags);
        return payload;
    }

    private LoadoutSanitizationResult sanitizeIfUnavailable(
        UUID playerId,
        String itemId,
        AdminItemAccessUpdateRequest request,
        UUID ledgerEventId,
        OffsetDateTime now
    ) {
        if (canUse(request)) {
            return LoadoutSanitizationResult.empty();
        }
        return loadoutSanitizationService.sanitizeUnavailableItem(
            playerId,
            itemId,
            request.catalogVersion(),
            "admin_access_update",
            ledgerEventId,
            now
        );
    }

    private int sanitizedWeaponPresets(ExistingLedgerEvent existing) {
        return existing.sanitizedWeaponPresets() == null ? 0 : existing.sanitizedWeaponPresets();
    }

    private int sanitizedOutfitPresets(ExistingLedgerEvent existing) {
        return existing.sanitizedOutfitPresets() == null ? 0 : existing.sanitizedOutfitPresets();
    }

    private int staleMatchProfiles(ExistingLedgerEvent existing) {
        return existing.staleMatchProfiles() == null ? 0 : existing.staleMatchProfiles();
    }

    private String requestHash(UUID playerId, String itemId, AdminItemAccessUpdateRequest request) {
        Map<String, Object> hashPayload = new LinkedHashMap<>();
        hashPayload.put("player_id", playerId);
        hashPayload.put("item_id", itemId);
        hashPayload.put("request", request);
        return sha256(toJson(hashPayload));
    }

    private String normalizedDisabledReason(AdminItemAccessUpdateRequest request) {
        return Boolean.TRUE.equals(request.disabled()) ? normalized(request.disabledReason()) : null;
    }

    private String normalized(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private boolean canUse(AdminItemAccessUpdateRequest request) {
        return !request.hidden() && !request.lockedInShop() && !request.lockedByQuest() && !request.disabled();
    }

    private String resolvedEventType(String eventType) {
        return eventType != null ? eventType : "admin_override";
    }

    private String toJsonOrNull(Map<String, Object> payload) {
        return payload == null ? null : toJson(payload);
    }

    private Map<String, Object> parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, JSON_MAP);
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ACCESS_PAYLOAD_PARSE_FAILED", "Unable to parse access payload");
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ADMIN_ACCESS_SERIALIZATION_FAILED", "Unable to serialize admin access payload");
        }
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "REQUEST_HASH_FAILED", "Unable to hash admin access request");
        }
    }

    private record ExistingLedgerEvent(
        UUID ledgerEventId,
        String requestHash,
        Long resultAccessRevision,
        Integer sanitizedWeaponPresets,
        Integer sanitizedOutfitPresets,
        Integer staleMatchProfiles
    ) {
    }

    private record ProjectionRow(
        long accessRevision,
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        Map<String, Object> unlockHintPayload
    ) {
    }
}
