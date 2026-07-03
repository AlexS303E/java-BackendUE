package com.game.backend.catalog.application;

import com.game.backend.catalog.repository.CatalogRepository;

import com.game.backend.cache.RedisCacheService;
import com.game.backend.common.api.ApiException;
import com.game.backend.catalog.repository.CatalogRepository.AllowedModuleRecord;
import com.game.backend.catalog.repository.CatalogRepository.CatalogItemRecord;
import com.game.backend.catalog.repository.CatalogRepository.WeaponMountRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Сервис чтения каталога и выбора активной версии для матчей.
 */
@Service
public class CatalogService {
    private final CatalogRepository repository;
    private final RedisCacheService cacheService;

    public CatalogService(CatalogRepository repository, RedisCacheService cacheService) {
        this.repository = repository;
        this.cacheService = cacheService;
    }

    /**
     * Собирает клиентский snapshot активной версии каталога для realm.
     */
    public CatalogSnapshot getSnapshot(String realmId) {
        long catalogVersion = activeCatalogVersion(realmId);
        return cacheService.getCatalogSnapshot(realmId, catalogVersion)
            .orElseGet(() -> {
                CatalogSnapshot response = new CatalogSnapshot(
                    realmId,
                    catalogVersion,
                    items(catalogVersion),
                    weaponMounts(catalogVersion),
                    allowedModules(catalogVersion)
                );
                cacheService.putCatalogSnapshot(response);
                return response;
            });
    }

    /**
     * Находит активную версию каталога, разрешенную для новых матчей.
     */
    public long activeCatalogVersion(String realmId) {
        List<Long> versions = repository.findActiveCatalogVersionsForNewMatches(realmId);
        if (versions.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "CATALOG_VERSION_NOT_SUPPORTED", "No active catalog for realm " + realmId);
        }
        return versions.getFirst();
    }

    /**
     * Проверяет, может ли DS использовать указанную версию каталога для нового матча.
     * Результат кэшируется в Redis на 5 минут, т.к. catalog deployments редки.
     */
    public boolean catalogVersionAllowsNewMatches(String realmId, long catalogVersion) {
        Optional<Boolean> cached = cacheService.getCatalogAllowsNewMatches(realmId, catalogVersion);
        if (cached.isPresent()) {
            return cached.get();
        }
        boolean allowed = repository.catalogVersionAllowsNewMatches(realmId, catalogVersion);
        cacheService.putCatalogAllowsNewMatches(realmId, catalogVersion, allowed);
        return allowed;
    }

    private List<CatalogItem> items(long catalogVersion) {
        return repository.findItems(catalogVersion)
            .stream()
            .map(this::toCatalogItem)
            .toList();
    }

    private List<WeaponMount> weaponMounts(long catalogVersion) {
        return repository.findWeaponMounts(catalogVersion)
            .stream()
            .map(this::toWeaponMount)
            .toList();
    }

    private List<AllowedModule> allowedModules(long catalogVersion) {
        return repository.findAllowedModules(catalogVersion)
            .stream()
            .map(this::toAllowedModule)
            .toList();
    }

    private CatalogItem toCatalogItem(CatalogItemRecord item) {
        return new CatalogItem(
            item.itemId(),
            item.catalogVersion(),
            item.itemType(),
            item.displayName(),
            item.enabled()
        );
    }

    private WeaponMount toWeaponMount(WeaponMountRecord mount) {
        return new WeaponMount(
            mount.mountId(),
            mount.catalogVersion(),
            mount.weaponId(),
            mount.mountType(),
            mount.mountIndex(),
            mount.required(),
            mount.displayOrder()
        );
    }

    private AllowedModule toAllowedModule(AllowedModuleRecord module) {
        return new AllowedModule(
            module.mountId(),
            module.moduleId(),
            module.catalogVersion()
        );
    }
}
