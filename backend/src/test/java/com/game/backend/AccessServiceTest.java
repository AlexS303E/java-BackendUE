package com.game.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.backend.access.api.AccessResponse;
import com.game.backend.access.application.AccessService;
import com.game.backend.access.application.ItemAccessPolicy;
import com.game.backend.access.repository.AccessRepository;
import com.game.backend.cache.RedisCacheService;
import com.game.backend.catalog.application.CatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccessServiceTest {
    private static final UUID PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000222");
    private static final long CATALOG_VERSION = 11L;
    private static final long ACCESS_REVISION = 3L;

    private final AccessRepository repository = mock(AccessRepository.class);
    private final CatalogService catalogService = mock(CatalogService.class);
    private final RedisCacheService cacheService = mock(RedisCacheService.class);
    private final ItemAccessPolicy itemAccessPolicy = mock(ItemAccessPolicy.class);
    private final AccessService service = new AccessService(
        repository,
        catalogService,
        new ObjectMapper(),
        cacheService,
        itemAccessPolicy
    );

    @Test
    void shouldMarkDisabledCatalogItemAsNotUsableInAccessSnapshot() {
        AccessRepository.AccessItemRow disabledCatalogItem = new AccessRepository.AccessItemRow(
            "weapon.disabled",
            "weapon",
            "Disabled Weapon",
            false,
            false,
            false,
            false,
            false,
            null,
            null,
            null
        );
        when(repository.findAccessRevision(PLAYER_ID)).thenReturn(List.of(ACCESS_REVISION));
        when(cacheService.getAccess(PLAYER_ID, CATALOG_VERSION, ACCESS_REVISION)).thenReturn(Optional.empty());
        when(repository.findAccessItems(PLAYER_ID, CATALOG_VERSION)).thenReturn(List.of(disabledCatalogItem));
        when(itemAccessPolicy.canUseForUi(false, false, false, false, false)).thenReturn(false);

        AccessResponse response = service.getAccess(PLAYER_ID, "global", CATALOG_VERSION);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().itemId()).isEqualTo("weapon.disabled");
        assertThat(response.items().getFirst().playerCanUse()).isFalse();
        verify(itemAccessPolicy).canUseForUi(false, false, false, false, false);
        verify(cacheService).putAccess(response);
    }
}
