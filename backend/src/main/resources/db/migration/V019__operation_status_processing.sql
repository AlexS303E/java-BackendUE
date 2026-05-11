ALTER TABLE IF EXISTS runtime_preset_change_operations
DROP CONSTRAINT IF EXISTS runtime_preset_change_operations_status_check;

ALTER TABLE IF EXISTS runtime_preset_change_operations
ADD CONSTRAINT runtime_preset_change_operations_status_check
CHECK (status IN ('applied','conflict','rejected','duplicate','failed','processing'));
