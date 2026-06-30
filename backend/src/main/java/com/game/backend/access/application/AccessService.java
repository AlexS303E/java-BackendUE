package com.game.backend.access.application;

import com.game.backend.access.repository.AccessRepository;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.application.CatalogService;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
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

    private final AccessRepository repository;
    private final CatalogService catalogService;
    private final ObjectMapper objectMapper;
    private final RedisCacheService cacheService;
    private final ItemAccessPolicy itemAccessPolicy;

    public AccessService(
        AccessRepository repository,
        CatalogService catalogService,
        ObjectMapper objectMapper,
        RedisCacheService cacheService,
        ItemAccessPolicy itemAccessPolicy
    ) {
        this.repository = repository;
        this.catalogService = catalogService;
        this.objectMapper = objectMapper;
        this.cacheService = cacheService;
        this.itemAccessPolicy = itemAccessPolicy;
    }

    /**
     * Возвращает доступы игрока для конкретного realm и версии каталога.
     */
    public AccessSnapshot getAccess(UUID playerId, String realmId, Long requestedCatalogVersion) {
        long catalogVersion = requestedCatalogVersion == null
            ? catalogService.activeCatalogVersion(realmId)
            : requestedCatalogVersion;
        long accessRevision = accessRevision(playerId);

        return cacheService.getAccess(playerId, catalogVersion, accessRevision)
            .orElseGet(() -> {
                AccessSnapshot response = new AccessSnapshot(
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
        List<Long> revisions = repository.findAccessRevision(playerId);
        if (revisions.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ACCESS_PROJECTION_NOT_FOUND", "Access projection was not found");
        }
        return revisions.getFirst();
    }

    private List<AccessItem> items(UUID playerId, long catalogVersion) {
        return repository.findAccessItems(playerId, catalogVersion)
            .stream()
            .map(row -> new AccessItem(
                    row.itemId(),
                    row.itemType(),
                    row.displayName(),
                    row.hidden(),
                    row.lockedInShop(),
                    row.lockedByQuest(),
                    row.disabled(),
                    row.disabledReason(),
                    row.unlockHintCode(),
                    parsePayload(row.unlockHintPayload()),
                    itemAccessPolicy.canUseForUi(
                        row.enabled(),
                        row.hidden(),
                        row.lockedInShop(),
                        row.lockedByQuest(),
                        row.disabled()
                    )
                ))
            .toList();
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
