# Server auth / mTLS hardening

## Production rules

When a production profile is active (`prod` or `production`), startup fails unless all of these are true:

- `app.server-auth.mtls.enabled=true`
- `app.server-auth.mtls.require-private-port=true`
- `app.server-auth.mtls.allow-header-fingerprint-fallback=false`

This keeps the `X-Server-Certificate-Fingerprint` shortcut limited to local/dev/test workflows.

## Runtime server API checks

All `/server/*` endpoints still require:

- `X-Server-Id`
- a resolved client certificate fingerprint from the mTLS request, or dev/test header fallback only when explicitly enabled
- active, non-expired `server_identities` row
- exact certificate fingerprint match
- required endpoint scope
- match assignment / realm / build validation in the server operation services

## Denied audit events

When the request contains a known `server_id`, authentication denials are recorded in `server_audit_events` with:

- `action = server_auth.authentication_denied`
- `result = denied`
- `scope = required endpoint scope`
- `payload.reason`, for example:
  - `wrong_private_port`
  - `missing_client_certificate`
  - `mtls_disabled_and_header_fallback_forbidden`
  - `missing_header_fingerprint_fallback`
  - `server_identity_revoked`
  - `server_identity_expired`
  - `certificate_fingerprint_mismatch`

Scope denials for authenticated server identities continue to be recorded as `server_auth.scope_denied`.
