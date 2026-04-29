CREATE TABLE item_team_rules (
  rule_id UUID PRIMARY KEY,
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  team_scope TEXT NOT NULL CHECK (team_scope IN ('all','specific')),
  team_tag TEXT NULL REFERENCES team_definitions(team_tag),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  CHECK ((team_scope = 'all' AND team_tag IS NULL) OR (team_scope = 'specific' AND team_tag IS NOT NULL))
);

CREATE UNIQUE INDEX uq_item_team_rules_all
  ON item_team_rules(item_id, catalog_version)
  WHERE team_scope = 'all';

CREATE UNIQUE INDEX uq_item_team_rules_specific
  ON item_team_rules(item_id, catalog_version, team_tag)
  WHERE team_scope = 'specific';

CREATE TABLE item_class_rules (
  item_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  rule_effect TEXT NOT NULL CHECK (rule_effect IN ('allow','deny')),
  PRIMARY KEY(item_id, catalog_version, class_tag),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

CREATE TABLE weapon_module_mounts (
  mount_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  weapon_id TEXT NOT NULL,
  mount_type TEXT NOT NULL,
  mount_index INT NOT NULL,
  is_required BOOLEAN NOT NULL,
  display_order INT NOT NULL,
  PRIMARY KEY(mount_id, catalog_version),
  FOREIGN KEY(weapon_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

CREATE TABLE weapon_mount_allowed_modules (
  mount_id TEXT NOT NULL,
  module_id TEXT NOT NULL,
  catalog_version BIGINT NOT NULL,
  PRIMARY KEY(mount_id, module_id, catalog_version),
  FOREIGN KEY(mount_id, catalog_version) REFERENCES weapon_module_mounts(mount_id, catalog_version),
  FOREIGN KEY(module_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);
