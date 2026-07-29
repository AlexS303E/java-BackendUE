CREATE TABLE catalog_bootstrap_weapon_defaults (
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  preset_slot INT NOT NULL CHECK (preset_slot > 0),
  weapon_slot_id TEXT NOT NULL REFERENCES weapon_slot_definitions(weapon_slot_id),
  weapon_id TEXT NOT NULL,
  mount_id TEXT NULL,
  module_id TEXT NULL,
  PRIMARY KEY(catalog_version, class_tag, preset_slot),
  FOREIGN KEY(weapon_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  FOREIGN KEY(mount_id, catalog_version) REFERENCES weapon_module_mounts(mount_id, catalog_version),
  FOREIGN KEY(module_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version),
  CHECK ((mount_id IS NULL AND module_id IS NULL) OR (mount_id IS NOT NULL AND module_id IS NOT NULL))
);

CREATE TABLE catalog_bootstrap_outfit_defaults (
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  team_tag TEXT NOT NULL REFERENCES team_definitions(team_tag),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  outfit_preset_slot INT NOT NULL CHECK (outfit_preset_slot > 0),
  clothing_slot_id TEXT NOT NULL REFERENCES clothing_slot_definitions(clothing_slot_id),
  item_id TEXT NOT NULL,
  PRIMARY KEY(catalog_version, team_tag, class_tag, outfit_preset_slot, clothing_slot_id),
  FOREIGN KEY(item_id, catalog_version) REFERENCES catalog_items(item_id, catalog_version)
);

INSERT INTO catalog_bootstrap_weapon_defaults(
  catalog_version, class_tag, preset_slot, weapon_slot_id, weapon_id, mount_id, module_id
)
VALUES (
  1, 'class.assault', 1, 'primary', 'weapon.ak12', 'weapon.ak12.mount.scope.01', 'module.scope.red_dot_01'
);

INSERT INTO catalog_bootstrap_outfit_defaults(
  catalog_version, team_tag, class_tag, outfit_preset_slot, clothing_slot_id, item_id
)
VALUES
  (1, 'team.red', 'class.assault', 1, 'torso', 'clothing.team_red.jacket_01'),
  (1, 'team.blue', 'class.assault', 1, 'torso', 'clothing.team_blue.jacket_01');
