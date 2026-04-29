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
  '10000000-0000-0000-0000-000000000001',
  'global',
  'ds-dev-smoke',
  'dev-ds-fingerprint',
  'active',
  ARRAY[
    'match_profile:read',
    'runtime_event:write',
    'runtime_preset_change:write',
    'match_audit:write'
  ],
  now(),
  now() + interval '365 days'
);
