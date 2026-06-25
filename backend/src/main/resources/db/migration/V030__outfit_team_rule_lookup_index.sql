CREATE INDEX IF NOT EXISTS idx_outfit_item_team_rules_lookup
  ON outfit_item_team_rules(item_id, catalog_version, team_scope, team_tag);
