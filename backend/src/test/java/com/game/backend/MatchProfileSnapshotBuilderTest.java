package com.game.backend;

import com.game.backend.access.application.ItemAccessPolicy;
import com.game.backend.catalog.application.CatalogValidationData;
import com.game.backend.matchprofile.api.MatchWeaponDto;
import com.game.backend.matchprofile.application.MatchProfileBuildCommand;
import com.game.backend.matchprofile.application.MatchProfileSnapshotBuilder;
import com.game.backend.matchprofile.repository.MatchProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MatchProfileSnapshotBuilderTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private static final long CATALOG_VERSION = 7L;
    private static final String CLASS_TAG = "class.assault";
    private static final String TEAM_RED = "team.red";
    private static final String BLUE_ONLY_WEAPON = "weapon.blue.only";
    private static final String WEAPON_SLOT_ID = "primary";

    private final MatchProfileRepository repository = mock(MatchProfileRepository.class);
    private final CatalogValidationData catalogValidationData = mock(CatalogValidationData.class);
    private final ItemAccessPolicy itemAccessPolicy = mock(ItemAccessPolicy.class);
    private final MatchProfileSnapshotBuilder builder = new MatchProfileSnapshotBuilder(
        repository,
        catalogValidationData,
        itemAccessPolicy
    );

    @Test
    void shouldKeepTeamRestrictedWeaponWhenGameModeDoesNotEnforceWeaponTeamRules() {
        givenSelectedWeapon();
        when(repository.enforceTeamItemRules("tdm")).thenReturn(false);
        when(repository.findTeamCompliantItems(CATALOG_VERSION, Set.of(BLUE_ONLY_WEAPON), TEAM_RED))
            .thenReturn(Set.of());

        MatchProfileSnapshotBuilder.Snapshot snapshot = builder.build(request("tdm"), CATALOG_VERSION);

        assertThat(snapshot.weapons())
            .extracting(MatchWeaponDto::weaponId)
            .containsExactly(BLUE_ONLY_WEAPON);
        assertThat(snapshot.warnings()).isEmpty();
    }

    @Test
    void shouldRemoveTeamRestrictedWeaponWhenGameModeEnforcesWeaponTeamRules() {
        givenSelectedWeapon();
        when(repository.enforceTeamItemRules("asymmetric_factions")).thenReturn(true);
        when(repository.findTeamCompliantItems(CATALOG_VERSION, Set.of(BLUE_ONLY_WEAPON), TEAM_RED))
            .thenReturn(Set.of());

        MatchProfileSnapshotBuilder.Snapshot snapshot = builder.build(request("asymmetric_factions"), CATALOG_VERSION);

        assertThat(snapshot.weapons()).isEmpty();
        assertThat(snapshot.warnings())
            .containsExactly("Weapon restricted for team in this game mode, removed: " + BLUE_ONLY_WEAPON);
    }

    @Test
    void shouldAlwaysRemoveOutfitItemRestrictedForTeam() {
        String blueOnlyJacket = "clothing.blue.only";
        when(repository.findWeaponRows(PLAYER_ID, CLASS_TAG, 1, CATALOG_VERSION)).thenReturn(List.of());
        when(repository.findOutfitRows(PLAYER_ID, TEAM_RED, CLASS_TAG, 1, CATALOG_VERSION))
            .thenReturn(List.of(new MatchProfileRepository.OutfitRow("torso", blueOnlyJacket)));
        when(itemAccessPolicy.usableItemsForMatchProfile(
            eq(PLAYER_ID),
            eq(CATALOG_VERSION),
            eq(CLASS_TAG),
            anySet()
        )).thenReturn(Set.of(blueOnlyJacket));
        when(repository.findTeamCompliantItems(CATALOG_VERSION, Set.of(blueOnlyJacket), TEAM_RED))
            .thenReturn(Set.of(blueOnlyJacket));
        when(repository.findOutfitTeamCompliantItems(CATALOG_VERSION, Set.of(blueOnlyJacket), TEAM_RED))
            .thenReturn(Set.of());
        when(catalogValidationData.getActiveClothingSlots()).thenReturn(Set.of("torso"));

        MatchProfileSnapshotBuilder.Snapshot snapshot = builder.build(request("tdm"), CATALOG_VERSION);

        assertThat(snapshot.outfit()).isEmpty();
        assertThat(snapshot.warnings())
            .containsExactly("Clothing restricted for team, removed: " + blueOnlyJacket);
    }

    private void givenSelectedWeapon() {
        when(repository.findWeaponRows(PLAYER_ID, CLASS_TAG, 1, CATALOG_VERSION))
            .thenReturn(List.of(new MatchProfileRepository.WeaponRow(WEAPON_SLOT_ID, BLUE_ONLY_WEAPON, null, null)));
        when(repository.findOutfitRows(PLAYER_ID, TEAM_RED, CLASS_TAG, 1, CATALOG_VERSION)).thenReturn(List.of());
        when(itemAccessPolicy.usableItemsForMatchProfile(
            eq(PLAYER_ID),
            eq(CATALOG_VERSION),
            eq(CLASS_TAG),
            anySet()
        )).thenReturn(Set.of(BLUE_ONLY_WEAPON));
        when(repository.findOutfitTeamCompliantItems(CATALOG_VERSION, Set.of(), TEAM_RED)).thenReturn(Set.of());
        when(catalogValidationData.getWeaponSlotRules(CLASS_TAG)).thenReturn(Map.of(WEAPON_SLOT_ID, true));
        when(catalogValidationData.getMountAllowedModules(CATALOG_VERSION)).thenReturn(Map.of());
    }

    private MatchProfileBuildCommand request(String gameModeId) {
        return new MatchProfileBuildCommand(
            UUID.randomUUID(),
            PLAYER_ID,
            "global",
            CLASS_TAG,
            TEAM_RED,
            1,
            1,
            List.of(CATALOG_VERSION),
            CATALOG_VERSION,
            "test-server-build",
            gameModeId
        );
    }
}
