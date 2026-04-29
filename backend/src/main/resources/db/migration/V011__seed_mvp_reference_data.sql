INSERT INTO realms(realm_id, display_name, is_active)
VALUES ('global', 'Global', true);

INSERT INTO class_definitions(class_tag, display_name, is_active)
VALUES ('class.assault', 'Assault', true);

INSERT INTO team_definitions(team_tag, display_name, is_active)
VALUES ('team.red', 'Red', true), ('team.blue', 'Blue', true);

INSERT INTO weapon_slot_definitions(weapon_slot_id, display_name, slot_group, order_index, is_active)
VALUES
  ('primary', 'Primary', 'weapon', 10, true),
  ('secondary', 'Secondary', 'weapon', 20, true),
  ('pistol', 'Pistol', 'weapon', 30, true),
  ('melee', 'Melee', 'weapon', 40, true),
  ('grenade', 'Grenade', 'equipment', 50, true);

INSERT INTO clothing_slot_definitions(clothing_slot_id, display_name, order_index, is_active)
VALUES
  ('head', 'Head', 10, true),
  ('torso', 'Torso', 20, true),
  ('legs', 'Legs', 30, true),
  ('feet', 'Feet', 40, true),
  ('armor', 'Armor', 50, true);
