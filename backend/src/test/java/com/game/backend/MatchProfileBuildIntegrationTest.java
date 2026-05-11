package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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
class MatchProfileBuildIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final String ADMIN_TOKEN = "dev-admin-token";
    private static final UUID DEV_SERVER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MODULE_ID = "module.scope.red_dot_01";
    private static final String RED_JACKET_ID = "clothing.team_red.jacket_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldBuildPersistAndReturnAuthoritativeMatchProfileSnapshot() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();
        UUID matchId = UUID.randomUUID();

        JsonNode profile = json(
                mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                matchId,
                                playerId,
                                catalogVersion,
                                List.of(catalogVersion),
                                catalogVersion
                        ))
                                .headers(serverHeaders()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.schema_version").value(1))
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.realm_id").value("global"))
                        .andExpect(jsonPath("$.catalog_version").value(catalogVersion))
                        .andExpect(jsonPath("$.class_tag").value(CLASS_TAG))
                        .andExpect(jsonPath("$.team_tag").value(TEAM_RED))
                        .andExpect(jsonPath("$.weapon_preset_slot").value(WEAPON_PRESET_SLOT))
                        .andExpect(jsonPath("$.outfit_preset_slot").value(OUTFIT_PRESET_SLOT))
                        .andExpect(jsonPath("$.sanitized_warnings").isArray())
                        .andReturn()
        );

        JsonNode primary = findWeapon(profile, "primary");
        assertThat(primary.path("weapon_id").asText()).isEqualTo(WEAPON_ID);
        assertThat(primary.path("modules").size()).isEqualTo(1);
        assertThat(primary.path("modules").get(0).path("module_id").asText()).isEqualTo(MODULE_ID);

        JsonNode grenade = findWeapon(profile, "grenade");
        assertThat(grenade.path("weapon_id").isNull()).isTrue();
        assertThat(grenade.path("modules").size()).isZero();

        JsonNode torso = findOutfitItem(profile, "torso");
        assertThat(torso.path("item_id").asText()).isEqualTo(RED_JACKET_ID);

        assertThat(profile.path("dependency_revisions").path("weapon_preset_revision").asLong())
                .isEqualTo(weaponPresetRevision(playerId));
        assertThat(profile.path("dependency_revisions").path("outfit_preset_revision").asLong())
                .isEqualTo(outfitPresetRevision(playerId));
        assertThat(profile.path("dependency_revisions").path("access_revision").asLong())
                .isEqualTo(accessRevision(playerId));
        assertThat(profile.path("dependency_revisions").path("profile_revision").asLong()).isPositive();

        assertThat(matchProfileCount(playerId, catalogVersion)).isEqualTo(1);
        assertThat(freshMatchProfileCount(playerId, catalogVersion)).isEqualTo(1);
        assertThat(serverAuditCount(matchId, "match_profile.build", "success")).isEqualTo(1);
    }

    @Test
    void shouldRejectUnsupportedCatalogVersionAndNotPersistProfile() throws Exception {
        UUID playerId = registerPlayer();
        long activeCatalogVersion = activeCatalogVersion();
        long unsupportedCatalogVersion = activeCatalogVersion + 10_000;
        UUID matchId = UUID.randomUUID();

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                matchId,
                                playerId,
                                activeCatalogVersion,
                                List.of(unsupportedCatalogVersion),
                                unsupportedCatalogVersion
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATALOG_VERSION_NOT_SUPPORTED"));

        assertThat(matchProfileCount(playerId, activeCatalogVersion)).isZero();
        assertThat(serverAuditCount(matchId, "match_profile.build", "failed")).isEqualTo(1);
        assertThat(serverAuditCodeCount(matchId, "CATALOG_VERSION_NOT_SUPPORTED")).isEqualTo(1);
    }

    @Test
    void shouldMarkOldProfileStaleAfterAccessChangeAndBuildFreshProfileFromSanitizedPreset() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();
        UUID firstMatchId = UUID.randomUUID();

        JsonNode firstProfile = json(
                mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                firstMatchId,
                                playerId,
                                catalogVersion,
                                List.of(catalogVersion),
                                catalogVersion
                        ))
                                .headers(serverHeaders()))
                        .andExpect(status().isOk())
                        .andReturn()
        );
        assertThat(findWeapon(firstProfile, "primary").path("modules").size()).isEqualTo(1);
        assertThat(freshMatchProfileCount(playerId, catalogVersion)).isEqualTo(1);

        JsonNode adminResponse = json(
                mockMvc.perform(postJson("/admin/players/" + playerId + "/access/items/" + MODULE_ID, accessBody(
                                        catalogVersion,
                                        false,
                                        false,
                                        false,
                                        true,
                                        "admin_disabled",
                                        "match-profile-test: disable selected module"
                                ))
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .header("X-Admin-Id", "match-profile-test-admin")
                                .header("Idempotency-Key", "match-profile-stale:" + UUID.randomUUID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.sanitized_weapon_presets").value(1))
                        .andExpect(jsonPath("$.stale_match_profiles").value(1))
                        .andReturn()
        );
        assertThat(adminResponse.path("access_revision").asLong()).isEqualTo(accessRevision(playerId));
        assertThat(staleMatchProfileCount(playerId, catalogVersion, "access_changed")).isEqualTo(1);
        assertThat(freshMatchProfileCount(playerId, catalogVersion)).isZero();

        UUID secondMatchId = UUID.randomUUID();
        JsonNode secondProfile = json(
                mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                secondMatchId,
                                playerId,
                                catalogVersion,
                                List.of(catalogVersion),
                                catalogVersion
                        ))
                                .headers(serverHeaders()))
                        .andExpect(status().isOk())
                        .andReturn()
        );

        JsonNode primary = findWeapon(secondProfile, "primary");
        assertThat(primary.path("weapon_id").asText()).isEqualTo(WEAPON_ID);
        assertThat(primary.path("modules").size()).isZero();
        assertThat(secondProfile.path("dependency_revisions").path("weapon_preset_revision").asLong())
                .isEqualTo(weaponPresetRevision(playerId));
        assertThat(secondProfile.path("dependency_revisions").path("access_revision").asLong())
                .isEqualTo(accessRevision(playerId));

        assertThat(matchProfileCount(playerId, catalogVersion)).isEqualTo(2);
        assertThat(staleMatchProfileCount(playerId, catalogVersion, "access_changed")).isEqualTo(1);
        assertThat(freshMatchProfileCount(playerId, catalogVersion)).isEqualTo(1);
        assertThat(serverAuditCount(secondMatchId, "match_profile.build", "success")).isEqualTo(1);
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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

    private Map<String, Object> matchProfileBuildBody(
            UUID matchId,
            UUID playerId,
            long ignoredCatalogVersion,
            List<Long> supportedCatalogVersions,
            Long preferredCatalogVersion
    ) {
        return Map.ofEntries(
                Map.entry("match_id", matchId.toString()),
                Map.entry("player_id", playerId.toString()),
                Map.entry("realm_id", "global"),
                Map.entry("class_tag", CLASS_TAG),
                Map.entry("team_tag", TEAM_RED),
                Map.entry("weapon_preset_slot", WEAPON_PRESET_SLOT),
                Map.entry("outfit_preset_slot", OUTFIT_PRESET_SLOT),
                Map.entry("supported_catalog_versions", supportedCatalogVersions),
                Map.entry("preferred_catalog_version", preferredCatalogVersion),
                Map.entry("server_build_id", devServerBuildId()),
                Map.entry("game_mode_id", "tdm")
        );
    }

    private Map<String, Object> accessBody(
            long catalogVersion,
            boolean hidden,
            boolean lockedInShop,
            boolean lockedByQuest,
            boolean disabled,
            String disabledReason,
            String reason
    ) {
        return Map.of(
                "catalog_version", catalogVersion,
                "hidden", hidden,
                "locked_in_shop", lockedInShop,
                "locked_by_quest", lockedByQuest,
                "disabled", disabled,
                "disabled_reason", disabledReason == null ? "" : disabledReason,
                "unlock_hint_code", disabled ? "admin_disabled" : "unavailable",
                "unlock_hint_payload", Map.of("source", "MatchProfileBuildIntegrationTest"),
                "reason", reason
        );
    }

    private JsonNode findWeapon(JsonNode profile, String weaponSlotId) {
        for (JsonNode weapon : profile.path("weapons")) {
            if (weaponSlotId.equals(weapon.path("weapon_slot_id").asText())) {
                return weapon;
            }
        }
        throw new AssertionError("Weapon slot not found in match profile: " + weaponSlotId);
    }

    private JsonNode findOutfitItem(JsonNode profile, String clothingSlotId) {
        for (JsonNode item : profile.path("outfit")) {
            if (clothingSlotId.equals(item.path("clothing_slot_id").asText())) {
                return item;
            }
        }
        throw new AssertionError("Clothing slot not found in match profile: " + clothingSlotId);
    }

    private org.springframework.http.HttpHeaders serverHeaders() {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.add("X-Server-Id", DEV_SERVER_ID.toString());
        headers.add("X-Server-Certificate-Fingerprint", devServerFingerprint());
        return headers;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
            String url,
            Object body
    ) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private long activeCatalogVersion() {
        return jdbcTemplate.queryForObject(
                """
                    SELECT catalog_version
                    FROM catalog_deployments
                    WHERE realm_id = 'global'
                      AND deployment_state = 'active'
                      AND allow_new_matches = true
                    ORDER BY activated_at DESC NULLS LAST, catalog_version DESC
                    LIMIT 1
                    """,
                Long.class
        );
    }

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
        );
    }

    private String devServerFingerprint() {
        return jdbcTemplate.queryForObject(
                "SELECT certificate_fingerprint FROM server_identities WHERE server_id = ?",
                String.class,
                DEV_SERVER_ID
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

    private long outfitPresetRevision(UUID playerId) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT revision
                    FROM player_outfit_presets
                    WHERE player_id = ?
                      AND team_tag = ?
                      AND class_tag = ?
                      AND outfit_preset_slot = ?
                    """,
                Long.class,
                playerId,
                TEAM_RED,
                CLASS_TAG,
                OUTFIT_PRESET_SLOT
        );
    }

    private long accessRevision(UUID playerId) {
        return jdbcTemplate.queryForObject(
                "SELECT access_revision FROM player_access_projection_state WHERE player_id = ?",
                Long.class,
                playerId
        );
    }

    private int matchProfileCount(UUID playerId, long catalogVersion) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_match_profiles
                    WHERE player_id = ?
                      AND catalog_version = ?
                    """,
                Integer.class,
                playerId,
                catalogVersion
        );
        return count == null ? 0 : count;
    }

    private int freshMatchProfileCount(UUID playerId, long catalogVersion) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_match_profiles
                    WHERE player_id = ?
                      AND catalog_version = ?
                      AND is_stale = false
                    """,
                Integer.class,
                playerId,
                catalogVersion
        );
        return count == null ? 0 : count;
    }

    private int staleMatchProfileCount(UUID playerId, long catalogVersion, String staleReason) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_match_profiles
                    WHERE player_id = ?
                      AND catalog_version = ?
                      AND is_stale = true
                      AND stale_reason = ?
                    """,
                Integer.class,
                playerId,
                catalogVersion,
                staleReason
        );
        return count == null ? 0 : count;
    }

    private int serverAuditCount(UUID matchId, String action, String result) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM server_audit_events
                    WHERE match_id = ?
                      AND action = ?
                      AND result = ?
                    """,
                Integer.class,
                matchId,
                action,
                result
        );
        return count == null ? 0 : count;
    }

    private int serverAuditCodeCount(UUID matchId, String code) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM server_audit_events
                    WHERE match_id = ?
                      AND action = 'match_profile.build'
                      AND payload->>'code' = ?
                    """,
                Integer.class,
                matchId,
                code
        );
        return count == null ? 0 : count;
    }
}
