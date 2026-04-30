CREATE TABLE player_notifications (
  notification_id UUID PRIMARY KEY,
  player_id UUID NOT NULL REFERENCES player_accounts(player_id),
  event_type TEXT NOT NULL,
  aggregate_type TEXT NOT NULL,
  aggregate_id TEXT NOT NULL,
  payload JSONB NOT NULL,
  payload_schema_version INT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('unread','read','archived')),
  created_at TIMESTAMPTZ NOT NULL,
  read_at TIMESTAMPTZ NULL,
  expires_at TIMESTAMPTZ NULL
);

CREATE INDEX idx_player_notifications_player_status_created
  ON player_notifications(player_id, status, created_at DESC);

CREATE INDEX idx_player_notifications_player_created
  ON player_notifications(player_id, created_at DESC);
