package com.game.backend.matchprofile.application;

import com.game.backend.catalog.application.CatalogService;
import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class CatalogVersionSelector {
    private final CatalogService catalogService;

    public CatalogVersionSelector(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public long select(MatchProfileBuildCommand command) {
        List<Long> versions = command.supportedCatalogVersions();
        if (versions.size() != versions.stream().distinct().count()) {
            throw new ApiException(
                HttpStatus.BAD_REQUEST,
                "DUPLICATE_CATALOG_VERSIONS",
                "supported_catalog_versions must not contain duplicates"
            );
        }
        return versions.stream()
            .filter(version -> catalogService.catalogVersionAllowsNewMatches(command.realmId(), version))
            .sorted(preferredFirst(command.preferredCatalogVersion()))
            .findFirst()
            .orElseThrow(() -> new ApiException(
                HttpStatus.CONFLICT,
                "CATALOG_VERSION_NOT_SUPPORTED",
                "Dedicated Server does not support an active catalog version for realm " + command.realmId()
            ));
    }

    private Comparator<Long> preferredFirst(Long preferredCatalogVersion) {
        return (left, right) -> {
            if (preferredCatalogVersion == null) {
                return Long.compare(right, left);
            }
            if (left.equals(preferredCatalogVersion)) {
                return -1;
            }
            if (right.equals(preferredCatalogVersion)) {
                return 1;
            }
            return Long.compare(right, left);
        };
    }
}
