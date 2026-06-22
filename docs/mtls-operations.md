# mTLS Operations

Stage 1 uses a single active certificate fingerprint per `server_identities`
row. Full overlapping multi-fingerprint rotation is intentionally deferred to
Stage 2 hardening.

## Certificate Rotation Plan

### Preconditions

- Generate new Dedicated Server certificate material outside git with
  `tools/mtls/generate-dev-certs.ps1` for local validation, or through the
  production certificate authority for production.
- Confirm the new certificate subject, server owner, realm, build id, and
  expiration date.
- Compute and record the SHA-256 certificate fingerprint.

### Apply

- Drain or pause new match assignment for the server identity being rotated.
- Update `server_identities.certificate_fingerprint` for the target `server_id`
  in the same maintenance window as DS certificate deployment.
- Keep `status = 'active'` and preserve `allowed_scopes`, `realm_id`, and
  `server_build_id` unless the rotation is part of a scope/build change.
- Restart or reload the DS process so it uses the new private key/certificate.

### Verify

- Run `tools/mtls/run-mtls-smoke.ps1` against the private mTLS connector.
- Confirm `/server/*` requests with the new certificate authenticate and reach
  request validation or business handling.
- Confirm requests with the old certificate fail with
  `certificate_fingerprint_mismatch` after the cutover.
- Check `server_audit_events` for denied attempts and successful server actions.

### Rollback

- Reapply the previous known-good `certificate_fingerprint` for the same
  `server_id`.
- Restart or reload the DS process with the previous certificate material.
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

- Add multi-fingerprint overlap and grace-period rotation.
- Add CRL/OCSP or managed CA revocation distribution if infrastructure requires
  TLS-layer revocation before the application receives the request.
- Add expiry/revocation metrics and alerts for server identities.
