package com.game.backend;

import com.game.backend.auth.api.RegisterRequest;
import com.game.backend.auth.application.AuthService;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.application.MatchProfileDependencyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
class MatchProfileDependencyServiceIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;

    @Autowired
    private AuthService authService;

    @Autowired
    private MatchProfileDependencyService dependencyService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldLoadWeaponOutfitAndAccessDependencyTuple() {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();

        MatchProfileDependencyService.DependencyTuple tuple = dependencyService.load(
                request(playerId, catalogVersion, OUTFIT_PRESET_SLOT),
                catalogVersion
        );

        assertThat(tuple.weaponPresetRevision()).isEqualTo(1);
        assertThat(tuple.outfitPresetRevision()).isEqualTo(1);
        assertThat(tuple.accessRevision()).isEqualTo(accessRevision(playerId));
        assertThat(tuple.weaponPresetSanitized()).isFalse();
        assertThat(tuple.outfitPresetSanitized()).isFalse();
    }

    @Test
    void shouldRejectMissingOutfitPreset() {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();

        assertThatThrownBy(() -> dependencyService.load(request(playerId, catalogVersion, 99), catalogVersion))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("OUTFIT_PRESET_NOT_FOUND"));
    }

    private UUID registerPlayer() {
        String loginName = "mp_deps_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(new RegisterRequest(loginName, "password123")).playerId();
    }

    private BuildMatchProfileRequest request(UUID playerId, long catalogVersion, int outfitPresetSlot) {
        return new BuildMatchProfileRequest(
                UUID.randomUUID(),
                playerId,
                "global",
                CLASS_TAG,
                TEAM_RED,
                WEAPON_PRESET_SLOT,
                outfitPresetSlot,
                List.of(catalogVersion),
                catalogVersion,
                "dev-server-build",
                "tdm"
        );
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

    private long accessRevision(UUID playerId) {
        return jdbcTemplate.queryForObject(
                "SELECT access_revision FROM player_access_projection_state WHERE player_id = ?",
                Long.class,
                playerId
        );
    }
}
