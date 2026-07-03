package com.game.backend;

import com.game.backend.auth.application.AuthService;
import com.game.backend.matchprofile.application.MatchProfileBuildCommand;
import com.game.backend.matchprofile.application.MatchProfileWeapon;
import com.game.backend.matchprofile.application.MatchProfileSnapshotBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
class MatchProfileSnapshotBuilderIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;
    private static final String WEAPON_SLOT_ID = "primary";
    private static final String WEAPON_ID = "weapon.ak12";
    private static final String MODULE_ID = "module.scope.red_dot_01";

    @Autowired
    private AuthService authService;

    @Autowired
    private MatchProfileSnapshotBuilder snapshotBuilder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldBuildDefaultWeaponOutfitSnapshot() {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();

        MatchProfileSnapshotBuilder.Snapshot snapshot = snapshotBuilder.build(
                request(playerId, catalogVersion, "tdm"),
                catalogVersion
        );

        MatchProfileWeapon primary = findWeapon(snapshot, WEAPON_SLOT_ID);
        assertThat(primary.weaponId()).isEqualTo(WEAPON_ID);
        assertThat(primary.modules()).hasSize(1);
        assertThat(primary.modules().getFirst().moduleId()).isEqualTo(MODULE_ID);
        assertThat(snapshot.outfit()).isNotEmpty();
        assertThat(snapshot.warnings()).isEmpty();
    }

    @Test
    void shouldRemoveTeamRestrictedWeaponWhenGameModeEnforcesTeamRules() {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();
        String blueOnlyWeaponId = insertBlueOnlyWeapon(playerId, catalogVersion);
        selectPrimaryWeapon(playerId, catalogVersion, blueOnlyWeaponId);

        MatchProfileSnapshotBuilder.Snapshot snapshot = snapshotBuilder.build(
                request(playerId, catalogVersion, "asymmetric_factions"),
                catalogVersion
        );

        assertThat(snapshot.weapons())
                .extracting(MatchProfileWeapon::weaponId)
                .doesNotContain(blueOnlyWeaponId);
        assertThat(snapshot.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Weapon restricted for team"));
    }

    private UUID registerPlayer() {
        String loginName = "mp_builder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(loginName, "password123").playerId();
    }

    private MatchProfileBuildCommand request(UUID playerId, long catalogVersion, String gameModeId) {
        return new MatchProfileBuildCommand(
                UUID.randomUUID(),
                playerId,
                "global",
                CLASS_TAG,
                TEAM_RED,
                WEAPON_PRESET_SLOT,
                OUTFIT_PRESET_SLOT,
                List.of(catalogVersion),
                catalogVersion,
                "dev-server-build",
                gameModeId
        );
    }

    private MatchProfileWeapon findWeapon(MatchProfileSnapshotBuilder.Snapshot snapshot, String weaponSlotId) {
        return snapshot.weapons().stream()
                .filter(weapon -> weaponSlotId.equals(weapon.weaponSlotId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Weapon slot not found: " + weaponSlotId));
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

    private String insertBlueOnlyWeapon(UUID playerId, long catalogVersion) {
        String weaponId = "weapon.test.blue_only." + UUID.randomUUID();
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
                    VALUES (?, ?, 'weapon', 'Blue Only Test Weapon', true, 1, ?)
                    """,
                weaponId,
                catalogVersion,
                OffsetDateTime.now()
        );
        jdbcTemplate.update(
                "INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag) VALUES (?, ?, ?, 'specific', 'team.blue')",
                UUID.randomUUID(),
                weaponId,
                catalogVersion
        );
        jdbcTemplate.update(
                "INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect) VALUES (?, ?, ?, 'allow')",
                weaponId,
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
                weaponId,
                catalogVersion,
                OffsetDateTime.now()
        );
        return weaponId;
    }

    private void selectPrimaryWeapon(UUID playerId, long catalogVersion, String weaponId) {
        jdbcTemplate.update(
                """
                    UPDATE player_weapon_preset_slots
                    SET selected_weapon_id = ?
                    WHERE player_id = ?
                      AND class_tag = ?
                      AND preset_slot = ?
                      AND catalog_version = ?
                      AND weapon_slot_id = ?
                    """,
                weaponId,
                playerId,
                CLASS_TAG,
                WEAPON_PRESET_SLOT,
                catalogVersion,
                WEAPON_SLOT_ID
        );
    }
}
