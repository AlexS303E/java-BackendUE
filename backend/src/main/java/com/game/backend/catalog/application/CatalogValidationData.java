package com.game.backend.catalog.application;

import com.game.backend.catalog.repository.CatalogRepository;
import com.game.backend.catalog.repository.CatalogRepository.MountAllowedModule;
import com.game.backend.catalog.repository.CatalogRepository.WeaponSlotRule;

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
        Map<String, Map<String, Boolean>> grouped = new HashMap<>();
        for (WeaponSlotRule rule : repository.findAllWeaponSlotRules()) {
            grouped.computeIfAbsent(rule.classTag(), k -> new HashMap<>()).put(rule.weaponSlotId(), rule.allowed());
        }
        grouped.forEach((classTag, rules) ->
            weaponSlotAllowedCache.put(classTag, Collections.unmodifiableMap(rules))
        );
    }

    private void loadAllMountAllowedModules() {
        Map<Long, Map<String, Set<String>>> grouped = new HashMap<>();
        for (MountAllowedModule allowedModule : repository.findAllMountAllowedModules()) {
            grouped.computeIfAbsent(allowedModule.catalogVersion(), k -> new HashMap<>())
                .computeIfAbsent(allowedModule.mountId(), k -> new HashSet<>())
                .add(allowedModule.moduleId());
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
        rules = repository.findWeaponSlotRules(classTag).stream()
            .collect(Collectors.toUnmodifiableMap(WeaponSlotRule::weaponSlotId, WeaponSlotRule::allowed));
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
        Set<String> active = Collections.unmodifiableSet(new HashSet<>(repository.findActiveClothingSlotIds()));
        clothingSlotActiveCache = active;
        return active;
    }

    public Map<String, Set<String>> getMountAllowedModules(long catalogVersion) {
        Map<String, Set<String>> cached = mountAllowedModulesCache.get(catalogVersion);
        if (cached != null) {
            return cached;
        }
        Map<String, Set<String>> result = new HashMap<>();
        for (MountAllowedModule allowedModule : repository.findMountAllowedModules(catalogVersion)) {
            result.computeIfAbsent(allowedModule.mountId(), k -> new HashSet<>()).add(allowedModule.moduleId());
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
