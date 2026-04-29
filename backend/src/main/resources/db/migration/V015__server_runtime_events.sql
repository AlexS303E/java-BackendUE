CREATE TABLE server_runtime_events (
  event_id UUID PRIMARY KEY,
  match_id UUID NOT NULL REFERENCES server_matches(match_id),
  server_id UUID NOT NULL REFERENCES server_identities(server_id),
  event_seq BIGINT NOT NULL,
  event_type TEXT NOT NULL,
  player_id UUID NULL REFERENCES player_accounts(player_id),
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  UNIQUE(match_id, server_id, event_seq)
);

CREATE INDEX idx_server_runtime_events_match_seq
  ON server_runtime_events(match_id, event_seq);

CREATE INDEX idx_server_runtime_events_type_received
  ON server_runtime_events(event_type, received_at DESC);
