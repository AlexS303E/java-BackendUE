package com.game.backend.catalog.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Admin-команда на публикацию validated/canary catalog_version как active для realm.
 */
public record CatalogPublishRequest(
    @NotBlank
    String realmId,

    @NotNull
    @Min(1)
    Long catalogVersion,

    @Min(0)
    @Max(100)
    Integer rolloutPercent,

    Boolean allowExistingMatches,

    @NotBlank
    String reason
) {
}
