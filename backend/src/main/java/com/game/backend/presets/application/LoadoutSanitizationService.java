package com.game.backend.presets.application;

import com.game.backend.presets.repository.PresetsRepository;
import com.game.backend.presets.repository.PresetsRepository.OutfitPresetKey;
import com.game.backend.presets.repository.PresetsRepository.WeaponPresetKey;

import com.game.backend.common.api.ApiException;
import com.game.backend.notifications.application.PlayerNotificationService;
import com.game.backend.outbox.application.OutboxService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Чистит сохраненные loadout presets, если предмет стал недоступен игроку.
 */
@Service
public class LoadoutSanitizationService {
    private final PresetsRepository repository;
    private final OutboxService outboxService;
    private final PlayerNotificationService playerNotificationService;

    public LoadoutSanitizationService(
        PresetsRepository repository,
        OutboxService outboxService,
        PlayerNotificationService playerNotificationService
    ) {
        this.repository = repository;
        this.outboxService = outboxService;
        this.playerNotificationService = playerNotificationService;
    }

    /**
     * Удаляет недоступный item из weapon/outfit presets и помечает затронутые presets как sanitized.
     */
    public LoadoutSanitizationResult sanitizeUnavailableItem(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        String itemType = itemType(itemId, catalogVersion);
        int weaponPresets = switch (itemType) {
            case "weapon" -> sanitizeWeapon(playerId, itemId, catalogVersion, source, sourceEventId, now);
            case "module" -> sanitizeModule(playerId, itemId, catalogVersion, source, sourceEventId, now);
            default -> 0;
        };
        int outfitPresets = "clothing".equals(itemType)
            ? sanitizeClothing(playerId, itemId, catalogVersion, source, sourceEventId, now)
            : 0;
        return new LoadoutSanitizationResult(weaponPresets, outfitPresets);
    }

    private String itemType(String itemId, long catalogVersion) {
        List<String> itemTypes = repository.findItemTypes(itemId, catalogVersion);
        if (itemTypes.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "CATALOG_ITEM_NOT_FOUND", "Catalog item was not found");
        }
        return itemTypes.getFirst();
    }

    private int sanitizeWeapon(
        UUID playerId,
        String weaponId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<WeaponPresetKey> presets = weaponPresetsUsingWeapon(playerId, weaponId, catalogVersion);
        for (WeaponPresetKey preset : presets) {
            repository.deleteWeaponConfigsForWeapon(
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                weaponId
            );
            repository.clearSelectedWeapon(
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                weaponId
            );
            long revision = markWeaponPresetSanitized(playerId, preset, now);
            recordWeaponPresetSanitized(playerId, preset, weaponId, "weapon", revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private int sanitizeModule(
        UUID playerId,
        String moduleId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<WeaponPresetKey> presets = weaponPresetsUsingModule(playerId, moduleId, catalogVersion);
        for (WeaponPresetKey preset : presets) {
            repository.bumpWeaponConfigRevisionForModuleRemoval(
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                moduleId,
                now
            );
            repository.deleteWeaponConfigModulesByModule(
                playerId,
                preset.classTag(),
                preset.presetSlot(),
                catalogVersion,
                moduleId
            );
            long revision = markWeaponPresetSanitized(playerId, preset, now);
            recordWeaponPresetSanitized(playerId, preset, moduleId, "module", revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private int sanitizeClothing(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        List<OutfitPresetKey> presets = outfitPresetsUsingItem(playerId, itemId, catalogVersion);
        for (OutfitPresetKey preset : presets) {
            repository.deleteOutfitPresetItem(
                playerId,
                preset.teamTag(),
                preset.classTag(),
                preset.outfitPresetSlot(),
                catalogVersion,
                itemId
            );
            long revision = markOutfitPresetSanitized(playerId, preset, now);
            recordOutfitPresetSanitized(playerId, preset, itemId, revision, source, sourceEventId, now);
        }
        return presets.size();
    }

    private List<WeaponPresetKey> weaponPresetsUsingWeapon(UUID playerId, String weaponId, long catalogVersion) {
        return repository.lockWeaponPresetsUsingWeapon(playerId, weaponId, catalogVersion);
    }

    private List<WeaponPresetKey> weaponPresetsUsingModule(UUID playerId, String moduleId, long catalogVersion) {
        return repository.lockWeaponPresetsUsingModule(playerId, moduleId, catalogVersion);
    }

    private List<OutfitPresetKey> outfitPresetsUsingItem(UUID playerId, String itemId, long catalogVersion) {
        return repository.lockOutfitPresetsUsingItem(playerId, itemId, catalogVersion);
    }

    private long markWeaponPresetSanitized(UUID playerId, WeaponPresetKey preset, OffsetDateTime now) {
        return repository.markWeaponPresetSanitized(playerId, preset, now);
    }

    private long markOutfitPresetSanitized(UUID playerId, OutfitPresetKey preset, OffsetDateTime now) {
        return repository.markOutfitPresetSanitized(playerId, preset, now);
    }

    private void recordWeaponPresetSanitized(
        UUID playerId,
        WeaponPresetKey preset,
        String removedItemId,
        String removedItemType,
        long revision,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", playerId,
            "class_tag", preset.classTag(),
            "preset_slot", preset.presetSlot(),
            "catalog_version", preset.catalogVersion(),
            "revision", revision,
            "removed_item_id", removedItemId,
            "removed_item_type", removedItemType,
            "source", source,
            "source_event_id", sourceEventId
        );
        outboxService.record(
            "weapon_preset.sanitized",
            "weapon_preset",
            weaponPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            playerId,
            "weapon_preset.sanitized",
            "weapon_preset",
            weaponPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
    }

    private void recordOutfitPresetSanitized(
        UUID playerId,
        OutfitPresetKey preset,
        String removedItemId,
        long revision,
        String source,
        UUID sourceEventId,
        OffsetDateTime now
    ) {
        Map<String, Object> payload = Map.of(
            "player_id", playerId,
            "team_tag", preset.teamTag(),
            "class_tag", preset.classTag(),
            "outfit_preset_slot", preset.outfitPresetSlot(),
            "catalog_version", preset.catalogVersion(),
            "revision", revision,
            "removed_item_id", removedItemId,
            "removed_item_type", "clothing",
            "source", source,
            "source_event_id", sourceEventId
        );
        outboxService.record(
            "outfit_preset.sanitized",
            "outfit_preset",
            outfitPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
        playerNotificationService.record(
            playerId,
            "outfit_preset.sanitized",
            "outfit_preset",
            outfitPresetAggregateId(playerId, preset),
            1,
            payload,
            now
        );
    }

    private String weaponPresetAggregateId(UUID playerId, WeaponPresetKey preset) {
        return playerId + ":" + preset.classTag() + ":" + preset.presetSlot() + ":" + preset.catalogVersion();
    }

    private String outfitPresetAggregateId(UUID playerId, OutfitPresetKey preset) {
        return playerId + ":" + preset.teamTag() + ":" + preset.classTag() + ":" + preset.outfitPresetSlot() + ":" + preset.catalogVersion();
    }
}
