package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class CatalogLifecycleIntegrationTest {
    private static final String ADMIN_TOKEN = "dev-admin-token";
    private static final String PASSWORD = "password123";
    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetCatalogPointer() {
        resetGlobalCatalogToVersionOne();
    }

    @AfterEach
    void restoreCatalogPointer() {
        resetGlobalCatalogToVersionOne();
    }

    @Test
    void shouldPublishCatalogMigrateDurableStateAndRollbackDeploymentPointer() throws Exception {
        UUID playerId = registerPlayer();
        UUID staleMatchId = UUID.randomUUID();
        long fromVersion = weaponPresetCatalogVersion(playerId);
        assertThat(fromVersion).isEqualTo(1L);

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(staleMatchId, playerId, 1L))
                        .headers(serverHeaders()))
                .andExpect(status().isOk());

        seedCatalogVersionTwoWithoutAllowedModule();

        JsonNode published = json(
                mockMvc.perform(postJson("/admin/catalog/publish", Map.of(
                                "realm_id", "global",
                                "catalog_version", 2,
                                "rollout_percent", 100,
                                "allow_existing_matches", true,
                                "reason", "integration test publish"
                        ))
                                .header("X-Admin-Token", ADMIN_TOKEN))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.previous_catalog_version").value(1))
                        .andExpect(jsonPath("$.active_catalog_version").value(2))
                        .andExpect(jsonPath("$.action").value("publish"))
                        .andReturn()
        );

        assertThat(published.path("migrated_access_players").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(published.path("migrated_weapon_presets").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(activeCatalogVersion()).isEqualTo(2L);
        assertThat(weaponPresetCatalogVersion(playerId)).isEqualTo(2L);
        assertThat(weaponPresetRevision(playerId)).isEqualTo(2L);
        assertThat(weaponPresetSanitized(playerId)).isTrue();
        assertThat(moduleCount(playerId, 2L)).isZero();
        assertThat(staleProfileCount(playerId)).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/catalog/snapshot"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalog_version").value(2));

        JsonNode profileV2 = json(
                mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(UUID.randomUUID(), playerId, 2L))
                                .headers(serverHeaders()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.catalog_version").value(2))
                        .andReturn()
        );
        assertThat(findWeapon(profileV2, "primary").path("modules")).isEmpty();

        JsonNode rolledBack = json(
                mockMvc.perform(postJson("/admin/catalog/rollback", Map.of(
                                "realm_id", "global",
                                "target_catalog_version", 1,
                                "reason", "integration test rollback"
                        ))
                                .header("X-Admin-Token", ADMIN_TOKEN))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.previous_catalog_version").value(2))
                        .andExpect(jsonPath("$.active_catalog_version").value(1))
                        .andExpect(jsonPath("$.action").value("rollback"))
                        .andReturn()
        );

        assertThat(rolledBack.path("migrated_weapon_presets").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(activeCatalogVersion()).isEqualTo(1L);
        assertThat(weaponPresetCatalogVersion(playerId)).isEqualTo(1L);
    }

    @Test
    void shouldRejectDraftCatalogAndApplyMigrationMapDropAndManualActions() throws Exception {
        seedDraftCatalogVersion(90L);

        mockMvc.perform(postJson("/admin/catalog/publish", publishBody(90L, "draft must not publish"))
                        .header("X-Admin-Token", ADMIN_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_VERSION_NOT_PUBLISHABLE"));

        assertThat(activeCatalogVersion()).isEqualTo(1L);
        assertThat(activeNewMatchCatalogRows()).isEqualTo(1);

        UUID mappedPlayerId = registerPlayer();
        seedCatalogVersionWithModuleMigration(3L, "map", "module.scope.red_dot_02");
        publishCatalog(3L);
        assertThat(selectedModuleIds(mappedPlayerId, 3L)).containsExactly("module.scope.red_dot_02");
        assertThat(pendingCatalogConflictCount(mappedPlayerId)).isZero();
        assertThat(activeCatalogVersion()).isEqualTo(3L);
        assertThat(activeNewMatchCatalogRows()).isEqualTo(1);

        resetGlobalCatalogToVersionOne();
        UUID droppedPlayerId = registerPlayer();
        seedCatalogVersionWithModuleMigration(4L, "drop", null);
        publishCatalog(4L);
        assertThat(moduleCount(droppedPlayerId, 4L)).isZero();
        assertThat(pendingCatalogConflictCount(droppedPlayerId)).isZero();
        assertThat(activeCatalogVersion()).isEqualTo(4L);
        assertThat(activeNewMatchCatalogRows()).isEqualTo(1);

        resetGlobalCatalogToVersionOne();
        UUID manualPlayerId = registerPlayer();
        seedCatalogVersionWithModuleMigration(5L, "manual", null);
        publishCatalog(5L);
        assertThat(moduleCount(manualPlayerId, 5L)).isZero();
        assertThat(pendingCatalogConflictCount(manualPlayerId)).isEqualTo(1);

        JsonNode manualPayload = latestPendingCatalogConflictPayload(manualPlayerId);
        assertThat(manualPayload.path("source").asText()).isEqualTo("catalog_lifecycle");
        assertThat(manualPayload.path("conflict").path("resolution").asText()).isEqualTo("manual_required");
        assertThat(manualPayload.path("conflict").path("manual_migration_conflicts").get(0).path("migration_action").asText())
                .isEqualTo("manual");
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "catalog_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
        jdbcTemplate.update(
                """
                    UPDATE catalog_versions
                    SET state = 'validated',
                        retired_at = null
                    WHERE catalog_version <> 1
                      AND state = 'active'
                    """
        );
        jdbcTemplate.update(
                """
                    UPDATE catalog_versions
                    SET state = 'active',
                        activated_at = ?,
                        retired_at = null
                    WHERE catalog_version = 1
                    """,
                now
        );
    }

    private void seedCatalogVersionTwoWithoutAllowedModule() {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_versions(catalog_version, artifact_hash, schema_version, state, created_at, validated_at)
                    VALUES (2, ?, 1, 'validated', ?, ?)
                    ON CONFLICT (catalog_version) DO UPDATE SET
                      state = 'validated',
                      validated_at = EXCLUDED.validated_at,
                      retired_at = null
                    """,
                "integration-test-catalog-v2",
                now,
                now
        );
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_items(item_id, catalog_version, item_type, display_name, is_enabled, payload_schema_version, created_at)
                    VALUES
                      ('weapon.ak12', 2, 'weapon', 'AK-12 v2', true, 1, ?),
                      ('module.scope.red_dot_01', 2, 'module', 'Red Dot Sight v2', true, 1, ?),
                      ('clothing.team_red.jacket_01', 2, 'clothing', 'Red Team Jacket v2', true, 1, ?),
                      ('clothing.team_blue.jacket_01', 2, 'clothing', 'Blue Team Jacket v2', true, 1, ?)
                    ON CONFLICT (item_id, catalog_version) DO NOTHING
                    """,
                now,
                now,
                now,
                now
        );
        jdbcTemplate.update(
                """
                    INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
                    VALUES
                      (?, 'weapon.ak12', 2, 'all', null),
                      (?, 'module.scope.red_dot_01', 2, 'all', null),
                      (?, 'clothing.team_red.jacket_01', 2, 'specific', 'team.red'),
                      (?, 'clothing.team_blue.jacket_01', 2, 'specific', 'team.blue')
                    ON CONFLICT DO NOTHING
                    """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        jdbcTemplate.update(
                """
                    INSERT INTO outfit_item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
                    VALUES
                      (?, 'clothing.team_red.jacket_01', 2, 'specific', 'team.red'),
                      (?, 'clothing.team_blue.jacket_01', 2, 'specific', 'team.blue')
                    ON CONFLICT DO NOTHING
                    """,
                UUID.randomUUID(),
                UUID.randomUUID()
        );
        jdbcTemplate.update(
                """
                    INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect)
                    VALUES
                      ('weapon.ak12', 2, 'class.assault', 'allow'),
                      ('module.scope.red_dot_01', 2, 'class.assault', 'allow'),
                      ('clothing.team_red.jacket_01', 2, 'class.assault', 'allow'),
                      ('clothing.team_blue.jacket_01', 2, 'class.assault', 'allow')
                    ON CONFLICT DO NOTHING
                    """
        );
        jdbcTemplate.update(
                """
                    INSERT INTO weapon_module_mounts(mount_id, catalog_version, weapon_id, mount_type, mount_index, is_required, display_order)
                    VALUES ('weapon.ak12.mount.scope.01', 2, 'weapon.ak12', 'scope', 1, false, 10)
                    ON CONFLICT (mount_id, catalog_version) DO NOTHING
                    """
        );
        // Намеренно не добавляем weapon_mount_allowed_modules для v2: publish должен удалить невалидный module из durable preset.
    }

    private void seedDraftCatalogVersion(long catalogVersion) {
        OffsetDateTime now = OffsetDateTime.now();
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_versions(catalog_version, artifact_hash, schema_version, state, created_at)
                    VALUES (?, ?, 1, 'draft', ?)
                    ON CONFLICT (catalog_version) DO UPDATE SET
                      state = 'draft',
                      validated_at = null,
                      activated_at = null,
                      retired_at = null
                    """,
                catalogVersion,
                "integration-test-draft-" + catalogVersion,
                now
        );
    }

    private void seedCatalogVersionWithModuleMigration(long catalogVersion, String action, String mappedModuleId) {
        OffsetDateTime now = OffsetDateTime.now();
        String targetModuleId = mappedModuleId == null ? "module.scope.red_dot_01" : mappedModuleId;
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_versions(catalog_version, artifact_hash, schema_version, state, created_at, validated_at)
                    VALUES (?, ?, 1, 'validated', ?, ?)
                    ON CONFLICT (catalog_version) DO UPDATE SET
                      state = 'validated',
                      validated_at = EXCLUDED.validated_at,
                      retired_at = null
                    """,
                catalogVersion,
                "integration-test-catalog-v" + catalogVersion,
                now,
                now
        );
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_items(item_id, catalog_version, item_type, display_name, is_enabled, payload_schema_version, created_at)
                    VALUES
                      ('weapon.ak12', ?, 'weapon', 'AK-12 migration test', true, 1, ?),
                      (?, ?, 'module', 'Red Dot Sight migration test', true, 1, ?),
                      ('clothing.team_red.jacket_01', ?, 'clothing', 'Red Team Jacket migration test', true, 1, ?),
                      ('clothing.team_blue.jacket_01', ?, 'clothing', 'Blue Team Jacket migration test', true, 1, ?)
                    ON CONFLICT (item_id, catalog_version) DO NOTHING
                    """,
                catalogVersion,
                now,
                targetModuleId,
                catalogVersion,
                now,
                catalogVersion,
                now,
                catalogVersion,
                now
        );
        jdbcTemplate.update(
                """
                    INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
                    VALUES
                      (?, 'weapon.ak12', ?, 'all', null),
                      (?, ?, ?, 'all', null),
                      (?, 'clothing.team_red.jacket_01', ?, 'specific', 'team.red'),
                      (?, 'clothing.team_blue.jacket_01', ?, 'specific', 'team.blue')
                    ON CONFLICT DO NOTHING
                    """,
                UUID.randomUUID(),
                catalogVersion,
                UUID.randomUUID(),
                targetModuleId,
                catalogVersion,
                UUID.randomUUID(),
                catalogVersion,
                UUID.randomUUID(),
                catalogVersion
        );
        jdbcTemplate.update(
                """
                    INSERT INTO outfit_item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
                    VALUES
                      (?, 'clothing.team_red.jacket_01', ?, 'specific', 'team.red'),
                      (?, 'clothing.team_blue.jacket_01', ?, 'specific', 'team.blue')
                    ON CONFLICT DO NOTHING
                    """,
                UUID.randomUUID(),
                catalogVersion,
                UUID.randomUUID(),
                catalogVersion
        );
        jdbcTemplate.update(
                """
                    INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect)
                    VALUES
                      ('weapon.ak12', ?, 'class.assault', 'allow'),
                      (?, ?, 'class.assault', 'allow'),
                      ('clothing.team_red.jacket_01', ?, 'class.assault', 'allow'),
                      ('clothing.team_blue.jacket_01', ?, 'class.assault', 'allow')
                    ON CONFLICT DO NOTHING
                    """,
                catalogVersion,
                targetModuleId,
                catalogVersion,
                catalogVersion,
                catalogVersion
        );
        jdbcTemplate.update(
                """
                    INSERT INTO weapon_module_mounts(mount_id, catalog_version, weapon_id, mount_type, mount_index, is_required, display_order)
                    VALUES ('weapon.ak12.mount.scope.01', ?, 'weapon.ak12', 'scope', 1, false, 10)
                    ON CONFLICT (mount_id, catalog_version) DO NOTHING
                    """,
                catalogVersion
        );
        jdbcTemplate.update(
                """
                    INSERT INTO weapon_mount_allowed_modules(mount_id, module_id, catalog_version)
                    VALUES ('weapon.ak12.mount.scope.01', ?, ?)
                    ON CONFLICT DO NOTHING
                    """,
                targetModuleId,
                catalogVersion
        );
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_id_migration_map(
                      from_catalog_version,
                      to_catalog_version,
                      id_type,
                      old_id,
                      new_id,
                      migration_action
                    )
                    VALUES (1, ?, 'item', 'module.scope.red_dot_01', ?, ?)
                    ON CONFLICT (from_catalog_version, to_catalog_version, id_type, old_id)
                    DO UPDATE SET
                      new_id = EXCLUDED.new_id,
                      migration_action = EXCLUDED.migration_action
                    """,
                catalogVersion,
                mappedModuleId,
                action
        );
    }

    private JsonNode publishCatalog(long catalogVersion) throws Exception {
        return json(
                mockMvc.perform(postJson("/admin/catalog/publish", publishBody(catalogVersion, "integration test publish"))
                                .header("X-Admin-Token", ADMIN_TOKEN))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.active_catalog_version").value((int) catalogVersion))
                        .andExpect(jsonPath("$.action").value("publish"))
                        .andReturn()
        );
    }

    private Map<String, Object> publishBody(long catalogVersion, String reason) {
        return Map.of(
                "realm_id", "global",
                "catalog_version", catalogVersion,
                "rollout_percent", 100,
                "allow_existing_matches", true,
                "reason", reason
        );
    }

    private Map<String, Object> matchProfileBuildBody(UUID matchId, UUID playerId, long catalogVersion) {
        return Map.ofEntries(
                Map.entry("match_id", matchId.toString()),
                Map.entry("player_id", playerId.toString()),
                Map.entry("realm_id", "global"),
                Map.entry("class_tag", CLASS_TAG),
                Map.entry("team_tag", "team.red"),
                Map.entry("weapon_preset_slot", WEAPON_PRESET_SLOT),
                Map.entry("outfit_preset_slot", 1),
                Map.entry("supported_catalog_versions", List.of(catalogVersion)),
                Map.entry("preferred_catalog_version", catalogVersion),
                Map.entry("server_build_id", devServerBuildId()),
                Map.entry("game_mode_id", "tdm")
        );
    }

    private long activeCatalogVersion() {
        return jdbcTemplate.queryForObject(
                "SELECT catalog_version FROM catalog_deployments WHERE realm_id = 'global' AND deployment_state = 'active' AND allow_new_matches = true",
                Long.class
        );
    }

    private int activeNewMatchCatalogRows() {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM catalog_deployments
                    WHERE realm_id = 'global'
                      AND deployment_state = 'active'
                      AND allow_new_matches = true
                    """,
                Integer.class
        );
    }

    private long weaponPresetCatalogVersion(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT catalog_version
                    FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                Long.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT
        );
    }

    private long weaponPresetRevision(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT revision
                    FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                Long.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT
        );
    }

    private boolean weaponPresetSanitized(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT sanitized
                    FROM player_weapon_presets
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                    """,
                Boolean.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT
        );
    }

    private int moduleCount(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                      AND catalog_version = ?
                    """,
                Integer.class,
                playerId,
                catalogVersion
        );
    }

    private List<String> selectedModuleIds(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForList(
                """
                    SELECT module_id
                    FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                      AND catalog_version = ?
                    ORDER BY module_id
                    """,
                String.class,
                playerId,
                catalogVersion
        );
    }

    private int pendingCatalogConflictCount(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM post_match_pending_changes
                    WHERE player_id = ?
                      AND reason_code = 'catalog_conflict'
                      AND status = 'pending'
                    """,
                Integer.class,
                playerId
        );
    }

    private JsonNode latestPendingCatalogConflictPayload(UUID playerId) throws Exception {
        String payload = jdbcTemplate.queryForObject(
                """
                    SELECT payload::text
                    FROM post_match_pending_changes
                    WHERE player_id = ?
                      AND reason_code = 'catalog_conflict'
                      AND status = 'pending'
                    ORDER BY created_at DESC
                    LIMIT 1
                    """,
                String.class,
                playerId
        );
        return objectMapper.readTree(payload);
    }

    private int staleProfileCount(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_match_profiles
                    WHERE player_id = ?
                      AND is_stale = true
                    """,
                Integer.class,
                playerId
        );
    }

    private JsonNode findWeapon(JsonNode profile, String weaponSlotId) {
        for (JsonNode weapon : profile.path("weapons")) {
            if (weaponSlotId.equals(weapon.path("weapon_slot_id").asText())) {
                return weapon;
            }
        }
        throw new AssertionError("Weapon slot not found: " + weaponSlotId);
    }

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                UUID.fromString(DEV_SERVER_ID)
        );
    }

    private String devServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                UUID.fromString(DEV_SERVER_ID)
        );
    }

    private org.springframework.http.HttpHeaders serverHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Server-Id", DEV_SERVER_ID);
        headers.add("X-Server-Certificate-Fingerprint", devServerFingerprint());
        return headers;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(String url, Object body) throws Exception {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder = post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        if (url.startsWith("/admin/")) {
            builder.header("X-Admin-Confirm", "true");
        }
        return builder;
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
