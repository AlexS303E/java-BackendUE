INSERT INTO server_identities(
  server_id,
  realm_id,
  server_build_id,
  certificate_fingerprint,
  status,
  allowed_scopes,
  created_at,
  expires_at
)
VALUES (
  '10000000-0000-0000-0000-000000000002',
  'global',
  'ds-dev-limited-smoke',
  'dev-ds-limited-fingerprint',
  'active',
  ARRAY['match_profile:read'],
  now(),
  now() + interval '365 days'
);
