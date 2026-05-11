package com.game.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
class LoadoutValidationIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final String DEV_SERVER_ID = "10000000-0000-0000-0000-000000000001";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MODULE_ID = "module.scope.red_dot_01";
    private static final String MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String RED_JACKET_ID = "clothing.team_red.jacket_01";
    private static final String BLUE_JACKET_ID = "clothing.team_blue.jacket_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldRejectInvalidLoadoutOnWeaponPresetSave() throws Exception {
        PlayerContext player = registerAndLoginPlayer();
        long revision = weaponPresetRevision(player.playerId());

        expectSaveValidationFailure(
                player,
                revision,
                weaponSlot("weapon.missing", List.of())
        );
        assertThat(weaponPresetRevision(player.playerId())).isEqualTo(revision);

        expectSaveValidationFailure(
                player,
                revision,
                weaponSlot(WEAPON_ID, List.of(module("module.missing", MOUNT_ID)))
        );
        assertThat(weaponPresetRevision(player.playerId())).isEqualTo(revision);

        expectSaveValidationFailure(
                player,
                revision,
                weaponSlot(WEAPON_ID, List.of(module(MODULE_ID, "weapon.ak12.mount.scope.99")))
        );
        assertThat(weaponPresetRevision(player.playerId())).isEqualTo(revision);

        String disallowedModuleId = insertUsableModuleWithoutAllowedMount(player.playerId(), player.catalogVersion());
        expectSaveValidationFailure(
                player,
                revision,
                weaponSlot(WEAPON_ID, List.of(module(disallowedModuleId, MOUNT_ID)))
        );
        assertThat(weaponPresetRevision(player.playerId())).isEqualTo(revision);

        setPlayerItemAccessFlags(player.playerId(), WEAPON_ID, player.catalogVersion(), false, false, false, true);
        expectSaveValidationFailure(
                player,
                revision,
                weaponSlot(WEAPON_ID, List.of(module(MODULE_ID, MOUNT_ID)))
        );
        assertThat(weaponPresetRevision(player.playerId())).isEqualTo(revision);
    }

    @Test
    void shouldRejectInvalidDurableLoadoutDuringMatchProfileBuild() throws Exception {
        PlayerContext disabledModulePlayer = registerAndLoginPlayer();
        UUID disabledModuleMatchId = UUID.randomUUID();
        setPlayerItemAccessFlags(disabledModulePlayer.playerId(), MODULE_ID, disabledModulePlayer.catalogVersion(), false, false, false, true);

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                disabledModuleMatchId,
                                disabledModulePlayer.playerId(),
                                "team.red",
                                disabledModulePlayer.catalogVersion()
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOADOUT_VALIDATION_FAILED"));

        assertThat(profileCount(disabledModulePlayer.playerId(), disabledModuleMatchId)).isZero();

        PlayerContext hiddenWeaponPlayer = registerAndLoginPlayer();
        UUID hiddenWeaponMatchId = UUID.randomUUID();
        setPlayerItemAccessFlags(hiddenWeaponPlayer.playerId(), WEAPON_ID, hiddenWeaponPlayer.catalogVersion(), true, false, false, false);

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                hiddenWeaponMatchId,
                                hiddenWeaponPlayer.playerId(),
                                "team.red",
                                hiddenWeaponPlayer.catalogVersion()
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOADOUT_VALIDATION_FAILED"));

        assertThat(profileCount(hiddenWeaponPlayer.playerId(), hiddenWeaponMatchId)).isZero();

        PlayerContext lockedClothingPlayer = registerAndLoginPlayer();
        UUID lockedClothingMatchId = UUID.randomUUID();
        setPlayerItemAccessFlags(lockedClothingPlayer.playerId(), RED_JACKET_ID, lockedClothingPlayer.catalogVersion(), false, true, false, false);

        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                lockedClothingMatchId,
                                lockedClothingPlayer.playerId(),
                                "team.red",
                                lockedClothingPlayer.catalogVersion()
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOADOUT_VALIDATION_FAILED"));

        assertThat(profileCount(lockedClothingPlayer.playerId(), lockedClothingMatchId)).isZero();
    }

    @Test
    void shouldValidateClothingCatalogAccessAndTeamRulesInMatchProfile() throws Exception {
        PlayerContext catalogDisabledClothingPlayer = registerAndLoginPlayer();
        UUID disabledClothingItemId = UUID.randomUUID();
        String disabledClothingId = "clothing.test.disabled." + disabledClothingItemId;
        insertPlayerClothing(
                catalogDisabledClothingPlayer.playerId(),
                catalogDisabledClothingPlayer.catalogVersion(),
                disabledClothingId,
                false,
                "team.red"
        );
        setOutfitTorso(catalogDisabledClothingPlayer.playerId(), "team.red", catalogDisabledClothingPlayer.catalogVersion(), disabledClothingId);

        UUID catalogDisabledMatchId = UUID.randomUUID();
        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                catalogDisabledMatchId,
                                catalogDisabledClothingPlayer.playerId(),
                                "team.red",
                                catalogDisabledClothingPlayer.catalogVersion()
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOADOUT_VALIDATION_FAILED"));

        assertThat(profileCount(catalogDisabledClothingPlayer.playerId(), catalogDisabledMatchId)).isZero();

        PlayerContext wrongTeamClothingPlayer = registerAndLoginPlayer();
        setOutfitTorso(wrongTeamClothingPlayer.playerId(), "team.red", wrongTeamClothingPlayer.catalogVersion(), BLUE_JACKET_ID);

        UUID wrongTeamMatchId = UUID.randomUUID();
        mockMvc.perform(postJson("/server/match-profile/build", matchProfileBuildBody(
                                wrongTeamMatchId,
                                wrongTeamClothingPlayer.playerId(),
                                "team.red",
                                wrongTeamClothingPlayer.catalogVersion()
                        ))
                        .headers(serverHeaders()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sanitized_warnings[0]").value(org.hamcrest.Matchers.containsString("restricted for team")));

        assertThat(profileCount(wrongTeamClothingPlayer.playerId(), wrongTeamMatchId)).isEqualTo(1);
    }

    private PlayerContext registerAndLoginPlayer() throws Exception {
        String loginName = "loadout_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        JsonNode registered = json(
                mockMvc.perform(postJson("/auth/register", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isCreated())
                        .andReturn()
        );
        UUID playerId = UUID.fromString(registered.path("player_id").asText());
        JsonNode login = json(
                mockMvc.perform(postJson("/auth/login", Map.of(
                                "login_name", loginName,
                                "password", PASSWORD
                        )))
                        .andExpect(status().isOk())
                        .andReturn()
        );
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        return new PlayerContext(playerId, login.path("access_token").asText(), catalogVersion);
    }

    private void expectSaveValidationFailure(
            PlayerContext player,
            long revision,
            Map<String, Object> primarySlot
    ) throws Exception {
        mockMvc.perform(putJson("/me/presets/weapons/" + CLASS_TAG + "/" + WEAPON_PRESET_SLOT, weaponPresetSaveBody(player.catalogVersion(), primarySlot))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + player.accessToken())
                        .header(HttpHeaders.IF_MATCH, "\"" + revision + "\""))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("LOADOUT_VALIDATION_FAILED"));
    }

    private Map<String, Object> weaponPresetSaveBody(long catalogVersion, Map<String, Object> primarySlot) {
        return Map.of(
                "catalog_version", catalogVersion,
                "slots", List.of(primarySlot, weaponSlot(null, List.of()))
        );
    }

    private Map<String, Object> weaponSlot(String weaponId, List<Map<String, Object>> modules) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("weapon_slot_id", weaponId == null ? "grenade" : WEAPON_SLOT_ID);
        slot.put("weapon_id", weaponId);
        slot.put("modules", modules);
        return slot;
    }

    private Map<String, Object> module(String moduleId, String mountId) {
        return Map.of(
                "mount_id", mountId,
                "module_id", moduleId
        );
    }

    private Map<String, Object> matchProfileBuildBody(UUID matchId, UUID playerId, String teamTag, long catalogVersion) {
        return Map.ofEntries(
                Map.entry("match_id", matchId.toString()),
                Map.entry("player_id", playerId.toString()),
                Map.entry("realm_id", "global"),
                Map.entry("class_tag", CLASS_TAG),
                Map.entry("team_tag", teamTag),
                Map.entry("weapon_preset_slot", WEAPON_PRESET_SLOT),
                Map.entry("outfit_preset_slot", OUTFIT_PRESET_SLOT),
                Map.entry("supported_catalog_versions", List.of(catalogVersion)),
                Map.entry("preferred_catalog_version", catalogVersion),
                Map.entry("server_build_id", devServerBuildId()),
                Map.entry("game_mode_id", "tdm")
        );
    }

    private String insertUsableModuleWithoutAllowedMount(UUID playerId, long catalogVersion) {
        String moduleId = "module.test.disallowed." + UUID.randomUUID();
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_items(
                      item_id,
                      catalog_version,
                      item_type,
                      display_name,
                      is_enabled,
                      payload_schema_version,
                      created_at
                    )
                    VALUES (?, ?, 'module', 'Disallowed Test Module', true, 1, ?)
                    """,
                moduleId,
                catalogVersion,
                OffsetDateTime.now()
        );
        jdbcTemplate.update(
                "INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag) VALUES (?, ?, ?, 'all', null)",
                UUID.randomUUID(),
                moduleId,
                catalogVersion
        );
        jdbcTemplate.update(
                "INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect) VALUES (?, ?, ?, 'allow')",
                moduleId,
                catalogVersion,
                CLASS_TAG
        );
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
                      updated_at
                    )
                    VALUES (?, ?, ?, false, false, false, false, ?)
                    """,
                playerId,
                moduleId,
                catalogVersion,
                OffsetDateTime.now()
        );
        return moduleId;
    }

    private void insertPlayerClothing(
            UUID playerId,
            long catalogVersion,
            String itemId,
            boolean catalogEnabled,
            String teamTag
    ) {
        jdbcTemplate.update(
                """
                    INSERT INTO catalog_items(
                      item_id,
                      catalog_version,
                      item_type,
                      display_name,
                      is_enabled,
                      payload_schema_version,
                      created_at
                    )
                    VALUES (?, ?, 'clothing', 'Test Clothing', ?, 1, ?)
                    """,
                itemId,
                catalogVersion,
                catalogEnabled,
                OffsetDateTime.now()
        );
        jdbcTemplate.update(
                "INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag) VALUES (?, ?, ?, 'specific', ?)",
                UUID.randomUUID(),
                itemId,
                catalogVersion,
                teamTag
        );
        jdbcTemplate.update(
                "INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect) VALUES (?, ?, ?, 'allow')",
                itemId,
                catalogVersion,
                CLASS_TAG
        );
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
                      updated_at
                    )
                    VALUES (?, ?, ?, false, false, false, false, ?)
                    """,
                playerId,
                itemId,
                catalogVersion,
                OffsetDateTime.now()
        );
    }

    private void setOutfitTorso(UUID playerId, String teamTag, long catalogVersion, String itemId) {
        jdbcTemplate.update(
                """
                    UPDATE player_outfit_preset_items
                    SET item_id = ?
                    WHERE player_id = ?
                      AND team_tag = ?
                      AND class_tag = ?
                      AND outfit_preset_slot = ?
                      AND catalog_version = ?
                      AND clothing_slot_id = 'torso'
                    """,
                itemId,
                playerId,
                teamTag,
                CLASS_TAG,
                OUTFIT_PRESET_SLOT,
                catalogVersion
        );
    }

    private void setPlayerItemAccessFlags(
            UUID playerId,
            String itemId,
            long catalogVersion,
            boolean hidden,
            boolean lockedInShop,
            boolean lockedByQuest,
            boolean disabled
    ) {
        jdbcTemplate.update(
                """
                    UPDATE player_item_access
                    SET is_hidden = ?,
                        is_locked_in_shop = ?,
                        is_locked_by_quest = ?,
                        is_disabled = ?,
                        updated_at = ?
                    WHERE player_id = ?
                      AND item_id = ?
                      AND catalog_version = ?
                    """,
                hidden,
                lockedInShop,
                lockedByQuest,
                disabled,
                OffsetDateTime.now(),
                playerId,
                itemId,
                catalogVersion
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

    private int profileCount(UUID playerId, UUID matchId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM server_audit_events
                    WHERE match_id = ?
                      AND action = 'match_profile.build'
                      AND result = 'success'
                      AND payload->>'player_id' = ?
                    """,
                Integer.class,
                matchId,
                playerId.toString()
        );
        return count == null ? 0 : count;
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

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder postJson(
            String url,
            Object body
    ) throws Exception {
        return post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder putJson(
            String url,
            Object body
    ) throws Exception {
        return put(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private record PlayerContext(UUID playerId, String accessToken, long catalogVersion) {
    }
}
