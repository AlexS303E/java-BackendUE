package com.game.backend;

import com.game.backend.access.application.ItemAccessPolicy;
import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.application.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "app.outbox.worker-enabled=false",
                "app.server-auth.mtls.enabled=false",
                "app.server-auth.mtls.require-private-port=false",
                "app.server-auth.mtls.allow-header-fingerprint-fallback=true"
        }
)
@ActiveProfiles("local")
class ItemAccessPolicyIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MODULE_ID = "module.scope.red_dot_01";

    @Autowired
    private AuthService authService;

    @Autowired
    private ItemAccessPolicy itemAccessPolicy;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyUiPresetRuntimeAndMatchProfileAccessRulesConsistently() {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);

        assertThat(itemAccessPolicy.canUseForUi(true, false, false, false, false)).isTrue();
        assertThat(itemAccessPolicy.canUseForUi(true, false, true, false, false)).isFalse();
        assertThat(itemAccessPolicy.canUseForUi(false, false, false, false, false)).isFalse();

        assertThat(itemAccessPolicy.canUseForPresetSave(playerId, WEAPON_ID, catalogVersion, CLASS_TAG, "weapon")).isTrue();
        assertThat(itemAccessPolicy.canUseForRuntimePresetChange(playerId, MODULE_ID, catalogVersion, CLASS_TAG, "module")).isTrue();
        assertThat(itemAccessPolicy.usableItemsForMatchProfile(playerId, catalogVersion, CLASS_TAG, Set.of(WEAPON_ID, MODULE_ID)))
                .containsExactlyInAnyOrder(WEAPON_ID, MODULE_ID);

        setPlayerItemAccessFlags(playerId, MODULE_ID, catalogVersion, false, false, true, false);

        assertThat(itemAccessPolicy.canUseForPresetSave(playerId, MODULE_ID, catalogVersion, CLASS_TAG, "module")).isFalse();
        assertThat(itemAccessPolicy.canUseForRuntimePresetChange(playerId, MODULE_ID, catalogVersion, CLASS_TAG, "module")).isFalse();
        assertThat(itemAccessPolicy.usableItemsForMatchProfile(playerId, catalogVersion, CLASS_TAG, Set.of(WEAPON_ID, MODULE_ID)))
                .containsExactly(WEAPON_ID);

        String disabledCatalogItem = insertDisabledCatalogModule(playerId, catalogVersion);
        assertThat(itemAccessPolicy.canUseForPresetSave(playerId, disabledCatalogItem, catalogVersion, CLASS_TAG, "module")).isFalse();
    }

    private UUID registerPlayer() {
        String loginName = "policy_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(new RegisterRequest(loginName, "password123")).playerId();
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

    private String insertDisabledCatalogModule(UUID playerId, long catalogVersion) {
        String itemId = "module.test.disabled." + UUID.randomUUID();
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
                    VALUES (?, ?, 'module', 'Disabled Policy Test Module', false, 1, ?)
                    """,
                itemId,
                catalogVersion,
                OffsetDateTime.now()
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
        return itemId;
    }
}
