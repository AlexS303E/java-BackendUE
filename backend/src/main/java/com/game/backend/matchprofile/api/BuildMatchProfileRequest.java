package com.game.backend.matchprofile.api;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    @Min(1)
    int weaponPresetSlot,

    @Min(1)
    int outfitPresetSlot,

    @NotEmpty
    @Size(max = 10)
    List<@NotNull Long> supportedCatalogVersions,

    @Min(1)
    Long preferredCatalogVersion,

    @NotBlank
    String serverBuildId,

    @NotBlank
    String gameModeId
) {
    @AssertTrue(message = "supported_catalog_versions must not contain duplicates")
    public boolean isSupportedCatalogVersionsUnique() {
        if (supportedCatalogVersions == null) {
            return true;
        }
        Set<Long> versions = new HashSet<>();
        for (Long version : supportedCatalogVersions) {
            if (version != null && !versions.add(version)) {
                return false;
            }
        }
        return true;
    }
}
