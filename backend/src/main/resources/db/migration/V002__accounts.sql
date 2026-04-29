CREATE TABLE player_accounts (
  player_id UUID PRIMARY KEY,
  login_name TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active','banned','disabled','deleted')),
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE player_auth_sessions (
  session_id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  refresh_token_hash TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('active','revoked','expired')),
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_player_auth_sessions_player_status
  ON player_auth_sessions(player_id, status);

CREATE TABLE player_platform_links (
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  provider TEXT NOT NULL CHECK (provider IN ('steam','epic','ps','xbox')),
  provider_user_id TEXT NOT NULL,
  linked_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(player_id, provider),
  UNIQUE(provider, provider_user_id)
);
