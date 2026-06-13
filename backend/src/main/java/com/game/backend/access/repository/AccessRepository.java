package com.game.backend.access.repository;

import com.game.backend.common.persistence.JdbcRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Repository
public class AccessRepository extends JdbcRepository {
    public AccessRepository(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<Long> findAccessRevision(UUID playerId) {
        return queryForList(
            "SELECT access_revision FROM player_access_projection_state WHERE player_id = ?",
            Long.class,
            playerId
        );
    }

    public List<AccessItemRow> findAccessItems(UUID playerId, long catalogVersion) {
        return query(
            """
                SELECT
                  pia.item_id,
                  ci.item_type,
                  ci.display_name,
                  ci.is_enabled,
                  pia.is_hidden,
                  pia.is_locked_in_shop,
                  pia.is_locked_by_quest,
                  pia.is_disabled,
                  pia.disabled_reason,
                  pia.unlock_hint_code,
                  pia.unlock_hint_payload::text AS unlock_hint_payload
                FROM player_item_access pia
                JOIN catalog_items ci
                  ON ci.item_id = pia.item_id
                 AND ci.catalog_version = pia.catalog_version
                WHERE pia.player_id = ?
                  AND pia.catalog_version = ?
                ORDER BY ci.item_type, pia.item_id
                """,
            (rs, rowNum) -> new AccessItemRow(
                rs.getString("item_id"),
                rs.getString("item_type"),
                rs.getString("display_name"),
                rs.getBoolean("is_enabled"),
                rs.getBoolean("is_hidden"),
                rs.getBoolean("is_locked_in_shop"),
                rs.getBoolean("is_locked_by_quest"),
                rs.getBoolean("is_disabled"),
                rs.getString("disabled_reason"),
                rs.getString("unlock_hint_code"),
                rs.getString("unlock_hint_payload")
            ),
            playerId,
            catalogVersion
        );
    }

    public Set<String> findUsableItemsForMatchProfile(
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

        return Set.copyOf(queryForList(
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

    public boolean canUseWithClassRules(
        UUID playerId,
        String itemId,
        long catalogVersion,
        String classTag,
        String itemType
    ) {
        Boolean canUse = queryForObject(
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

    public record AccessItemRow(
        String itemId,
        String itemType,
        String displayName,
        boolean enabled,
        boolean hidden,
        boolean lockedInShop,
        boolean lockedByQuest,
        boolean disabled,
        String disabledReason,
        String unlockHintCode,
        String unlockHintPayload
    ) {
    }
}
