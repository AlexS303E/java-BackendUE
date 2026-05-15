CREATE INDEX IF NOT EXISTS idx_admin_audit_events_created_at
  ON admin_audit_events(created_at);

CREATE INDEX IF NOT EXISTS idx_server_audit_events_created_at
  ON server_audit_events(created_at);
