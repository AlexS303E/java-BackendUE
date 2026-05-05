package com.game.backend.access.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.access.api.AccessItemDto;
import com.game.backend.access.api.AccessResponse;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.application.CatalogService;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Читает готовую проекцию доступа игрока к предметам каталога.
 */
@Service
public class AccessService {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final RedisCacheService cacheService;

    public AccessService(
        JdbcTemplate jdbcTemplate,
        CatalogService catalogService,
        ObjectMapper objectMapper,
        RedisCacheService cacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
    }

    /**
     * Возвращает доступы игрока для конкретного realm и версии каталога.
     */
    public AccessResponse getAccess(UUID playerId, String realmId, Long requestedCatalogVersion) {
        long catalogVersion = requestedCatalogVersion == null
            ? catalogService.activeCatalogVersion(realmId)
            : requestedCatalogVersion;
        long accessRevision = accessRevision(playerId);

        return cacheService.getAccess(playerId, catalogVersion, accessRevision)
            .orElseGet(() -> {
                AccessResponse response = new AccessResponse(
                    playerId,
                    catalogVersion,
                    accessRevision,
                    items(playerId, catalogVersion)
                );
                cacheService.putAccess(response);
                return response;
            });
    }

    private long accessRevision(UUID playerId) {
        List<Long> revisions = jdbcTemplate.queryForList(
            "SELECT access_revision FROM player_access_projection_state WHERE player_id = ?",
            Long.class,
            playerId
        );
        if (revisions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCESS_PROJECTION_NOT_FOUND", "Access projection was not found");
        }
        return revisions.getFirst();
    }

    private List<AccessItemDto> items(UUID playerId, long catalogVersion) {
        return jdbcTemplate.query(
            """
                SELECT
                  pia.item_id,
                  ci.item_type,
                  ci.display_name,
                  pia.is_hidden,
                  pia.is_locked_in_shop,
                  pia.is_locked_by_quest,
                  pia.is_disabled,
                  pia.disabled_reason,
                  pia.unlock_hint_code,
                  pia.unlock_hint_payload::text AS unlock_hint_payload
                FROM player_item_access pia
                JOIN catalog_items ci
                  ON ci.item_id = pia.item_id
                 AND ci.catalog_version = pia.catalog_version
                WHERE pia.player_id = ?
                  AND pia.catalog_version = ?
                ORDER BY ci.item_type, pia.item_id
                """,
            (rs, rowNum) -> {
                boolean hidden = rs.getBoolean("is_hidden");
                boolean lockedInShop = rs.getBoolean("is_locked_in_shop");
                boolean lockedByQuest = rs.getBoolean("is_locked_by_quest");
                boolean disabled = rs.getBoolean("is_disabled");
                return new AccessItemDto(
                    rs.getString("item_id"),
                    rs.getString("item_type"),
                    rs.getString("display_name"),
                    hidden,
                    lockedInShop,
                    lockedByQuest,
                    disabled,
                    rs.getString("disabled_reason"),
                    rs.getString("unlock_hint_code"),
                    parsePayload(rs.getString("unlock_hint_payload")),
                    !hidden && !lockedInShop && !lockedByQuest && !disabled
                );
            },
            playerId,
            catalogVersion
        );
    }

    /**
     * Парсит JSONB payload из БД в структуру, которую можно вернуть клиенту.
     */
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
}
