package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.matchprofile.application.MatchProfileBuildCommand;
import com.game.backend.matchprofile.application.MatchProfileDependencyRevisions;
import com.game.backend.matchprofile.application.MatchProfileSnapshot;
import com.game.backend.matchprofile.application.MatchProfileCacheService;
import com.game.backend.matchprofile.repository.MatchProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MatchProfileCacheServiceTest {
    private static final String REALM_ID = "global";
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_TAG = "team.red";
    private static final int WEAPON_PRESET_SLOT = 1;
    private static final int OUTFIT_PRESET_SLOT = 1;
    private static final long CATALOG_VERSION = 7;
    private static final long WEAPON_REVISION = 11;
    private static final long OUTFIT_REVISION = 13;
    private static final long ACCESS_REVISION = 17;

    private final MatchProfileRepository repository = mock(MatchProfileRepository.class);
    private final RedisCacheService cacheService = mock(RedisCacheService.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final MatchProfileCacheService service = new MatchProfileCacheService(repository, objectMapper, cacheService);

    @Test
    void shouldIgnoreCachedProfileWhenPayloadDoesNotMatchDependencyTuple() {
        MatchProfileBuildCommand request = request();
        MatchProfileSnapshot mismatched = response(request, WEAPON_REVISION + 1, OUTFIT_REVISION, ACCESS_REVISION);
        when(cacheService.getMatchProfile(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).thenReturn(Optional.of(mismatched));
        when(repository.findFreshPayload(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).thenReturn(List.of());

        assertThat(service.findByDependencyTuple(
            request,
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).isNull();

        verify(repository).findFreshPayload(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        );
    }

    @Test
    void shouldIgnoreDatabaseProfileWhenPayloadDoesNotMatchDependencyTuple() throws Exception {
        MatchProfileBuildCommand request = request();
        MatchProfileSnapshot mismatched = response(request, WEAPON_REVISION, OUTFIT_REVISION + 1, ACCESS_REVISION);
        when(cacheService.getMatchProfile(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).thenReturn(Optional.empty());
        when(repository.findFreshPayload(
            request.playerId(),
            request.realmId(),
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).thenReturn(List.of(objectMapper.writeValueAsString(mismatched)));

        assertThat(service.findByDependencyTuple(
            request,
            CATALOG_VERSION,
            WEAPON_REVISION,
            OUTFIT_REVISION,
            ACCESS_REVISION
        )).isNull();
    }

    private MatchProfileBuildCommand request() {
        return new MatchProfileBuildCommand(
            UUID.randomUUID(),
            UUID.randomUUID(),
            REALM_ID,
            CLASS_TAG,
            TEAM_TAG,
            WEAPON_PRESET_SLOT,
            OUTFIT_PRESET_SLOT,
            List.of(CATALOG_VERSION),
            CATALOG_VERSION,
            "dev-server-build",
            "tdm"
        );
    }

    private MatchProfileSnapshot response(
        MatchProfileBuildCommand request,
        long weaponPresetRevision,
        long outfitPresetRevision,
        long accessRevision
    ) {
        return new MatchProfileSnapshot(
            1,
            request.playerId(),
            request.realmId(),
            CATALOG_VERSION,
            request.classTag(),
            request.teamTag(),
            request.weaponPresetSlot(),
            request.outfitPresetSlot(),
            List.of(),
            List.of(),
            List.of(),
            new MatchProfileDependencyRevisions(weaponPresetRevision, outfitPresetRevision, accessRevision, 19)
        );
    }
}
