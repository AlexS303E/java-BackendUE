package com.game.backend.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.access.api.AccessResponse;
import com.game.backend.catalog.api.CatalogSnapshotResponse;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RedisCacheService {
    private static final String PREFIX = "ue";
    private static final Duration INDEX_TTL_GRACE = Duration.ofDays(1);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final BackendCacheProperties properties;

    public RedisCacheService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        BackendCacheProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public Optional<CatalogSnapshotResponse> getCatalogSnapshot(String realmId, long catalogVersion) {
        return read(catalogSnapshotKey(realmId, catalogVersion), CatalogSnapshotResponse.class);
    }

    public void putCatalogSnapshot(CatalogSnapshotResponse response) {
        String key = catalogSnapshotKey(response.realmId(), response.catalogVersion());
        String indexKey = catalogSnapshotIndexKey(response.realmId());
        write(key, indexKey, response, properties.getCatalogSnapshotTtl());
    }

    public void evictCatalogSnapshots(String realmId) {
        evictIndexed(catalogSnapshotIndexKey(realmId));
    }

    public Optional<AccessResponse> getAccess(UUID playerId, long catalogVersion, long accessRevision) {
        return read(accessKey(playerId, catalogVersion, accessRevision), AccessResponse.class);
    }

    public void putAccess(AccessResponse response) {
        String key = accessKey(response.playerId(), response.catalogVersion(), response.accessRevision());
        String indexKey = accessIndexKey(response.playerId());
        write(key, indexKey, response, properties.getAccessTtl());
    }

    public void evictPlayerAccess(UUID playerId) {
        evictIndexed(accessIndexKey(playerId));
    }

    public Optional<MatchProfileResponse> getMatchProfile(
        UUID playerId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        return read(matchProfileKey(
            playerId,
            realmId,
            classTag,
            teamTag,
            weaponPresetSlot,
            outfitPresetSlot,
            catalogVersion,
            weaponPresetRevision,
            outfitPresetRevision,
            accessRevision
        ), MatchProfileResponse.class);
    }

    public void putMatchProfile(MatchProfileResponse response) {
        String key = matchProfileKey(
            response.playerId(),
            response.realmId(),
            response.classTag(),
            response.teamTag(),
            response.weaponPresetSlot(),
            response.outfitPresetSlot(),
            response.catalogVersion(),
            response.dependencyRevisions().weaponPresetRevision(),
            response.dependencyRevisions().outfitPresetRevision(),
            response.dependencyRevisions().accessRevision()
        );
        String indexKey = matchProfileIndexKey(response.playerId());
        write(key, indexKey, response, properties.getMatchProfileTtl());
    }

    private static final Duration CATALOG_ALLOWS_NEW_MATCHES_TTL = Duration.ofMinutes(5);

    public Optional<Boolean> getCatalogAllowsNewMatches(String realmId, long catalogVersion) {
        if (!properties.isEnabled()) return Optional.empty();
        String raw = readString(catalogAllowsNewMatchesKey(realmId, catalogVersion));
        if (raw == null) return Optional.empty();
        return Optional.of(Boolean.parseBoolean(raw));
    }

    public void putCatalogAllowsNewMatches(String realmId, long catalogVersion, boolean allowed) {
        if (!properties.isEnabled()) return;
        try {
            String key = catalogAllowsNewMatchesKey(realmId, catalogVersion);
            String indexKey = catalogAllowsNewMatchesIndexKey(realmId);
            redisTemplate.opsForValue().set(key, String.valueOf(allowed), CATALOG_ALLOWS_NEW_MATCHES_TTL);
            redisTemplate.opsForSet().add(indexKey, key);
            redisTemplate.expire(indexKey, CATALOG_ALLOWS_NEW_MATCHES_TTL.plus(INDEX_TTL_GRACE));
        } catch (RuntimeException e) {
            // best effort
        }
    }

    public void evictCatalogAllowsNewMatches(String realmId) {
        evictIndexed(catalogAllowsNewMatchesIndexKey(realmId));
    }

    public String catalogSnapshotKey(String realmId, long catalogVersion) {
        return PREFIX + ":catalog:snapshot:" + realmId + ":" + catalogVersion;
    }

    public String accessKey(UUID playerId, long catalogVersion, long accessRevision) {
        return PREFIX + ":access:" + playerId + ":" + catalogVersion + ":" + accessRevision;
    }

    public String matchProfileKey(
        UUID playerId,
        String realmId,
        String classTag,
        String teamTag,
        int weaponPresetSlot,
        int outfitPresetSlot,
        long catalogVersion,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        return PREFIX + ":match-profile:"
            + playerId + ":"
            + realmId + ":"
            + classTag + ":"
            + teamTag + ":"
            + weaponPresetSlot + ":"
            + outfitPresetSlot + ":"
            + catalogVersion + ":"
            + weaponPresetRevision + ":"
            + outfitPresetRevision + ":"
            + accessRevision;
    }

    private String catalogAllowsNewMatchesKey(String realmId, long catalogVersion) {
        return PREFIX + ":catalog:allows-new-matches:" + realmId + ":" + catalogVersion;
    }

    private String catalogAllowsNewMatchesIndexKey(String realmId) {
        return PREFIX + ":catalog:allows-new-matches:index:" + realmId;
    }

    private String readString(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private <T> Optional<T> read(String key, Class<T> valueType) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            String value = redisTemplate.opsForValue().get(key);
            if (value == null || value.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(value, valueType));
        } catch (JsonProcessingException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private void write(String key, String indexKey, Object value, Duration ttl) {
        if (!properties.isEnabled() || ttl == null || ttl.isZero() || ttl.isNegative()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(value), ttl);
            redisTemplate.opsForSet().add(indexKey, key);
            redisTemplate.expire(indexKey, ttl.plus(INDEX_TTL_GRACE));
        } catch (JsonProcessingException | RuntimeException exception) {
            // Redis is an optimization in this MVP. DB remains the source of truth.
        }
    }

    private void evictIndexed(String indexKey) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            Set<String> keys = redisTemplate.opsForSet().members(indexKey);
            Set<String> keysToDelete = new LinkedHashSet<>();
            if (keys != null) {
                keysToDelete.addAll(keys);
            }
            keysToDelete.add(indexKey);
            redisTemplate.delete(keysToDelete);
        } catch (RuntimeException exception) {
            // Best-effort invalidation only; versioned/revisioned keys prevent stale reads.
        }
    }

    private String catalogSnapshotIndexKey(String realmId) {
        return PREFIX + ":catalog:snapshot:index:" + realmId;
    }

    private String accessIndexKey(UUID playerId) {
        return PREFIX + ":access:index:" + playerId;
    }

    private String matchProfileIndexKey(UUID playerId) {
        return PREFIX + ":match-profile:index:" + playerId;
    }
}
