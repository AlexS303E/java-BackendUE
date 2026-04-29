CREATE TABLE player_match_profiles (
  profile_id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  realm_id TEXT NOT NULL REFERENCES realms(realm_id),
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  team_tag TEXT NOT NULL REFERENCES team_definitions(team_tag),
  weapon_preset_slot INT NOT NULL,
  outfit_preset_slot INT NOT NULL,
  weapon_preset_revision BIGINT NOT NULL,
  outfit_preset_revision BIGINT NOT NULL,
  access_revision BIGINT NOT NULL,
  catalog_version BIGINT NOT NULL REFERENCES catalog_versions(catalog_version),
  profile_revision BIGINT NOT NULL,
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  is_stale BOOLEAN NOT NULL DEFAULT false,
  stale_reason TEXT NULL,
  stale_at TIMESTAMPTZ NULL,
  generated_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NULL,
  UNIQUE(player_id, realm_id, class_tag, team_tag, weapon_preset_slot, outfit_preset_slot, weapon_preset_revision, outfit_preset_revision, access_revision, catalog_version)
);

CREATE TABLE runtime_preset_change_operations (
  operation_id UUID PRIMARY KEY,
  match_id UUID NOT NULL,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  operation_seq BIGINT NOT NULL,
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  weapon_preset_slot INT NOT NULL,
  base_weapon_preset_revision BIGINT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('applied','conflict','rejected','duplicate','failed')),
  result_revision BIGINT NULL,
  pending_change_id UUID NULL,
  request_hash TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  UNIQUE(match_id, player_id, operation_seq)
);

CREATE TABLE post_match_pending_changes (
  change_id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  match_id UUID NOT NULL,
  class_tag TEXT NOT NULL REFERENCES class_definitions(class_tag),
  weapon_preset_slot INT NOT NULL,
  base_weapon_preset_revision BIGINT NOT NULL,
  current_conflicting_revision BIGINT NULL,
  reason_code TEXT NOT NULL CHECK (reason_code IN ('revision_conflict','catalog_conflict','access_conflict','validation_conflict')),
  status TEXT NOT NULL CHECK (status IN ('pending','applied','rejected','expired','superseded')),
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  resolved_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_post_match_pending_player_status
  ON post_match_pending_changes(player_id, status, created_at DESC);

CREATE INDEX idx_post_match_pending_preset_status
  ON post_match_pending_changes(player_id, class_tag, weapon_preset_slot, status);
