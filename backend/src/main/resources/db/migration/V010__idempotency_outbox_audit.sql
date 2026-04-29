CREATE TABLE api_idempotency_records (
  operation_scope TEXT NOT NULL,
  actor_id TEXT NOT NULL,
  route_fingerprint TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash TEXT NOT NULL,
  status_code INT NOT NULL,
  response_body JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY(operation_scope, actor_id, idempotency_key)
);

CREATE TABLE outbox_events (
  event_id UUID PRIMARY KEY,
  event_type TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('pending','processing','processed','failed')),
  attempts INT NOT NULL,
  next_attempt_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  processed_at TIMESTAMPTZ NULL,
  last_error TEXT NULL
);

CREATE TABLE server_identities (
  server_id UUID PRIMARY KEY,
  realm_id TEXT NOT NULL REFERENCES realms(realm_id),
  server_build_id TEXT NOT NULL,
  certificate_fingerprint TEXT NOT NULL UNIQUE,
  status TEXT NOT NULL CHECK (status IN ('active','revoked','expired')),
  allowed_scopes TEXT[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ NULL
);

CREATE TABLE server_matches (
  match_id UUID PRIMARY KEY,
  server_id UUID NOT NULL REFERENCES server_identities(server_id),
  realm_id TEXT NOT NULL REFERENCES realms(realm_id),
  status TEXT NOT NULL CHECK (status IN ('creating','running','finished','failed')),
  created_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NULL
);

CREATE TABLE admin_audit_events (
  event_id UUID PRIMARY KEY,
  actor_id TEXT NOT NULL,
  action TEXT NOT NULL,
  target_type TEXT NOT NULL,
  target_id TEXT NOT NULL,
  request_hash TEXT NULL,
  payload JSONB NULL,
  result TEXT NOT NULL CHECK (result IN ('success','denied','failed')),
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE server_audit_events (
  event_id UUID PRIMARY KEY,
  server_id UUID NOT NULL REFERENCES server_identities(server_id),
  match_id UUID NULL REFERENCES server_matches(match_id),
  action TEXT NOT NULL,
  scope TEXT NOT NULL,
  result TEXT NOT NULL CHECK (result IN ('success','denied','failed')),
  payload JSONB NULL,
  created_at TIMESTAMPTZ NOT NULL
);
