-- Add updated_at for status transition tracking (created_at should never change)
ALTER TABLE runtime_preset_change_operations
  ADD COLUMN updated_at TIMESTAMPTZ;

-- Backfill updated_at for existing rows (conservative: use created_at as fallback)
UPDATE runtime_preset_change_operations
  SET updated_at = created_at
  WHERE updated_at IS NULL;
