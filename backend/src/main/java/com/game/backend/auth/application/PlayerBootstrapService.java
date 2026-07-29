package com.game.backend.auth.application;

import com.game.backend.auth.repository.AuthRepository;
import com.game.backend.auth.repository.AuthRepository.OutfitBootstrapDefault;
import com.game.backend.auth.repository.AuthRepository.WeaponBootstrapDefault;

import com.game.backend.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Создает стартовые access/preset записи для нового игрока.
 */
@Service
public class PlayerBootstrapService {
    private static final String DEFAULT_REALM_ID = "global";

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
        List<WeaponBootstrapDefault> defaults = repository.findWeaponBootstrapDefaults(catalogVersion);
        if (defaults.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEFAULT_WEAPON_PRESET_NOT_CONFIGURED", "No weapon bootstrap defaults configured");
        }
        for (WeaponBootstrapDefault defaultPreset : defaults) {
            repository.insertDefaultWeaponPreset(playerId, defaultPreset.classTag(), defaultPreset.presetSlot(), catalogVersion, now);
            repository.insertDefaultWeaponPresetSlots(
                playerId,
                defaultPreset.classTag(),
                defaultPreset.presetSlot(),
                catalogVersion,
                defaultPreset.weaponSlotId(),
                defaultPreset.weaponId()
            );
            repository.insertDefaultWeaponConfig(
                playerId,
                defaultPreset.classTag(),
                defaultPreset.presetSlot(),
                catalogVersion,
                defaultPreset.weaponSlotId(),
                defaultPreset.weaponId(),
                now
            );
            if (defaultPreset.mountId() != null && defaultPreset.moduleId() != null) {
                repository.insertDefaultWeaponConfigModule(
                    playerId,
                    defaultPreset.classTag(),
                    defaultPreset.presetSlot(),
                    catalogVersion,
                    defaultPreset.weaponSlotId(),
                    defaultPreset.weaponId(),
                    defaultPreset.mountId(),
                    defaultPreset.moduleId()
                );
            }
        }
    }

    /**
     * Создает стартовые outfit presets для доступных команд.
     */
    private void bootstrapOutfitPresets(UUID playerId, long catalogVersion, OffsetDateTime now) {
        List<OutfitBootstrapDefault> defaults = repository.findOutfitBootstrapDefaults(catalogVersion);
        if (defaults.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DEFAULT_OUTFIT_NOT_CONFIGURED", "No outfit bootstrap defaults configured");
        }
        Set<String> createdPresets = new HashSet<>();
        for (OutfitBootstrapDefault defaultPreset : defaults) {
            String presetKey = defaultPreset.teamTag() + "\u0000" + defaultPreset.classTag() + "\u0000" + defaultPreset.presetSlot();
            if (createdPresets.add(presetKey)) {
                repository.insertDefaultOutfitPreset(
                    playerId,
                    defaultPreset.teamTag(),
                    defaultPreset.classTag(),
                    defaultPreset.presetSlot(),
                    catalogVersion,
                    now
                );
            }
            repository.insertDefaultOutfitPresetItem(
                playerId,
                defaultPreset.teamTag(),
                defaultPreset.classTag(),
                defaultPreset.presetSlot(),
                catalogVersion,
                defaultPreset.clothingSlotId(),
                defaultPreset.itemId()
            );
        }
    }
}
