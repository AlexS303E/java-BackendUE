CREATE TABLE IF NOT EXISTS runtime_operation_streams (
  match_id UUID NOT NULL,
  player_id UUID NOT NULL,
  last_applied_seq BIGINT NOT NULL DEFAULT 0,
  PRIMARY KEY (match_id, player_id)
);
