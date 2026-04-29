CREATE TABLE class_weapon_preset_rules (
  class_tag TEXT PRIMARY KEY REFERENCES class_definitions(class_tag),
  base_weapon_preset_count INT NOT NULL CHECK (base_weapon_preset_count >= 0)
);

CREATE TABLE class_weapon_slot_rules (
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  weapon_slot_id TEXT NOT NULL REFERENCES weapon_slot_definitions(weapon_slot_id),
  is_allowed BOOLEAN NOT NULL,
  PRIMARY KEY(class_tag, weapon_slot_id)
);

CREATE TABLE outfit_preset_rules (
  team_tag TEXT NOT NULL REFERENCES team_definitions(team_tag),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  base_outfit_preset_count INT NOT NULL CHECK (base_outfit_preset_count >= 0),
  PRIMARY KEY(team_tag, class_tag)
);

CREATE TABLE player_weapon_preset_slot_unlocks (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  unlocked_extra_count INT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type IN ('default','level','quest','shop','event','admin','compensation','system')),
  source_ref TEXT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, class_tag)
);

CREATE TABLE player_outfit_preset_slot_unlocks (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  team_tag TEXT NOT NULL REFERENCES team_definitions(team_tag),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  unlocked_extra_count INT NOT NULL,
  source_type TEXT NOT NULL CHECK (source_type IN ('default','level','quest','shop','event','admin','compensation','system')),
  source_ref TEXT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, team_tag, class_tag)
);
