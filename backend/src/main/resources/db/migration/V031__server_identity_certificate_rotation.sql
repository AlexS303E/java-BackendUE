CREATE TABLE server_identity_certificates (
  server_id UUID NOT NULL REFERENCES server_identities(server_id) ON DELETE CASCADE,
  certificate_fingerprint TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('active','retiring','revoked','expired')),
  valid_from TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  grace_until TIMESTAMPTZ NULL,
  created_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ NULL,
  PRIMARY KEY(server_id, certificate_fingerprint),
  UNIQUE(certificate_fingerprint)
);

INSERT INTO server_identity_certificates(
  server_id,
  certificate_fingerprint,
  status,
  valid_from,
  expires_at,
  grace_until,
  created_at,
  revoked_at
)
SELECT
  server_id,
  certificate_fingerprint,
  CASE WHEN status = 'active' THEN 'active' ELSE status END,
  created_at,
  expires_at,
  NULL,
  created_at,
  revoked_at
FROM server_identities
ON CONFLICT DO NOTHING;

CREATE INDEX idx_server_identity_certificates_usable
  ON server_identity_certificates(server_id, status, valid_from, expires_at, grace_until)
  WHERE status IN ('active','retiring');
