package com.game.backend.catalog.application;

import com.game.backend.catalog.repository.CatalogRepository;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class CatalogValidationData {
    private final CatalogRepository repository;

    private final ConcurrentHashMap<String, Map<String, Boolean>> weaponSlotAllowedCache = new ConcurrentHashMap<>();
    private volatile Set<String> clothingSlotActiveCache = null;
    private final ConcurrentHashMap<Long, Map<String, Set<String>>> mountAllowedModulesCache = new ConcurrentHashMap<>();

    public CatalogValidationData(CatalogRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void init() {
        loadActiveClothingSlots();
        loadAllWeaponSlotRules();
        loadAllMountAllowedModules();
    }

    private void loadAllWeaponSlotRules() {
        List<Map<String, Object>> rows = repository.queryForList(
            "SELECT class_tag, weapon_slot_id, is_allowed FROM class_weapon_slot_rules"
        );
        Map<String, Map<String, Boolean>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String classTag = (String) row.get("class_tag");
            String slotId = (String) row.get("weapon_slot_id");
            boolean allowed = (Boolean) row.get("is_allowed");
            grouped.computeIfAbsent(classTag, k -> new HashMap<>()).put(slotId, allowed);
        }
        grouped.forEach((classTag, rules) ->
            weaponSlotAllowedCache.put(classTag, Collections.unmodifiableMap(rules))
        );
    }

    private void loadAllMountAllowedModules() {
        List<Map<String, Object>> rows = repository.queryForList(
            "SELECT catalog_version, mount_id, module_id FROM weapon_mount_allowed_modules ORDER BY catalog_version, mount_id, module_id"
        );
        Map<Long, Map<String, Set<String>>> grouped = new HashMap<>();
        for (Map<String, Object> row : rows) {
            long catalogVersion = ((Number) row.get("catalog_version")).longValue();
            String mountId = (String) row.get("mount_id");
            String moduleId = (String) row.get("module_id");
            grouped.computeIfAbsent(catalogVersion, k -> new HashMap<>())
                .computeIfAbsent(mountId, k -> new HashSet<>())
                .add(moduleId);
        }
        grouped.forEach((version, mounts) -> {
            Map<String, Set<String>> frozen = mounts.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                    Map.Entry::getKey,
                    e -> Collections.unmodifiableSet(e.getValue())
                ));
            mountAllowedModulesCache.put(version, frozen);
        });
    }

    public Map<String, Boolean> getWeaponSlotRules(String classTag) {
        Map<String, Boolean> rules = weaponSlotAllowedCache.get(classTag);
        if (rules != null) {
            return rules;
        }
        rules = repository.query(
            "SELECT weapon_slot_id, is_allowed FROM class_weapon_slot_rules WHERE class_tag = ?",
            (rs, rowNum) -> Map.entry(rs.getString("weapon_slot_id"), rs.getBoolean("is_allowed")),
            classTag
        ).stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        weaponSlotAllowedCache.put(classTag, rules);
        return rules;
    }

    public Set<String> getActiveClothingSlots() {
        Set<String> cached = clothingSlotActiveCache;
        if (cached != null) {
            return cached;
        }
        return loadActiveClothingSlots();
    }

    private Set<String> loadActiveClothingSlots() {
        Set<String> active = Collections.unmodifiableSet(new HashSet<>(
            repository.queryForList(
                "SELECT clothing_slot_id FROM clothing_slot_definitions WHERE is_active = true",
                String.class
            )
        ));
        clothingSlotActiveCache = active;
        return active;
    }

    public Map<String, Set<String>> getMountAllowedModules(long catalogVersion) {
        Map<String, Set<String>> cached = mountAllowedModulesCache.get(catalogVersion);
        if (cached != null) {
            return cached;
        }
        List<Map<String, Object>> rows = repository.queryForList(
            "SELECT mount_id, module_id FROM weapon_mount_allowed_modules WHERE catalog_version = ?",
            catalogVersion
        );
        Map<String, Set<String>> result = new HashMap<>();
        for (Map<String, Object> row : rows) {
            String mountId = (String) row.get("mount_id");
            String moduleId = (String) row.get("module_id");
            result.computeIfAbsent(mountId, k -> new HashSet<>()).add(moduleId);
        }
        result = result.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                Map.Entry::getKey,
                e -> Collections.unmodifiableSet(e.getValue())
            ));
        mountAllowedModulesCache.put(catalogVersion, result);
        return result;
    }

    public void evict() {
        weaponSlotAllowedCache.clear();
        clothingSlotActiveCache = null;
        mountAllowedModulesCache.clear();
    }
}
