package com.game.backend;

import com.game.backend.catalog.application.CatalogService;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.application.CatalogVersionSelector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogVersionSelectorTest {
    @Test
    void shouldPreferRequestedVersionWhenItAllowsNewMatches() {
        CatalogService catalogService = mock(CatalogService.class);
        when(catalogService.catalogVersionAllowsNewMatches("global", 10L)).thenReturn(true);
        when(catalogService.catalogVersionAllowsNewMatches("global", 11L)).thenReturn(true);

        CatalogVersionSelector selector = new CatalogVersionSelector(catalogService);

        assertThat(selector.select(request(List.of(10L, 11L), 10L))).isEqualTo(10L);
    }

    @Test
    void shouldUseNewestSupportedVersionWhenPreferredVersionIsAbsent() {
        CatalogService catalogService = mock(CatalogService.class);
        when(catalogService.catalogVersionAllowsNewMatches("global", 10L)).thenReturn(true);
        when(catalogService.catalogVersionAllowsNewMatches("global", 11L)).thenReturn(true);

        CatalogVersionSelector selector = new CatalogVersionSelector(catalogService);

        assertThat(selector.select(request(List.of(10L, 11L), null))).isEqualTo(11L);
    }

    @Test
    void shouldRejectDuplicateSupportedCatalogVersions() {
        CatalogService catalogService = mock(CatalogService.class);
        CatalogVersionSelector selector = new CatalogVersionSelector(catalogService);

        assertThatThrownBy(() -> selector.select(request(List.of(10L, 10L), 10L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("DUPLICATE_CATALOG_VERSIONS"));
    }

    @Test
    void shouldRejectUnsupportedCatalogVersions() {
        CatalogService catalogService = mock(CatalogService.class);
        when(catalogService.catalogVersionAllowsNewMatches("global", 10L)).thenReturn(false);
        CatalogVersionSelector selector = new CatalogVersionSelector(catalogService);

        assertThatThrownBy(() -> selector.select(request(List.of(10L), 10L)))
                .isInstanceOf(ApiException.class)
                .satisfies(exception -> assertThat(((ApiException) exception).code())
                        .isEqualTo("CATALOG_VERSION_NOT_SUPPORTED"));
    }

    private BuildMatchProfileRequest request(List<Long> supportedCatalogVersions, Long preferredCatalogVersion) {
        return new BuildMatchProfileRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "global",
                "class.assault",
                "team.red",
                1,
                1,
                supportedCatalogVersions,
                preferredCatalogVersion,
                "dev-server-build",
                "tdm"
        );
    }
}
