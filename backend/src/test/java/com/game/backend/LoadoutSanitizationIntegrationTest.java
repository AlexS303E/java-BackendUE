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
class LoadoutSanitizationIntegrationTest {
    private static final String PASSWORD = "password123";
    private static final String ADMIN_TOKEN = "dev-admin-token";
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;
    private static final String TEAM_RED = "team.red";
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MODULE_ID = "module.scope.red_dot_01";
    private static final String MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String RED_JACKET_ID = "clothing.team_red.jacket_01";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSanitizeOnlyUnavailableModuleAndKeepWeaponPresetContainer() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        long initialRevision = weaponPresetRevision(playerId);

        assertThat(selectedWeapon(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(weaponConfigCount(playerId, catalogVersion, WEAPON_ID)).isEqualTo(1);
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isEqualTo(1);

        JsonNode response = json(
                mockMvc.perform(postJson("/admin/players/" + playerId + "/access/items/" + MODULE_ID, accessBody(
                                        catalogVersion,
                                        false,
                                        false,
                                        false,
                                        true,
                                        "admin_disabled",
                                        "sanitization-test: disable module"
                                ))
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .header("X-Admin-Id", "loadout-sanitization-test")
                                .header("Idempotency-Key", "sanitize-module:" + UUID.randomUUID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_id").value(playerId.toString()))
                        .andExpect(jsonPath("$.item_id").value(MODULE_ID))
                        .andExpect(jsonPath("$.player_can_use").value(false))
                        .andExpect(jsonPath("$.sanitized_weapon_presets").value(1))
                        .andExpect(jsonPath("$.sanitized_outfit_presets").value(0))
                        .andExpect(jsonPath("$.duplicate").value(false))
                        .andReturn()
        );

        UUID ledgerEventId = UUID.fromString(response.path("ledger_event_id").asText());
        assertThat(weaponPresetRevision(playerId)).isEqualTo(initialRevision + 1);
        assertThat(weaponPresetSanitized(playerId)).isTrue();
        assertThat(selectedWeapon(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(weaponConfigCount(playerId, catalogVersion, WEAPON_ID)).isEqualTo(1);
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isZero();
        assertThat(outfitItemCount(playerId, catalogVersion, TEAM_RED, RED_JACKET_ID)).isEqualTo(1);

        assertThat(outboxCount("weapon_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(notificationCount("weapon_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(notificationCount("player_access.changed", playerId)).isEqualTo(1);
        assertThat(ledgerPayloadSanitizedWeaponPresets(ledgerEventId)).isEqualTo(1);
        assertThat(ledgerPayloadSanitizedOutfitPresets(ledgerEventId)).isZero();
    }

    @Test
    void shouldSanitizeUnavailableWeaponAndRemoveItsConfigs() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        long initialRevision = weaponPresetRevision(playerId);

        assertThat(selectedWeapon(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(weaponConfigCount(playerId, catalogVersion, WEAPON_ID)).isEqualTo(1);
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isEqualTo(1);

        JsonNode response = json(
                mockMvc.perform(postJson("/admin/players/" + playerId + "/access/items/" + WEAPON_ID, accessBody(
                                        catalogVersion,
                                        false,
                                        true,
                                        false,
                                        false,
                                        null,
                                        "sanitization-test: lock weapon in shop"
                                ))
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .header("X-Admin-Id", "loadout-sanitization-test")
                                .header("Idempotency-Key", "sanitize-weapon:" + UUID.randomUUID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_can_use").value(false))
                        .andExpect(jsonPath("$.sanitized_weapon_presets").value(1))
                        .andExpect(jsonPath("$.sanitized_outfit_presets").value(0))
                        .andReturn()
        );

        UUID ledgerEventId = UUID.fromString(response.path("ledger_event_id").asText());
        assertThat(weaponPresetRevision(playerId)).isEqualTo(initialRevision + 1);
        assertThat(weaponPresetSanitized(playerId)).isTrue();
        assertThat(selectedWeapon(playerId, catalogVersion)).isNull();
        assertThat(weaponConfigCount(playerId, catalogVersion, WEAPON_ID)).isZero();
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isZero();
        assertThat(outfitItemCount(playerId, catalogVersion, TEAM_RED, RED_JACKET_ID)).isEqualTo(1);

        assertThat(outboxCount("weapon_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(notificationCount("weapon_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(ledgerPayloadSanitizedWeaponPresets(ledgerEventId)).isEqualTo(1);
        assertThat(ledgerPayloadSanitizedOutfitPresets(ledgerEventId)).isZero();
    }

    @Test
    void shouldSanitizeUnavailableClothingAndKeepWeaponPresetUntouched() throws Exception {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        long initialWeaponRevision = weaponPresetRevision(playerId);
        long initialOutfitRevision = outfitPresetRevision(playerId, TEAM_RED);

        assertThat(outfitItemCount(playerId, catalogVersion, TEAM_RED, RED_JACKET_ID)).isEqualTo(1);
        assertThat(selectedWeapon(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isEqualTo(1);

        JsonNode response = json(
                mockMvc.perform(postJson("/admin/players/" + playerId + "/access/items/" + RED_JACKET_ID, accessBody(
                                        catalogVersion,
                                        true,
                                        false,
                                        false,
                                        false,
                                        null,
                                        "sanitization-test: hide clothing"
                                ))
                                .header("X-Admin-Token", ADMIN_TOKEN)
                                .header("X-Admin-Id", "loadout-sanitization-test")
                                .header("Idempotency-Key", "sanitize-clothing:" + UUID.randomUUID()))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.player_can_use").value(false))
                        .andExpect(jsonPath("$.sanitized_weapon_presets").value(0))
                        .andExpect(jsonPath("$.sanitized_outfit_presets").value(1))
                        .andReturn()
        );

        UUID ledgerEventId = UUID.fromString(response.path("ledger_event_id").asText());
        assertThat(weaponPresetRevision(playerId)).isEqualTo(initialWeaponRevision);
        assertThat(weaponPresetSanitized(playerId)).isFalse();
        assertThat(selectedWeapon(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(moduleCount(playerId, catalogVersion, MODULE_ID)).isEqualTo(1);

        assertThat(outfitPresetRevision(playerId, TEAM_RED)).isEqualTo(initialOutfitRevision + 1);
        assertThat(outfitPresetSanitized(playerId, TEAM_RED)).isTrue();
        assertThat(outfitItemCount(playerId, catalogVersion, TEAM_RED, RED_JACKET_ID)).isZero();

        assertThat(outboxCount("outfit_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(notificationCount("outfit_preset.sanitized", playerId)).isEqualTo(1);
        assertThat(ledgerPayloadSanitizedWeaponPresets(ledgerEventId)).isZero();
        assertThat(ledgerPayloadSanitizedOutfitPresets(ledgerEventId)).isEqualTo(1);
    }

    private UUID registerPlayer() throws Exception {
        String loginName = "sanitize_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
                "unlock_hint_code", hidden ? "hidden" : (lockedInShop ? "buy_in_shop" : (disabled ? "admin_disabled" : "unavailable")),
                "unlock_hint_payload", Map.of("source", "LoadoutSanitizationIntegrationTest"),
                "reason", reason
        );
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
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
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
        ));
    }

    private String selectedWeapon(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForObject(
                """
                    SELECT selected_weapon_id
                    FROM player_weapon_preset_slots
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_slot_id = ?
                    """,
                String.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID
        );
    }

    private int weaponConfigCount(UUID playerId, long catalogVersion, String weaponId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_weapon_preset_weapon_configs
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_slot_id = ?
                      AND weapon_id = ?
                    """,
                Integer.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                weaponId
        );
        return count == null ? 0 : count;
    }

    private int moduleCount(UUID playerId, long catalogVersion, String moduleId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_weapon_preset_weapon_config_modules
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_slot_id = ?
                      AND weapon_id = ?
                      AND mount_id = ?
                      AND module_id = ?
                    """,
                Integer.class,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                WEAPON_ID,
                MOUNT_ID,
                moduleId
        );
        return count == null ? 0 : count;
    }

    private long outfitPresetRevision(UUID playerId, String teamTag) {
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
                teamTag,
                CLASS_TAG,
                OUTFIT_PRESET_SLOT
        );
    }

    private boolean outfitPresetSanitized(UUID playerId, String teamTag) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                """
                    SELECT sanitized
                    FROM player_outfit_presets
                    WHERE player_id = ?
                      AND team_tag = ?
                      AND class_tag = ?
                      AND outfit_preset_slot = ?
                    """,
                Boolean.class,
                playerId,
                teamTag,
                CLASS_TAG,
                OUTFIT_PRESET_SLOT
        ));
    }

    private int outfitItemCount(UUID playerId, long catalogVersion, String teamTag, String itemId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_outfit_preset_items
                    WHERE player_id = ?
                      AND team_tag = ?
                      AND class_tag = ?
                      AND outfit_preset_slot = ?
                      AND catalog_version = ?
                      AND item_id = ?
                    """,
                Integer.class,
                playerId,
                teamTag,
                CLASS_TAG,
                OUTFIT_PRESET_SLOT,
                catalogVersion,
                itemId
        );
        return count == null ? 0 : count;
    }

    private int outboxCount(String eventType, UUID playerId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM outbox_events
                    WHERE event_type = ?
                      AND aggregate_id LIKE ?
                    """,
                Integer.class,
                eventType,
                playerId + ":%"
        );
        return count == null ? 0 : count;
    }

    private int notificationCount(String eventType, UUID playerId) {
        Integer count = jdbcTemplate.queryForObject(
                """
                    SELECT count(*)
                    FROM player_notifications
                    WHERE player_id = ?
                      AND event_type = ?
                    """,
                Integer.class,
                playerId,
                eventType
        );
        return count == null ? 0 : count;
    }

    private int ledgerPayloadSanitizedWeaponPresets(UUID ledgerEventId) {
        return jdbcTemplate.queryForObject(
                "SELECT (payload->>'sanitized_weapon_presets')::int FROM entitlement_ledger WHERE ledger_event_id = ?",
                Integer.class,
                ledgerEventId
        );
    }

    private int ledgerPayloadSanitizedOutfitPresets(UUID ledgerEventId) {
        return jdbcTemplate.queryForObject(
                "SELECT (payload->>'sanitized_outfit_presets')::int FROM entitlement_ledger WHERE ledger_event_id = ?",
                Integer.class,
                ledgerEventId
        );
    }
}
