package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.access.application.AccessItem;
import com.game.backend.access.application.AccessSnapshot;
import com.game.backend.access.application.AccessService;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.api.CatalogItemDto;
import com.game.backend.catalog.api.CatalogSnapshotResponse;
import com.game.backend.catalog.application.CatalogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {
        "app.outbox.worker-enabled=false",
        "app.server-auth.mtls.enabled=false",
        "app.server-auth.mtls.require-private-port=false",
        "app.server-auth.mtls.allow-header-fingerprint-fallback=true",
        "app.cache.enabled=true",
        "app.cache.catalog-snapshot-ttl=PT10M",
        "app.cache.access-ttl=PT10M"
    }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class RedisCacheIntegrationTest {
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedisCacheService cacheService;

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private AccessService accessService;

    @BeforeEach
    void setUp() {
        resetGlobalCatalogToVersionOne();
        flushBackendCacheKeys();
    }

    @AfterEach
    void tearDown() {
        resetGlobalCatalogToVersionOne();
        flushBackendCacheKeys();
    }

    @Test
    void shouldCacheCatalogSnapshotUntilRealmSnapshotEviction() {
        CatalogSnapshotResponse first = catalogService.getSnapshot("global");
        assertThat(first.items()).isNotEmpty();

        String cacheKey = cacheService.catalogSnapshotKey("global", first.catalogVersion());
        assertThat(redisTemplate.hasKey(cacheKey)).isTrue();

        CatalogItemDto item = first.items().getFirst();
        String mutatedName = item.displayName() + " cache-test";
        try {
            jdbcTemplate.update(
                "UPDATE catalog_items SET display_name = ? WHERE item_id = ? AND catalog_version = ?",
                mutatedName,
                item.itemId(),
                item.catalogVersion()
            );

            CatalogSnapshotResponse cached = catalogService.getSnapshot("global");
            assertThat(item(cached, item.itemId()).displayName()).isEqualTo(item.displayName());

            cacheService.evictCatalogSnapshots("global");
            assertThat(redisTemplate.hasKey(cacheKey)).isFalse();

            CatalogSnapshotResponse afterEvict = catalogService.getSnapshot("global");
            assertThat(item(afterEvict, item.itemId()).displayName()).isEqualTo(mutatedName);
        } finally {
            jdbcTemplate.update(
                "UPDATE catalog_items SET display_name = ? WHERE item_id = ? AND catalog_version = ?",
                item.displayName(),
                item.itemId(),
                item.catalogVersion()
            );
            cacheService.evictCatalogSnapshots("global");
        }
    }

    @Test
    void shouldCachePlayerAccessByCatalogVersionAndAccessRevision() throws Exception {
        UUID playerId = registerPlayer();

        AccessSnapshot first = accessService.getAccess(playerId, "global", 1L);
        assertThat(first.items()).isNotEmpty();
        assertThat(redisTemplate.hasKey(cacheService.accessKey(playerId, 1L, first.accessRevision()))).isTrue();

        AccessItem item = first.items().getFirst();
        jdbcTemplate.update(
            """
                UPDATE player_item_access
                SET is_hidden = ?,
                    unlock_hint_code = ?,
                    updated_at = ?
                WHERE player_id = ?
                  AND item_id = ?
                  AND catalog_version = ?
                """,
            !item.hidden(),
            item.hidden() ? null : "hidden",
            OffsetDateTime.now(),
            playerId,
            item.itemId(),
            first.catalogVersion()
        );

        AccessSnapshot cached = accessService.getAccess(playerId, "global", 1L);
        assertThat(item(cached, item.itemId()).hidden()).isEqualTo(item.hidden());

        jdbcTemplate.update(
            "UPDATE player_access_projection_state SET access_revision = access_revision + 1 WHERE player_id = ?",
            playerId
        );

        AccessSnapshot revised = accessService.getAccess(playerId, "global", 1L);
        assertThat(revised.accessRevision()).isEqualTo(first.accessRevision() + 1);
        assertThat(item(revised, item.itemId()).hidden()).isEqualTo(!item.hidden());
        assertThat(redisTemplate.hasKey(cacheService.accessKey(playerId, 1L, revised.accessRevision()))).isTrue();

        cacheService.evictPlayerAccess(playerId);
        assertThat(redisTemplate.hasKey(cacheService.accessKey(playerId, 1L, revised.accessRevision()))).isFalse();
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "cache_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode registered = json(
            mockMvc.perform(postJson("/auth/register", Map.of(
                    "login_name", loginName,
                    "password", PASSWORD
                )))
                .andExpect(status().isCreated())
                .andReturn()
        );
        return UUID.fromString(registered.path("player_id").asText());
    }

    private CatalogItemDto item(CatalogSnapshotResponse response, String itemId) {
        return response.items().stream()
            .filter(item -> item.itemId().equals(itemId))
            .findFirst()
            .orElseThrow();
    }

    private AccessItem item(AccessSnapshot response, String itemId) {
        return response.items().stream()
            .filter(item -> item.itemId().equals(itemId))
            .findFirst()
            .orElseThrow();
    }

    private RequestBuilder postJson(String uri, Object body) throws Exception {
        return post(uri)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private void flushBackendCacheKeys() {
        Set<String> keys = redisTemplate.keys("ue:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void resetGlobalCatalogToVersionOne() {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'previous',
                    rollout_percent = 0,
                    allow_new_matches = false,
                    allow_existing_matches = true,
                    retired_at = ?
                WHERE realm_id = 'global'
                  AND catalog_version <> 1
                  AND deployment_state = 'active'
                  AND allow_new_matches = true
                """,
            now
        );
        jdbcTemplate.update(
            """
                UPDATE catalog_deployments
                SET deployment_state = 'active',
                    rollout_percent = 100,
                    allow_new_matches = true,
                    allow_existing_matches = true,
                    activated_at = ?,
                    retired_at = null
                WHERE realm_id = 'global'
                  AND catalog_version = 1
                """,
            now
        );
    }
}
