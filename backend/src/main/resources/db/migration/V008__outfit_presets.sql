CREATE TABLE player_outfit_presets (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  team_tag TEXT NOT NULL REFERENCES team_definitions(team_tag),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  outfit_preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  revision BIGINT NOT NULL,
  sanitized BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, team_tag, class_tag, outfit_preset_slot),
  UNIQUE(player_id, team_tag, class_tag, outfit_preset_slot, catalog_version)
);

CREATE TABLE player_outfit_preset_items (
  player_id UUID NOT NULL,
  team_tag TEXT NOT NULL,
  class_tag TEXT NOT NULL,
  outfit_preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL,
  clothing_slot_id TEXT NOT NULL REFERENCES clothing_slot_definitions(clothing_slot_id),
  item_id TEXT NOT NULL,
  PRIMARY KEY(player_id, team_tag, class_tag, outfit_preset_slot, catalog_version, clothing_slot_id),
  FOREIGN KEY(player_id, team_tag, class_tag, outfit_preset_slot, catalog_version)
    REFERENCES player_outfit_presets(player_id, team_tag, class_tag, outfit_preset_slot, catalog_version) ON DELETE CASCADE,
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);
