ALTER TABLE outbox_events
  ADD COLUMN processing_owner TEXT NULL,
  ADD COLUMN processing_token UUID NULL,
  ADD COLUMN processing_started_at TIMESTAMPTZ NULL,
  ADD COLUMN processing_deadline TIMESTAMPTZ NULL;
