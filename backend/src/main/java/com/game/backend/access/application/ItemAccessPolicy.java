package com.game.backend.access.application;

import com.game.backend.access.repository.AccessRepository;

import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class ItemAccessPolicy {
    private final AccessRepository repository;

    public ItemAccessPolicy(AccessRepository repository) {
        this.repository = repository;
    }

    public boolean canUseForUi(
        boolean catalogEnabled,
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled
    ) {
        return catalogEnabled && playerAccessCanUse(hidden, lockedInShop, lockedByQuest, disabled);
    }

    public boolean canUseForPresetSave(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        return canUseWithClassRules(playerId, itemId, catalogVersion, classTag, itemType);
    }

    public boolean canUseForRuntimePresetChange(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        return canUseWithClassRules(playerId, itemId, catalogVersion, classTag, itemType);
    }

    public Set<String> usableItemsForMatchProfile(
        UUID playerId,
        long catalogVersion,
        String classTag,
        Set<String> itemIds
    ) {
        return repository.findUsableItemsForMatchProfile(playerId, catalogVersion, classTag, itemIds);
    }

    private boolean playerAccessCanUse(
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled
    ) {
        return !hidden && !lockedInShop && !lockedByQuest && !disabled;
    }

    private boolean canUseWithClassRules(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        return repository.canUseWithClassRules(
            playerId,
            itemId,
            catalogVersion,
            classTag,
            itemType
        );
    }
}
