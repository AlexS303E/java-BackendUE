# mTLS Operations

Server identity rotation supports multiple accepted certificate fingerprints per
`server_id` through `server_identity_certificates`. The legacy
`server_identities.certificate_fingerprint` remains the current/primary
fingerprint for compatibility with existing admin views and local scripts.

## Certificate Rotation Plan

### Preconditions

- Generate new Dedicated Server certificate material outside git with
  `tools/mtls/generate-dev-certs.ps1` for local validation, or through the
  production certificate authority for production.
- Confirm the new certificate subject, server owner, realm, build id, and
  expiration date.
- Compute and record the SHA-256 certificate fingerprint.

### Apply

- Insert the new fingerprint into `server_identity_certificates` with
  `status = 'active'`, `valid_from <= now()`, and its certificate `expires_at`.
- Keep `server_identities.status = 'active'` and preserve `allowed_scopes`,
  `realm_id`, and `server_build_id` unless the rotation is part of a
  scope/build change.
- Roll Dedicated Server instances so they start using the new private
  key/certificate.
- After rollout, update `server_identities.certificate_fingerprint` to the new
  current fingerprint for compatibility surfaces.
- Move the old row in `server_identity_certificates` to `status = 'retiring'`
  and set `grace_until` to the end of the overlap window.

### Verify

- Run `tools/mtls/run-mtls-smoke.ps1` against the private mTLS connector.
- Confirm `/server/*` requests with the new certificate authenticate and reach
  request validation or business handling.
- During grace, confirm both old and new certificates authenticate.
- After `grace_until`, confirm requests with the old certificate fail with
  `certificate_fingerprint_mismatch`.
- Check `server_audit_events` for denied attempts and successful server actions.

### Rollback

- Set the previous known-good row in `server_identity_certificates` back to
  `status = 'active'` and clear `revoked_at`.
- Reapply the previous known-good `server_identities.certificate_fingerprint`
  for the same `server_id` if the compatibility current pointer was advanced.
- Roll the DS process back to the previous certificate material.
- Re-run the mTLS smoke and record the rollback reason in the incident timeline.

## Revocation List

Stage 1 revocation is the application-level server identity list in
`server_identities`, not a distributed CRL/OCSP system.

### Revoke

- Prefer `POST /admin/server-identities/revoke` with `X-Admin-Confirm: true`,
  `Idempotency-Key`, and a human-readable `reason`.
- The endpoint sets `status = 'revoked'`, writes `revoked_at`, records admin
  audit, and publishes `server_identity.revoked` through outbox.
- Emergency SQL is allowed only when admin API is unavailable:

```sql
UPDATE server_identities
SET status = 'revoked',
    revoked_at = now()
WHERE server_id = '<server-id>'
  AND status <> 'revoked';
```

### Verify

- Requests from the revoked identity are denied with `UNAUTHENTICATED`.
- `ServerAdminSecurityIntegrationTest` covers revoked identities at the server
  auth boundary.
- `AdminParityIntegrationTest` covers the admin revoke endpoint and idempotent
  replay behavior.
- `server_audit_events` contains authentication denial entries for known
  revoked server ids.

### Stage 2 Follow-Up

- Add CRL/OCSP or managed CA revocation distribution if infrastructure requires
  TLS-layer revocation before the application receives the request.
- Add expiry/revocation metrics and alerts for server identities.
