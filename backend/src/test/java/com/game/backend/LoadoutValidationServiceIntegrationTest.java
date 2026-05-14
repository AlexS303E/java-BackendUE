package com.game.backend;

import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.application.AuthService;
import com.game.backend.common.api.ApiException;
import com.game.backend.presets.application.LoadoutValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
class LoadoutValidationServiceIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String OTHER_WEAPON_ID = "weapon.m4";
    private static final String MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String MODULE_ID = "module.scope.red_dot_01";

    @Autowired
    private AuthService authService;

    @Autowired
    private LoadoutValidationService loadoutValidationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldValidatePresetSaveAndRuntimeChangeScenarios() {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);

        loadoutValidationService.validateForPresetSave(
                playerId,
                CLASS_TAG,
                catalogVersion,
                List.of(new LoadoutValidationService.WeaponSlotSelection(
                        WEAPON_SLOT_ID,
                        WEAPON_ID,
                        List.of(new LoadoutValidationService.ModuleSelection(MOUNT_ID, MODULE_ID))
                ))
        );

        assertThatThrownBy(() -> loadoutValidationService.validateForPresetSave(
                playerId,
                CLASS_TAG,
                catalogVersion,
                List.of(new LoadoutValidationService.WeaponSlotSelection(
                        "grenade",
                        null,
                        List.of(new LoadoutValidationService.ModuleSelection(MOUNT_ID, MODULE_ID))
                ))
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("LOADOUT_VALIDATION_FAILED"));

        setPlayerItemAccessFlags(playerId, MODULE_ID, catalogVersion, false, false, false, true);

        assertThatThrownBy(() -> loadoutValidationService.validateForRuntimeSetModule(
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                WEAPON_ID,
                MOUNT_ID,
                MODULE_ID
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("LOADOUT_VALIDATION_FAILED"));

        assertThatThrownBy(() -> loadoutValidationService.validateForRuntimeClearModule(
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                OTHER_WEAPON_ID
        ))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("LOADOUT_VALIDATION_FAILED"));

        setPlayerItemAccessFlags(playerId, MODULE_ID, catalogVersion, false, false, false, false);
        loadoutValidationService.validateForRuntimeSetModule(
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID,
                WEAPON_ID,
                MOUNT_ID,
                MODULE_ID
        );

        assertThat(weaponPresetRevision(playerId)).isEqualTo(1);
    }

    private UUID registerPlayer() {
        String loginName = "loadout_validation_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
}
