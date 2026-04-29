INSERT INTO catalog_versions(
  catalog_version,
  artifact_hash,
  schema_version,
  state,
  created_at,
  validated_at,
  activated_at
)
VALUES (
  1,
  'mvp-seed-catalog-v1',
  1,
  'active',
  now(),
  now(),
  now()
);

INSERT INTO catalog_deployments(
  realm_id,
  catalog_version,
  deployment_state,
  rollout_percent,
  allow_new_matches,
  allow_existing_matches,
  activated_at
)
VALUES ('global', 1, 'active', 100, true, true, now());

INSERT INTO catalog_items(
  item_id,
  catalog_version,
  item_type,
  display_name,
  is_enabled,
  payload_schema_version,
  created_at
)
VALUES
  ('weapon.ak12', 1, 'weapon', 'AK-12', true, 1, now()),
  ('module.scope.red_dot_01', 1, 'module', 'Red Dot Sight', true, 1, now()),
  ('clothing.team_red.jacket_01', 1, 'clothing', 'Red Team Jacket', true, 1, now()),
  ('clothing.team_blue.jacket_01', 1, 'clothing', 'Blue Team Jacket', true, 1, now());

INSERT INTO catalog_item_fragments(
  item_id,
  catalog_version,
  fragment_type,
  fragment_order,
  payload,
  payload_schema_version
)
VALUES
  (
    'weapon.ak12',
    1,
    'damage_profile',
    10,
    '{"schema_version":1,"damage_mode":"distance_curve_multiplier","base_damage":40.0,"damage_curve_id":"curve.ak12.damage_falloff","min_damage":15.0,"max_damage":null,"range_unit":"meters","damage_type":"ballistic"}'::jsonb,
    1
  ),
  (
    'module.scope.red_dot_01',
    1,
    'module_stat_modifier',
    10,
    '{"schema_version":1,"modifiers":[{"target_stat":"spread.x","operation":"add_percent","value":-0.10}]}'::jsonb,
    1
  );

INSERT INTO item_team_rules(rule_id, item_id, catalog_version, team_scope, team_tag)
VALUES
  ('00000000-0000-0000-0000-000000000101', 'weapon.ak12', 1, 'all', null),
  ('00000000-0000-0000-0000-000000000102', 'module.scope.red_dot_01', 1, 'all', null),
  ('00000000-0000-0000-0000-000000000103', 'clothing.team_red.jacket_01', 1, 'specific', 'team.red'),
  ('00000000-0000-0000-0000-000000000104', 'clothing.team_blue.jacket_01', 1, 'specific', 'team.blue');

INSERT INTO item_class_rules(item_id, catalog_version, class_tag, rule_effect)
VALUES
  ('weapon.ak12', 1, 'class.assault', 'allow'),
  ('module.scope.red_dot_01', 1, 'class.assault', 'allow'),
  ('clothing.team_red.jacket_01', 1, 'class.assault', 'allow'),
  ('clothing.team_blue.jacket_01', 1, 'class.assault', 'allow');

INSERT INTO weapon_module_mounts(
  mount_id,
  catalog_version,
  weapon_id,
  mount_type,
  mount_index,
  is_required,
  display_order
)
VALUES ('weapon.ak12.mount.scope.01', 1, 'weapon.ak12', 'scope', 1, false, 10);

INSERT INTO weapon_mount_allowed_modules(mount_id, module_id, catalog_version)
VALUES ('weapon.ak12.mount.scope.01', 'module.scope.red_dot_01', 1);

INSERT INTO class_weapon_preset_rules(class_tag, base_weapon_preset_count)
VALUES ('class.assault', 1);

INSERT INTO class_weapon_slot_rules(class_tag, weapon_slot_id, is_allowed)
VALUES
  ('class.assault', 'primary', true),
  ('class.assault', 'grenade', true);

INSERT INTO outfit_preset_rules(team_tag, class_tag, base_outfit_preset_count)
VALUES
  ('team.red', 'class.assault', 1),
  ('team.blue', 'class.assault', 1);
