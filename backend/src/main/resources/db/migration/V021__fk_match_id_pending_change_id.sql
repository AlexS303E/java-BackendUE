-- Add FK from runtime_preset_change_operations.pending_change_id to post_match_pending_changes
ALTER TABLE runtime_preset_change_operations
  ADD CONSTRAINT fk_runtime_op_pending_change
  FOREIGN KEY (pending_change_id) REFERENCES post_match_pending_changes(change_id);
