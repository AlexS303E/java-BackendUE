package com.game.backend.access.application;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;

@Service
public class ItemAccessPolicy {
    private final JdbcTemplate jdbcTemplate;

    public ItemAccessPolicy(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        if (itemIds.isEmpty()) {
            return Set.of();
        }

        String placeholders = String.join(",", itemIds.stream().map(id -> "?").toArray(String[]::new));
        Object[] params = new Object[4 + itemIds.size()];
        params[0] = playerId;
        params[1] = catalogVersion;
        int i = 2;
        for (String itemId : itemIds) {
            params[i++] = itemId;
        }
        params[i++] = classTag;
        params[i] = classTag;

        return Set.copyOf(jdbcTemplate.queryForList(
            """
                SELECT ci.item_id
                FROM catalog_items ci
                JOIN player_item_access pia
                  ON pia.item_id = ci.item_id
                 AND pia.catalog_version = ci.catalog_version
                 AND pia.player_id = ?
                WHERE ci.catalog_version = ?
                  AND ci.is_enabled = true
                  AND pia.is_hidden = false
                  AND pia.is_locked_in_shop = false
                  AND pia.is_locked_by_quest = false
                  AND pia.is_disabled = false
                  AND ci.item_id IN (""" + placeholders + """
                )
                  AND NOT EXISTS (
                    SELECT 1 FROM item_class_rules icr
                    WHERE icr.item_id = ci.item_id
                      AND icr.catalog_version = ci.catalog_version
                      AND icr.class_tag = ?
                      AND icr.rule_effect = 'deny'
                  )
                  AND (
                    NOT EXISTS (
                      SELECT 1 FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.rule_effect = 'allow'
                    )
                    OR EXISTS (
                      SELECT 1 FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'allow'
                    )
                  )
                """,
            String.class,
            params
        ));
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
        Boolean canUse = jdbcTemplate.queryForObject(
            """
                SELECT EXISTS(
                  SELECT 1
                  FROM catalog_items ci
                  JOIN player_item_access pia
                    ON pia.item_id = ci.item_id
                   AND pia.catalog_version = ci.catalog_version
                   AND pia.player_id = ?
                  WHERE ci.item_id = ?
                    AND ci.catalog_version = ?
                    AND ci.item_type = ?
                    AND ci.is_enabled = true
                    AND pia.is_hidden = false
                    AND pia.is_locked_in_shop = false
                    AND pia.is_locked_by_quest = false
                    AND pia.is_disabled = false
                    AND NOT EXISTS (
                      SELECT 1
                      FROM item_class_rules icr
                      WHERE icr.item_id = ci.item_id
                        AND icr.catalog_version = ci.catalog_version
                        AND icr.class_tag = ?
                        AND icr.rule_effect = 'deny'
                    )
                    AND (
                      NOT EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.rule_effect = 'allow'
                      )
                      OR EXISTS (
                        SELECT 1
                        FROM item_class_rules icr
                        WHERE icr.item_id = ci.item_id
                          AND icr.catalog_version = ci.catalog_version
                          AND icr.class_tag = ?
                          AND icr.rule_effect = 'allow'
                      )
                    )
                )
                """,
            Boolean.class,
            playerId,
            itemId,
            catalogVersion,
            itemType,
            classTag,
            classTag
        );
        return Boolean.TRUE.equals(canUse);
    }
}
