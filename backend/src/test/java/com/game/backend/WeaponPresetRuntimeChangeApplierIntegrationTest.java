package com.game.backend;

import com.game.backend.auth.application.AuthService;
import com.game.backend.runtimechanges.api.RuntimePresetChangePayload;
import com.game.backend.runtimechanges.api.RuntimePresetChangeStep;
import com.game.backend.runtimechanges.application.WeaponPresetRuntimeChangeApplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
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
@Transactional
class WeaponPresetRuntimeChangeApplierIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String MODULE_ID = "module.scope.red_dot_01";

    @Autowired
    private WeaponPresetRuntimeChangeApplier applier;

    @Autowired
    private AuthService authService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldApplyAllRuntimeWeaponPresetOperations() {
        UUID playerId = registerPlayer();
        long catalogVersion = weaponPresetCatalogVersion(playerId);
        OffsetDateTime now = OffsetDateTime.now();

        applier.apply(playerId, CLASS_TAG, WEAPON_PRESET_SLOT, catalogVersion, payload(
                new RuntimePresetChangeStep("clear_module", WEAPON_SLOT_ID, WEAPON_ID, MOUNT_ID, null)
        ), now);
        assertThat(moduleCount(playerId, catalogVersion)).isZero();

        applier.apply(playerId, CLASS_TAG, WEAPON_PRESET_SLOT, catalogVersion, payload(
                new RuntimePresetChangeStep("set_module", WEAPON_SLOT_ID, WEAPON_ID, MOUNT_ID, MODULE_ID)
        ), now);
        assertThat(moduleCount(playerId, catalogVersion)).isEqualTo(1);

        applier.apply(playerId, CLASS_TAG, WEAPON_PRESET_SLOT, catalogVersion, payload(
                new RuntimePresetChangeStep("clear_weapon", WEAPON_SLOT_ID, null, null, null)
        ), now);
        assertThat(selectedWeaponId(playerId, catalogVersion)).isNull();

        applier.apply(playerId, CLASS_TAG, WEAPON_PRESET_SLOT, catalogVersion, payload(
                new RuntimePresetChangeStep("set_weapon", WEAPON_SLOT_ID, WEAPON_ID, null, null)
        ), now);
        assertThat(selectedWeaponId(playerId, catalogVersion)).isEqualTo(WEAPON_ID);
        assertThat(weaponConfigCount(playerId, catalogVersion)).isEqualTo(1);
    }

    private UUID registerPlayer() {
        String loginName = "runtime_applier_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(loginName, "password123").playerId();
    }

    private RuntimePresetChangePayload payload(RuntimePresetChangeStep step) {
        return new RuntimePresetChangePayload(1, List.of(step));
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

    private String selectedWeaponId(UUID playerId, long catalogVersion) {
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

    private int weaponConfigCount(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForObject(
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
                WEAPON_ID
        );
    }

    private int moduleCount(UUID playerId, long catalogVersion) {
        return jdbcTemplate.queryForObject(
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
                MODULE_ID
        );
    }
}
