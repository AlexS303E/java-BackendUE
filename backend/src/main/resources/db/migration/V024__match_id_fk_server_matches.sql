-- Make match_id nullable in both tables (catalog conflicts have no match context)
ALTER TABLE runtime_preset_change_operations
  ALTER COLUMN match_id DROP NOT NULL;

ALTER TABLE post_match_pending_changes
  ALTER COLUMN match_id DROP NOT NULL;

-- Nullify orphan match_ids that don't reference existing server_matches
-- (catalog conflict pending changes used operationId as match_id)
UPDATE runtime_preset_change_operations
  SET match_id = NULL
  WHERE match_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM server_matches WHERE match_id = runtime_preset_change_operations.match_id);

UPDATE post_match_pending_changes
  SET match_id = NULL
  WHERE match_id IS NOT NULL
    AND NOT EXISTS (SELECT 1 FROM server_matches WHERE match_id = post_match_pending_changes.match_id);

-- Add FK constraints (nullable — only checked when match_id IS NOT NULL)
ALTER TABLE runtime_preset_change_operations
  ADD CONSTRAINT fk_runtime_op_match
  FOREIGN KEY (match_id) REFERENCES server_matches(match_id);

ALTER TABLE post_match_pending_changes
  ADD CONSTRAINT fk_pending_change_match
  FOREIGN KEY (match_id) REFERENCES server_matches(match_id);
