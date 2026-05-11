CREATE TABLE game_mode_rules (
  game_mode_id TEXT PRIMARY KEY,
  enforce_team_item_rules BOOLEAN NOT NULL DEFAULT false
);

INSERT INTO game_mode_rules(game_mode_id, enforce_team_item_rules) VALUES
  ('default', false),
  ('tdm', false),
  ('asymmetric_factions', true)
ON CONFLICT (game_mode_id) DO NOTHING;
