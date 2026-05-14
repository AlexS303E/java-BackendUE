-- Add dead_letter status to outbox_events CHECK constraint
ALTER TABLE outbox_events
  DROP CONSTRAINT IF EXISTS outbox_events_status_check;

ALTER TABLE outbox_events
  ADD CONSTRAINT outbox_events_status_check
  CHECK (status IN ('pending','processing','processed','failed','dead_letter'));
