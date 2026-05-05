package com.game.backend.catalog.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Admin-команда на rollback активного каталога к указанной или последней previous версии.
 */
public record CatalogRollbackRequest(
    @NotBlank
    String realmId,

    Long targetCatalogVersion,

    @NotBlank
    String reason
) {
}
