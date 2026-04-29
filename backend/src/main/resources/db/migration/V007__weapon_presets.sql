-- NOTE: v13 prose says catalog_version belongs to the durable preset container key,
-- but the canonical schema uses a primary key without catalog_version plus a unique
-- key with catalog_version. This migration follows the canonical schema exactly;
-- revisit before production DDL freeze.

CREATE TABLE player_weapon_presets (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  revision BIGINT NOT NULL,
  sanitized BOOLEAN NOT NULL DEFAULT false,
  updated_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, class_tag, preset_slot),
  UNIQUE(player_id, class_tag, preset_slot, catalog_version)
);

CREATE TABLE player_weapon_preset_slots (
  player_id UUID NOT NULL,
  class_tag TEXT NOT NULL,
  preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL,
  weapon_slot_id TEXT NOT NULL REFERENCES weapon_slot_definitions(weapon_slot_id),
  selected_weapon_id TEXT NULL,
  PRIMARY KEY(player_id, class_tag, preset_slot, catalog_version, weapon_slot_id),
  FOREIGN KEY(player_id, class_tag, preset_slot, catalog_version)
    REFERENCES player_weapon_presets(player_id, class_tag, preset_slot, catalog_version) ON DELETE CASCADE,
  FOREIGN KEY(selected_weapon_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

CREATE TABLE player_weapon_preset_weapon_configs (
  player_id UUID NOT NULL,
  class_tag TEXT NOT NULL,
  preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL,
  weapon_slot_id TEXT NOT NULL REFERENCES weapon_slot_definitions(weapon_slot_id),
  weapon_id TEXT NOT NULL,
  config_revision BIGINT NOT NULL,
  last_used_at TIMESTAMPTZ NULL,
  PRIMARY KEY(player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id),
  FOREIGN KEY(player_id, class_tag, preset_slot, catalog_version)
    REFERENCES player_weapon_presets(player_id, class_tag, preset_slot, catalog_version) ON DELETE CASCADE,
  FOREIGN KEY(weapon_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

CREATE TABLE player_weapon_preset_weapon_config_modules (
  player_id UUID NOT NULL,
  class_tag TEXT NOT NULL,
  preset_slot INT NOT NULL,
  catalog_version BIGINT NOT NULL,
  weapon_slot_id TEXT NOT NULL,
  weapon_id TEXT NOT NULL,
  mount_id TEXT NOT NULL,
  module_id TEXT NOT NULL,
  PRIMARY KEY(player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id, mount_id),
  FOREIGN KEY(player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id)
    REFERENCES player_weapon_preset_weapon_configs(player_id, class_tag, preset_slot, catalog_version, weapon_slot_id, weapon_id) ON DELETE CASCADE,
  FOREIGN KEY(mount_id, catalog_version) REFERENCES weapon_module_mounts(mount_id, catalog_version),
  FOREIGN KEY(module_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  FOREIGN KEY(mount_id, module_id, catalog_version) REFERENCES weapon_mount_allowed_modules(mount_id, module_id, catalog_version)
);
