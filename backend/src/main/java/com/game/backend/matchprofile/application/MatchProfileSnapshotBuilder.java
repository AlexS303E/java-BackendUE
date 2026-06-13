package com.game.backend.matchprofile.application;

import com.game.backend.matchprofile.repository.MatchProfileRepository;

import com.game.backend.access.application.ItemAccessPolicy;
import com.game.backend.catalog.application.CatalogValidationData;
import com.game.backend.common.api.ApiException;
import com.game.backend.matchprofile.api.BuildMatchProfileRequest;
import com.game.backend.matchprofile.api.MatchModuleDto;
import com.game.backend.matchprofile.api.MatchOutfitItemDto;
import com.game.backend.matchprofile.api.MatchWeaponDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class MatchProfileSnapshotBuilder {
    private final MatchProfileRepository repository;
    private final CatalogValidationData catalogValidationData;
    private final ItemAccessPolicy itemAccessPolicy;

    public MatchProfileSnapshotBuilder(
        MatchProfileRepository repository,
        CatalogValidationData catalogValidationData,
        ItemAccessPolicy itemAccessPolicy
    ) {
        this.repository = repository;
        this.catalogValidationData = catalogValidationData;
        this.itemAccessPolicy = itemAccessPolicy;
    }

    public Snapshot build(BuildMatchProfileRequest request, long catalogVersion) {
        boolean enforceTeamItemRules = repository.enforceTeamItemRules(request.gameModeId());
        List<MatchWeaponDto> weapons = weapons(request, catalogVersion);
        List<MatchOutfitItemDto> outfit = outfit(request, catalogVersion);
        List<String> warnings = new ArrayList<>();
        validateLoadout(request, catalogVersion, weapons, outfit, enforceTeamItemRules, warnings);
        return new Snapshot(weapons, outfit, warnings);
    }

    private List<MatchWeaponDto> weapons(BuildMatchProfileRequest request, long catalogVersion) {
        List<MatchProfileRepository.WeaponRow> rows = repository.findWeaponRows(
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion
        );

        Map<String, MatchWeaponDto> weaponMap = new HashMap<>();
        for (MatchProfileRepository.WeaponRow row : rows) {
            String slotId = row.weaponSlotId();
            String weaponId = row.weaponId();
            String mountId = row.mountId();
            String moduleId = row.moduleId();

            MatchWeaponDto weapon = weaponMap.get(slotId);
            if (weapon == null) {
                weapon = new MatchWeaponDto(slotId, weaponId, new ArrayList<>());
                weaponMap.put(slotId, weapon);
            }
            if (mountId != null && moduleId != null) {
                weapon.modules().add(new MatchModuleDto(mountId, moduleId));
            }
        }

        return new ArrayList<>(weaponMap.values());
    }

    private List<MatchOutfitItemDto> outfit(BuildMatchProfileRequest request, long catalogVersion) {
        return new ArrayList<>(repository.findOutfitRows(
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        ).stream()
            .map(row -> new MatchOutfitItemDto(row.clothingSlotId(), row.itemId()))
            .toList());
    }

    private void validateLoadout(
        BuildMatchProfileRequest request,
        long catalogVersion,
        List<MatchWeaponDto> weapons,
        List<MatchOutfitItemDto> outfit,
        boolean enforceTeamItemRules,
        List<String> warnings
    ) {
        Set<String> weaponSlotIds = new HashSet<>();
        Set<String> itemIds = new HashSet<>();
        Set<String> clothingItemIds = new HashSet<>();
        Set<String> clothingSlotIds = new HashSet<>();

        for (MatchWeaponDto weapon : weapons) {
            weaponSlotIds.add(weapon.weaponSlotId());
            if (weapon.weaponId() == null) continue;
            itemIds.add(weapon.weaponId());
            for (MatchModuleDto module : weapon.modules()) {
                itemIds.add(module.moduleId());
            }
        }

        for (MatchOutfitItemDto item : outfit) {
            clothingSlotIds.add(item.clothingSlotId());
            itemIds.add(item.itemId());
            clothingItemIds.add(item.itemId());
        }

        validateWeaponSlotsAllowedBatch(request.classTag(), weaponSlotIds);
        Set<String> baseUsableItems = itemAccessPolicy.usableItemsForMatchProfile(
            request.playerId(),
            catalogVersion,
            request.classTag(),
            itemIds
        );
        Set<String> teamUsableItems = repository.findTeamCompliantItems(catalogVersion, itemIds, request.teamTag());
        Set<String> outfitTeamUsableItems = repository.findOutfitTeamCompliantItems(catalogVersion, clothingItemIds, request.teamTag());
        filterRestrictedItems(weapons, outfit, baseUsableItems, teamUsableItems, outfitTeamUsableItems, enforceTeamItemRules, warnings);
        List<ModuleMountPair> filteredPairs = collectModuleMountPairs(weapons);
        validateMountModulesAllowedBatch(catalogVersion, filteredPairs, baseUsableItems);
        validateClothingSlotsBatch(clothingSlotIds);
    }

    private void filterRestrictedItems(
        List<MatchWeaponDto> weapons,
        List<MatchOutfitItemDto> outfit,
        Set<String> baseUsableItems,
        Set<String> teamUsableItems,
        Set<String> outfitTeamUsableItems,
        boolean enforceTeamItemRules,
        List<String> warnings
    ) {
        List<MatchWeaponDto> removedWeapons = new ArrayList<>();
        for (MatchWeaponDto weapon : List.copyOf(weapons)) {
            if (weapon.weaponId() == null) continue;
            if (!baseUsableItems.contains(weapon.weaponId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED",
                    "Item is not usable in selected loadout: " + weapon.weaponId());
            }
            if (enforceTeamItemRules && !teamUsableItems.contains(weapon.weaponId())) {
                removedWeapons.add(weapon);
                continue;
            }
            List<MatchModuleDto> removedModules = new ArrayList<>();
            for (MatchModuleDto module : List.copyOf(weapon.modules())) {
                if (!baseUsableItems.contains(module.moduleId())) {
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED",
                        "Item is not usable in selected loadout: " + module.moduleId());
                }
                if (enforceTeamItemRules && !teamUsableItems.contains(module.moduleId())) {
                    removedModules.add(module);
                }
            }
            weapon.modules().removeAll(removedModules);
            for (MatchModuleDto removed : removedModules) {
                warnings.add("Module restricted for team in this game mode, removed: " + removed.moduleId());
            }
        }
        weapons.removeAll(removedWeapons);
        for (MatchWeaponDto removed : removedWeapons) {
            warnings.add("Weapon restricted for team in this game mode, removed: " + removed.weaponId());
        }

        List<MatchOutfitItemDto> removedOutfit = new ArrayList<>();
        for (MatchOutfitItemDto item : outfit) {
            if (!baseUsableItems.contains(item.itemId())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED",
                    "Item is not usable in selected loadout: " + item.itemId());
            }
            if (!outfitTeamUsableItems.contains(item.itemId())) {
                removedOutfit.add(item);
            }
        }
        outfit.removeAll(removedOutfit);
        for (MatchOutfitItemDto removed : removedOutfit) {
            warnings.add("Clothing restricted for team, removed: " + removed.itemId());
        }
    }

    private void validateWeaponSlotsAllowedBatch(String classTag, Set<String> weaponSlotIds) {
        if (weaponSlotIds.isEmpty()) return;
        Map<String, Boolean> rules = catalogValidationData.getWeaponSlotRules(classTag);
        for (String slotId : weaponSlotIds) {
            if (!Boolean.TRUE.equals(rules.get(slotId))) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Weapon slot is not allowed for class: " + slotId);
            }
        }
    }

    private void validateMountModulesAllowedBatch(long catalogVersion, List<ModuleMountPair> pairs, Set<String> baseUsableItems) {
        if (pairs.isEmpty()) return;
        Map<String, Set<String>> allowedByMount = catalogValidationData.getMountAllowedModules(catalogVersion);
        for (ModuleMountPair p : pairs) {
            if (!baseUsableItems.contains(p.moduleId)) continue;
            Set<String> modules = allowedByMount.get(p.mountId);
            if (modules == null || !modules.contains(p.moduleId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Module is not allowed for mount: " + p.moduleId);
            }
        }
    }

    private void validateClothingSlotsBatch(Set<String> clothingSlotIds) {
        if (clothingSlotIds.isEmpty()) return;
        Set<String> activeSlots = catalogValidationData.getActiveClothingSlots();
        for (String slotId : clothingSlotIds) {
            if (!activeSlots.contains(slotId)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOADOUT_VALIDATION_FAILED", "Clothing slot is not active: " + slotId);
            }
        }
    }

    private List<ModuleMountPair> collectModuleMountPairs(List<MatchWeaponDto> weapons) {
        List<ModuleMountPair> pairs = new ArrayList<>();
        for (MatchWeaponDto weapon : weapons) {
            if (weapon.weaponId() == null) continue;
            for (MatchModuleDto module : weapon.modules()) {
                pairs.add(new ModuleMountPair(module.mountId(), module.moduleId()));
            }
        }
        return pairs;
    }

    public record Snapshot(
        List<MatchWeaponDto> weapons,
        List<MatchOutfitItemDto> outfit,
        List<String> warnings
    ) {
    }

    private record ModuleMountPair(String mountId, String moduleId) {
    }
}
