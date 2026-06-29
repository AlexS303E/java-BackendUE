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

## Block 13 - Outbox claim queue index

Goal: keep outbox polling from scanning operational tables as retry volume grows.

- [x] Add a partial index for deliverable `pending`/`failed` outbox rows ordered by `next_attempt_at` and `created_at`.
- [x] Cover the index and Flyway migration version in `FlywayMigrationIntegrationTest`.

## Block 14 - Production rate-limit fail-fast

Goal: keep overload protection active and sane in production.

- [x] Reject production startup when rate limiting is disabled.
- [x] Reject non-positive production rate-limit windows and per-route limits.
- [x] Cover unsafe rate-limit settings in `ProductionHardeningValidatorTest`.

## Block 15 - Rate-limit rejection metrics

Goal: make overload protection visible during incidents without high-cardinality labels.

- [x] Emit `backend.rate_limit.rejections` when a request is rejected with `429`.
- [x] Tag rejections by route bucket only: `auth`, `server`, or `admin`.
- [x] Cover rejection metrics in `RateLimitingFilterTest`.

## Block 16 - Outbox circuit-breaker metrics

Goal: make outbox retry storm protection visible during incidents.

- [x] Emit `outbox.circuit_breaker.opened` when the worker opens its breaker.
- [x] Expose `outbox.circuit_breaker.open` as a 0/1 gauge for current breaker state.
- [x] Cover breaker metrics in `OutboxWorkerIntegrationTest`.

## Block 17 - Runbook metric signal guard

Goal: keep operational runbooks tied to the metrics emitted by overload and outbox protections.

- [x] Document outbox lag/status/circuit-breaker metrics in the outbox incident runbook.
- [x] Document rate-limit rejection metrics in the overload incident runbook.
- [x] Cover required runbook metric names in `RunbookCoverageTest`.

## Block 18 - Admin role matrix coverage guard

Goal: keep every implemented admin route assigned to an explicit tested role.

- [x] Store route templates in `AdminAuthenticationFilterTest` role matrix.
- [x] Extract literal `/admin/*` Spring mappings from backend controllers.
- [x] Fail CI when an implemented admin route is missing from the role matrix.

## Block 19 - Server route OpenAPI drift guard

Goal: keep implemented Dedicated Server routes synchronized with the private server OpenAPI contract.

- [x] Reuse the literal Spring mapping extractor for `/server/*` routes.
- [x] Fail CI when an implemented server route is missing from `contracts/openapi/server-api.yaml`.

## Block 20 - Server route scope matrix guard

Goal: keep every implemented Dedicated Server route assigned to an explicit server identity scope.

- [x] Extract server route/scope mapping into a testable matrix.
- [x] Fail CI when an implemented `/server/*` route is missing from the scope matrix.
- [x] Cover unknown server routes as fail-closed.

## Block 21 - Server route filter fail-closed guard

Goal: keep unknown Dedicated Server routes rejected by the server authentication filter before controller dispatch.

- [x] Add an integration regression for an unmapped `/server/*` route with valid server identity headers.
- [x] Assert the route returns `SERVER_ENDPOINT_NOT_CONFIGURED` instead of falling through to an accidental handler.

## Block 22 - Application repository boundary drift guard

Goal: keep future application services from reintroducing direct SQL/query plumbing outside repositories.

- [x] Discover every `*/application` package automatically in the architecture boundary test.
- [x] Apply the SQL/query-plumbing guard to each discovered application package instead of relying only on a hand-maintained list.

## Block 23 - JDBC infrastructure boundary guard

Goal: keep direct `JdbcTemplate` access constrained to repository infrastructure.

- [x] Add a repository-boundary regression that scans production Java sources.
- [x] Fail CI when direct JDBC usage appears outside `*/repository/*` or `common/persistence`.

## Block 24 - JdbcRepository inheritance boundary guard

Goal: keep generic repository query helpers from leaking into service/application classes.

- [x] Scan production Java sources for `JdbcRepository` subclasses.
- [x] Fail CI when a subclass lives outside a `*/repository/*` package.

## Block 25 - API application boundary drift guard

Goal: keep controllers and DTOs behind application services instead of reaching into persistence.

- [x] Discover every `*/api` package automatically in the architecture boundary test.
- [x] Fail CI when API code imports repositories or uses JDBC/query helpers directly.

## Block 26 - Repository API independence guard

Goal: keep persistence code independent from HTTP/API contracts.

- [x] Discover every `*/repository` package automatically in the architecture boundary test.
- [x] Freeze the current repository-to-API DTO debt as an explicit allow-list.
- [x] Fail CI when new repository code depends on `*.api.*` DTOs or controllers.

## Block 27 - Notifications repository API decoupling

Goal: reduce repository-to-API DTO debt with a small, isolated vertical slice.

- [x] Replace notification API DTO returns in `NotificationsRepository` with repository records.
- [x] Move notification payload parsing and API response mapping into `PlayerNotificationService`.
- [x] Remove `NotificationsRepository` from the repository-to-API dependency allow-list.

## Block 28 - Post-match repository API decoupling

Goal: reduce repository-to-API DTO debt in the post-match pending change read path.

- [x] Replace `PostMatchPendingChangeDto` returns in `PostMatchRepository` with a repository summary record.
- [x] Move pending-change payload parsing and API DTO mapping into `PostMatchPendingChangesService`.
- [x] Remove `PostMatchRepository` from the repository-to-API dependency allow-list.

## Block 29 - Catalog repository API decoupling

Goal: reduce repository-to-API DTO debt in the catalog snapshot read path.

- [x] Replace catalog API DTO returns in `CatalogRepository` with repository records.
- [x] Move catalog snapshot API DTO mapping into `CatalogService`.
- [x] Remove `CatalogRepository` from the repository-to-API dependency allow-list.

## Block 30 - Presets repository API decoupling

Goal: remove the last repository-to-API DTO dependency from player presets.

- [x] Replace preset API DTO returns in `PresetsRepository` with repository records.
- [x] Replace preset save request DTO parameters with repository command records.
- [x] Move preset API DTO/command mapping into `PresetsService`.
- [x] Empty the repository-to-API dependency allow-list.
