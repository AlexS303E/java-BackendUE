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
        boolean enforceTeamItemRules = loadEnforceTeamItemRules(request.gameModeId());
        List<MatchWeaponDto> weapons = weapons(request, catalogVersion);
        List<MatchOutfitItemDto> outfit = outfit(request, catalogVersion);
        List<String> warnings = new ArrayList<>();
        validateLoadout(request, catalogVersion, weapons, outfit, enforceTeamItemRules, warnings);
        return new Snapshot(weapons, outfit, warnings);
    }

    private boolean loadEnforceTeamItemRules(String gameModeId) {
        List<Boolean> results = repository.queryForList(
            "SELECT enforce_team_item_rules FROM game_mode_rules WHERE game_mode_id = ?",
            Boolean.class,
            gameModeId
        );
        if (results.isEmpty()) {
            return false;
        }
        return Boolean.TRUE.equals(results.getFirst());
    }

    private List<MatchWeaponDto> weapons(BuildMatchProfileRequest request, long catalogVersion) {
        List<Object[]> rows = repository.query(
            """
                SELECT ws.weapon_slot_id, ws.selected_weapon_id,
                       wcm.mount_id, wcm.module_id
                FROM player_weapon_preset_slots ws
                LEFT JOIN player_weapon_preset_weapon_config_modules wcm
                  ON  wcm.player_id = ws.player_id
                  AND wcm.class_tag = ws.class_tag
                  AND wcm.preset_slot = ws.preset_slot
                  AND wcm.catalog_version = ws.catalog_version
                  AND wcm.weapon_slot_id = ws.weapon_slot_id
                  AND wcm.weapon_id = ws.selected_weapon_id
                WHERE ws.player_id = ?
                  AND ws.class_tag = ?
                  AND ws.preset_slot = ?
                  AND ws.catalog_version = ?
                ORDER BY ws.weapon_slot_id, wcm.mount_id
                """,
            (rs, rowNum) -> new Object[]{
                rs.getString("weapon_slot_id"),
                rs.getString("selected_weapon_id"),
                rs.getString("mount_id"),
                rs.getString("module_id")
            },
            request.playerId(),
            request.classTag(),
            request.weaponPresetSlot(),
            catalogVersion
        );

        Map<String, MatchWeaponDto> weaponMap = new HashMap<>();
        for (Object[] row : rows) {
            String slotId = (String) row[0];
            String weaponId = (String) row[1];
            String mountId = (String) row[2];
            String moduleId = (String) row[3];

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
        return repository.query(
            """
                SELECT clothing_slot_id, item_id
                FROM player_outfit_preset_items
                WHERE player_id = ?
                  AND team_tag = ?
                  AND class_tag = ?
                  AND outfit_preset_slot = ?
                  AND catalog_version = ?
                ORDER BY clothing_slot_id
                """,
            (rs, rowNum) -> new MatchOutfitItemDto(
                rs.getString("clothing_slot_id"),
                rs.getString("item_id")
            ),
            request.playerId(),
            request.teamTag(),
            request.classTag(),
            request.outfitPresetSlot(),
            catalogVersion
        );
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
        Set<String> teamUsableItems = queryTeamCompliantItems(catalogVersion, itemIds, request.teamTag());
        Set<String> outfitTeamUsableItems = queryOutfitTeamCompliantItems(catalogVersion, clothingItemIds, request.teamTag());
        filterRestrictedItems(weapons, outfit, baseUsableItems, teamUsableItems, outfitTeamUsableItems, enforceTeamItemRules, warnings);
        List<ModuleMountPair> filteredPairs = collectModuleMountPairs(weapons);
        validateMountModulesAllowedBatch(catalogVersion, filteredPairs, baseUsableItems);
        validateClothingSlotsBatch(clothingSlotIds);
    }

    private Set<String> queryTeamCompliantItems(long catalogVersion, Set<String> itemIds, String teamTag) {
        if (itemIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[2 + itemIds.size()];
        params[0] = catalogVersion;
        int i = 1;
        for (String id : itemIds) {
            params[i++] = id;
        }
        params[i] = teamTag;
        return Set.copyOf(repository.queryForList(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                WHERE ci.catalog_version = ?
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND (
                    EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                        AND itr.team_scope = 'all'
                    )
                    OR EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                        AND itr.team_scope = 'specific'
                        AND itr.team_tag = ?
                    )
                    OR NOT EXISTS (
                      SELECT 1 FROM item_team_rules itr
                      WHERE itr.item_id = ci.item_id
                        AND itr.catalog_version = ci.catalog_version
                    )
                  )
                """,
            String.class,
            params
        ));
    }

    private Set<String> queryOutfitTeamCompliantItems(long catalogVersion, Set<String> itemIds, String teamTag) {
        if (itemIds.isEmpty()) return Set.of();
        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[2 + itemIds.size()];
        params[0] = catalogVersion;
        int i = 1;
        for (String id : itemIds) {
            params[i++] = id;
        }
        params[i] = teamTag;
        return Set.copyOf(repository.queryForList(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                WHERE ci.catalog_version = ?
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND (
                    EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                        AND oitr.team_scope = 'all'
                    )
                    OR EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                        AND oitr.team_scope = 'specific'
                        AND oitr.team_tag = ?
                    )
                    OR NOT EXISTS (
                      SELECT 1 FROM outfit_item_team_rules oitr
                      WHERE oitr.item_id = ci.item_id
                        AND oitr.catalog_version = ci.catalog_version
                    )
                  )
                """,
            String.class,
            params
        ));
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
