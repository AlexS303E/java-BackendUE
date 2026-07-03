package com.game.backend;

import com.game.backend.auth.application.AuthService;
import com.game.backend.matchprofile.api.DependencyRevisionsDto;
import com.game.backend.matchprofile.api.MatchProfileResponse;
import com.game.backend.matchprofile.application.MatchProfileBuildCommand;
import com.game.backend.matchprofile.application.MatchProfileCacheService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

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
class MatchProfileCacheServiceIntegrationTest {
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;

    @Autowired
    private AuthService authService;

    @Autowired
    private MatchProfileCacheService cacheService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldSaveAndFindFreshProfileByDependencyTuple() {
        UUID playerId = registerPlayer();
        long catalogVersion = activeCatalogVersion();
        MatchProfileBuildCommand request = request(playerId, catalogVersion);
        MatchProfileResponse response = response(request, catalogVersion, 1, 1, accessRevision(playerId), 12345);

        assertThat(cacheService.findByDependencyTuple(request, catalogVersion, 1, 1, accessRevision(playerId))).isNull();

        cacheService.save(request, response);

        MatchProfileResponse cached = cacheService.findByDependencyTuple(request, catalogVersion, 1, 1, accessRevision(playerId));
        assertThat(cached).isNotNull();
        assertThat(cached.playerId()).isEqualTo(playerId);
        assertThat(cached.catalogVersion()).isEqualTo(catalogVersion);
        assertThat(cached.dependencyRevisions().profileRevision()).isEqualTo(12345);

        assertThat(cacheService.findByDependencyTuple(request, catalogVersion, 2, 1, accessRevision(playerId))).isNull();
    }

    private UUID registerPlayer() {
        String loginName = "mp_cache_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return authService.register(loginName, "password123").playerId();
    }

    private MatchProfileBuildCommand request(UUID playerId, long catalogVersion) {
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
                devServerBuildId(),
                "tdm"
        );
    }

    private MatchProfileResponse response(
            MatchProfileBuildCommand request,
            long catalogVersion,
            long weaponPresetRevision,
            long outfitPresetRevision,
            long accessRevision,
            long profileRevision
    ) {
        return new MatchProfileResponse(
                1,
                request.playerId(),
                request.realmId(),
                catalogVersion,
                request.classTag(),
                request.teamTag(),
                request.weaponPresetSlot(),
                request.outfitPresetSlot(),
                List.of(),
                List.of(),
                List.of(),
                new DependencyRevisionsDto(weaponPresetRevision, outfitPresetRevision, accessRevision, profileRevision)
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

    private String devServerBuildId() {
        return jdbcTemplate.queryForObject(
                "SELECT server_build_id FROM server_identities WHERE server_id = ?",
                String.class,
                UUID.fromString("10000000-0000-0000-0000-000000000001")
        );
    }
}
