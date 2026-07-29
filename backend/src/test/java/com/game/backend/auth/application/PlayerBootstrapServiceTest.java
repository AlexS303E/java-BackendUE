package com.game.backend.auth.application;

import com.game.backend.auth.repository.AuthRepository;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerBootstrapServiceTest {
    @Test
    void shouldBuildStarterPresetsFromActiveCatalogDefaults() {
        AuthRepository repository = mock(AuthRepository.class);
        when(repository.findActiveCatalogVersionsForBootstrap("global")).thenReturn(List.of(7L));
        when(repository.findWeaponBootstrapDefaults(7L)).thenReturn(List.of(
            new AuthRepository.WeaponBootstrapDefault(
                "class.engineer", 2, "primary", "weapon.m4", "weapon.m4.mount.scope", "module.scope"
            )
        ));
        when(repository.findOutfitBootstrapDefaults(7L)).thenReturn(List.of(
            new AuthRepository.OutfitBootstrapDefault("team.green", "class.engineer", 2, "torso", "clothing.green.torso"),
            new AuthRepository.OutfitBootstrapDefault("team.green", "class.engineer", 2, "head", "clothing.green.head")
        ));

        UUID playerId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now();
        new PlayerBootstrapService(repository).bootstrapNewPlayer(playerId, now);

        verify(repository).insertDefaultWeaponPreset(playerId, "class.engineer", 2, 7L, now);
        verify(repository).insertDefaultWeaponConfigModule(
            playerId, "class.engineer", 2, 7L, "primary", "weapon.m4", "weapon.m4.mount.scope", "module.scope"
        );
        verify(repository, times(1)).insertDefaultOutfitPreset(playerId, "team.green", "class.engineer", 2, 7L, now);
        verify(repository).insertDefaultOutfitPresetItem(
            playerId, "team.green", "class.engineer", 2, 7L, "torso", "clothing.green.torso"
        );
        verify(repository).insertDefaultOutfitPresetItem(
            playerId, "team.green", "class.engineer", 2, 7L, "head", "clothing.green.head"
        );
    }
}
