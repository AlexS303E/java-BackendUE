-- Indexes for match-profile/build validateCanUseBatch hot path
-- Analysis: the query joins catalog_items + player_item_access + EXISTS on item_class_rules/item_team_rules
-- These covering indexes eliminate heap lookups and enable single-index seeks for EXISTS subqueries.

CREATE INDEX IF NOT EXISTS idx_catalog_items_catalog_enabled
    ON catalog_items (catalog_version, is_enabled, item_id);

CREATE INDEX IF NOT EXISTS idx_item_class_rules_lookup
    ON item_class_rules (item_id, catalog_version, class_tag, rule_effect);

CREATE INDEX IF NOT EXISTS idx_item_team_rules_lookup
    ON item_team_rules (item_id, catalog_version, team_scope, team_tag);
