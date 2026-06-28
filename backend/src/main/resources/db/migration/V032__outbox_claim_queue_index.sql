CREATE INDEX IF NOT EXISTS idx_outbox_events_claim_queue
  ON outbox_events(next_attempt_at, created_at)
  WHERE status IN ('pending', 'failed');
