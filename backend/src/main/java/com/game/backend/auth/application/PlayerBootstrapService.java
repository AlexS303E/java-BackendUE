package com.game.backend.auth.application;

import com.game.backend.auth.repository.AuthRepository;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Создает стартовые access/preset записи для нового игрока.
 */
@Service
public class PlayerBootstrapService {
    private static final String DEFAULT_REALM_ID = "global";
    private static final String DEFAULT_CLASS_TAG = "class.assault";
    private static final int DEFAULT_PRESET_SLOT = 1;
    private static final int DEFAULT_OUTFIT_PRESET_SLOT = 1;
    private static final String DEFAULT_WEAPON_SLOT_ID = "primary";
    private static final String DEFAULT_WEAPON_ID = "weapon.ak12";
    private static final String DEFAULT_MOUNT_ID = "weapon.ak12.mount.scope.01";
    private static final String DEFAULT_MODULE_ID = "module.scope.red_dot_01";

    private final AuthRepository repository;

    public PlayerBootstrapService(AuthRepository repository) {
        this.repository = repository;
    }

    /**
     * Заполняет минимальный набор данных, чтобы игрок сразу прошел smoke-путь loadout -> match profile.
     */
    public void bootstrapNewPlayer(UUID playerId, OffsetDateTime now) {
        long catalogVersion = activeCatalogVersion();
        bootstrapAccess(playerId, catalogVersion, now);
        bootstrapWeaponPreset(playerId, catalogVersion, now);
        bootstrapOutfitPresets(playerId, catalogVersion, now);
    }

    private long activeCatalogVersion() {
        List<Long> versions = repository.findActiveCatalogVersionsForBootstrap(DEFAULT_REALM_ID);
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ACTIVE_CATALOG_NOT_FOUND", "No active catalog deployment found");
        }
        return versions.getFirst();
    }

    /**
     * Открывает игроку все enabled items активного MVP-каталога и фиксирует это в ledger.
     */
    private void bootstrapAccess(UUID playerId, long catalogVersion, OffsetDateTime now) {
        repository.insertAccessProjectionState(playerId, now);

        repository.insertBootstrapEntitlementLedgerEvents(playerId, catalogVersion, now);

        repository.insertEnabledCatalogAccess(playerId, catalogVersion, now);
    }

    /**
     * Создает дефолтный weapon preset для class.assault.
     */
    private void bootstrapWeaponPreset(UUID playerId, long catalogVersion, OffsetDateTime now) {
        repository.insertDefaultWeaponPreset(playerId, DEFAULT_CLASS_TAG, DEFAULT_PRESET_SLOT, catalogVersion, now);

        repository.insertDefaultWeaponPresetSlots(
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID
        );

        repository.insertDefaultWeaponConfig(
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID,
            now
        );

        repository.insertDefaultWeaponConfigModule(
            playerId,
            DEFAULT_CLASS_TAG,
            DEFAULT_PRESET_SLOT,
            catalogVersion,
            DEFAULT_WEAPON_SLOT_ID,
            DEFAULT_WEAPON_ID,
            DEFAULT_MOUNT_ID,
            DEFAULT_MODULE_ID
        );
    }

    /**
     * Создает стартовые outfit presets для доступных команд.
     */
    private void bootstrapOutfitPresets(UUID playerId, long catalogVersion, OffsetDateTime now) {
        List<String> teamTags = repository.findOutfitPresetTeamTags(DEFAULT_CLASS_TAG);

        for (String teamTag : teamTags) {
            repository.insertDefaultOutfitPreset(
                playerId,
                teamTag,
                DEFAULT_CLASS_TAG,
                DEFAULT_OUTFIT_PRESET_SLOT,
                catalogVersion,
                now
            );

            repository.insertDefaultOutfitPresetItem(
                playerId,
                teamTag,
                DEFAULT_CLASS_TAG,
                DEFAULT_OUTFIT_PRESET_SLOT,
                catalogVersion,
                defaultJacketForTeam(teamTag)
            );
        }
    }

    private String defaultJacketForTeam(String teamTag) {
        return switch (teamTag) {
            case "team.red" -> "clothing.team_red.jacket_01";
            case "team.blue" -> "clothing.team_blue.jacket_01";
            default -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEFAULT_OUTFIT_NOT_CONFIGURED", "No default outfit configured for " + teamTag);
        };
    }
}
