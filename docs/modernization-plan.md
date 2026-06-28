# Modernization Plan

## Block 1 - mTLS MVP stabilization

Goal: make the current Dedicated Server mTLS integration stable enough to commit and use in local integration.

Checklist:

- Keep the public connector on `server.port` for `/auth`, `/catalog`, `/me`, `/admin`, and health endpoints.
- Serve `/server/*` through the private HTTPS mTLS connector when `SERVER_MTLS_ENABLED=true`.
- Bind `SERVER_MTLS_*` environment variables to `app.server-auth.mtls.*` Spring properties.
- Require `X-Server-Id` for server identity lookup.
- In mTLS mode, compute the SHA-256 fingerprint from the TLS client certificate and ignore `X-Server-Certificate-Fingerprint`.
- Keep `X-Server-Certificate-Fingerprint` only as a dev/test fallback when mTLS is disabled.
- Generate local certificate material under `tools/mtls/out/`; never commit generated certificates, keystores, truststores, CSRs, or private keys.
- Keep OpenAPI and docs explicit about the private mTLS connector and the deprecated dev-only fallback header.

Validation:

- `.\gradlew.bat test`
- `powershell -ExecutionPolicy Bypass -File tools\openapi\verify-openapi-stage3.ps1 -RepoRoot .`
- Optional local smoke with generated certificates:
  - public API on `8080` works without a client certificate;
  - `/server/*` on `8080` is rejected when mTLS/private-port enforcement is enabled;
  - `/server/*` on `9443` without a client certificate is rejected by TLS/auth;
  - `/server/*` on `9443` with a valid client certificate authenticates and reaches request validation.

## Block 2 - Server runtime event idempotency

Goal: make `/server/runtime-events` match the non-idempotent POST rule from the architecture brief and OpenAPI.

- [x] Require `Idempotency-Key`.
- [x] Define whether the key must equal `event_id` or map through `api_idempotency_records`.
- [x] Return deterministic duplicate responses.
- [x] Reject key reuse with different payload.
- [x] Cover the behavior in integration tests and OpenAPI.

Decision: runtime-events use `api_idempotency_records` scoped by `server_id`; `Idempotency-Key` does not need to equal `event_id`.

## Block 3 - Negative/security integration tests

Goal: lock the current security behavior before deeper hardening.

- [x] Cover missing `X-Server-Id`, invalid server identity, expired/revoked identity, wrong realm, wrong server build, wrong match owner, and insufficient scope.
- [x] Cover unsupported catalog version and runtime preset idempotency conflict.
- [x] Cover admin token failures and admin idempotency reuse.

Evidence: `ServerAdminSecurityIntegrationTest`, `MatchProfileBuildIntegrationTest`, `RuntimePresetChangeIdempotencyTest`, and `AdminParityIntegrationTest`.

## Block 4 - mTLS hardening

Goal: prepare server identity management for real operations.

- [x] Add certificate rotation model with multiple active fingerprints and a grace period.
- [x] Add explicit certificate expiry/revocation checks in tests and admin status surfaces.
- [x] Add auth failure metrics for missing certificate, fingerprint mismatch, expired identity, revoked identity, wrong port, and scope denial.
- [x] Add structured logs for missing certificate, fingerprint mismatch, expired identity, revoked identity, wrong port, and scope denial.
- [x] Add rate limiting by `server_id`.

## Block 5 - Production readiness and cleanup

Goal: remove local-only shortcuts and document the production deployment shape.

- [x] Remove the header fingerprint fallback from production profiles and the final API contract.
- [x] Document internal-network/private-port deployment expectations.
- [x] Document ingress/proxy mTLS termination rules if used.
- [x] Document keystore/truststore secret management requirements such as Vault/KMS.
- [x] Finalize OpenAPI after deprecated fallback removal.

## Block 6 - Admin write hardening

Goal: make all admin write-actions replay-safe and contract-visible before dashboard write expansion.

- [x] Require `Idempotency-Key` for every `POST /admin/*` operation.
- [x] Cover admin POST idempotency in OpenAPI and the contract matrix.
- [x] Require explicit reason/comment bodies for legacy `/admin/control/*` write-actions.
- [x] Add audit request hash coverage for legacy `/admin/control/*` write-actions.

## Block 7 - Dashboard load control

Goal: keep read-only dashboard polling from adding avoidable DB/Redis pressure to gameplay APIs.

- [x] Serve `/admin/status/overview` from a short-lived snapshot during polling windows.
- [x] Add bounded pagination/limits to heavy dashboard lists where missing.
- [x] Add regression coverage that dashboard polling does not call detail/list queries.

## Block 8 - Outbox retry storm control

Goal: prevent repeated side-effect delivery failures from amplifying downstream incidents.

- [x] Keep outbox polling bounded by batch size and max attempts.
- [x] Move exhausted processing-timeout rows to `dead_letter`.
- [x] Open an in-memory worker circuit breaker after consecutive fully failed batches.
- [x] Cover circuit breaker behavior with an integration regression test.

## Block 9 - Admin least-privilege scopes

Goal: keep admin write permissions separated by operational domain before adding more dashboard actions.

- [x] Route `/admin/catalog/*` through the dedicated `catalog` role instead of the broad `security` fallback.
- [x] Keep production default admin role read-only (`status`) unless `ADMIN_DEFAULT_ROLES` is explicitly configured.
- [x] Cover catalog route role isolation in `AdminAuthenticationFilterTest`.

## Block 10 - Admin write contract drift guard

Goal: keep OpenAPI, documentation, and backend stage gates synchronized for admin write actions.

- [x] Add `X-Admin-Confirm` as a required OpenAPI parameter for every `POST /admin/*` operation.
- [x] Add a contract regression test that fails when a future admin POST omits the confirmation header.

## Block 11 - Admin route role matrix guard

Goal: keep admin permissions fail-closed when dashboard routes expand.

- [x] Remove the broad `security` fallback for unmapped `/admin/*` routes.
- [x] Add a regression matrix for every current admin route and its required role.
- [x] Deny unmapped admin routes even when the caller has all known roles.

## Block 12 - Admin route OpenAPI drift guard

Goal: keep implemented admin controller routes synchronized with the admin OpenAPI contract.

- [x] Extract literal `/admin/*` Spring mapping annotations from backend controllers in a regression test.
- [x] Fail CI when an implemented admin route is missing from `contracts/openapi/admin-api.yaml`.
