-- outfit_item_team_rules: clothing-specific team restrictions (separate from item_team_rules for weapons/modules)

CREATE TABLE outfit_item_team_rules (
  rule_id UUID PRIMARY KEY,
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  team_scope TEXT NOT NULL CHECK (team_scope IN ('all','specific')),
  team_tag TEXT NULL REFERENCES team_definitions(team_tag),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  CHECK ((team_scope = 'all' AND team_tag IS NULL) OR (team_scope = 'specific' AND team_tag IS NOT NULL))
);

CREATE UNIQUE INDEX uq_outfit_item_team_rules_all
  ON outfit_item_team_rules(item_id, catalog_version)
  WHERE team_scope = 'all';

CREATE UNIQUE INDEX uq_outfit_item_team_rules_specific
  ON outfit_item_team_rules(item_id, catalog_version, team_tag)
  WHERE team_scope = 'specific';

-- Migrate existing clothing team rules from item_team_rules
INSERT INTO outfit_item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
SELECT rule_id, item_id, catalog_version, team_scope, team_tag
FROM item_team_rules
WHERE item_id IN ('clothing.team_red.jacket_01', 'clothing.team_blue.jacket_01')
ON CONFLICT DO NOTHING;

-- Seed clothing rules for catalog v2 if not already in item_team_rules (integration tests add these manually)
