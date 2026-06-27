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

- Add certificate rotation model with multiple active fingerprints and a grace period.
- Add explicit certificate expiry/revocation checks in tests and admin status surfaces.
- Add auth failure metrics and structured logs for missing certificate, fingerprint mismatch, expired identity, revoked identity, wrong port, and scope denial.
- Add rate limiting by `server_id`.

## Block 5 - Production readiness and cleanup

Goal: remove local-only shortcuts and document the production deployment shape.

- Remove the header fingerprint fallback from production profiles and later from the final API contract.
- Document internal-network/private-port deployment expectations.
- Document ingress/proxy mTLS termination rules if used.
- Move keystore/truststore handling to secret management such as Vault/KMS.
- Finalize OpenAPI after deprecated fallback removal.
