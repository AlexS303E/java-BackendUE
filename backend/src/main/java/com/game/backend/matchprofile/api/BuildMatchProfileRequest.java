package com.game.backend.matchprofile.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Запрос Dedicated Server на сборку match profile игрока перед входом в матч.
 */
public record BuildMatchProfileRequest(
    @NotNull
    UUID matchId,

    @NotNull
    UUID playerId,

    @NotBlank
    String realmId,

    @NotBlank
    String classTag,

    @NotBlank
    String teamTag,

    int weaponPresetSlot,

    int outfitPresetSlot,

    @NotEmpty
    List<Long> supportedCatalogVersions,

    Long preferredCatalogVersion,

    @NotBlank
    String serverBuildId
) {
}
