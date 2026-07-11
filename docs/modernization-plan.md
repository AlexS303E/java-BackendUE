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

## Block 31 - Repository API boundary finalization

Goal: make repository independence from API contracts unconditional after removing all known debt.

- [x] Remove the repository-to-API dependency allow-list from the architecture boundary test.
- [x] Fail CI for any `*.api.*` dependency from repository code without exceptions.

## Block 32 - Architecture guard maintenance cleanup

Goal: keep repository-boundary tests automatic instead of hand-maintained per service.

- [x] Remove manual per-application SQL boundary tests now covered by automatic `*/application` discovery.
- [x] Keep the single package-discovery guard as the source of truth for application SQL/query plumbing.

## Block 33 - Repository component placement guard

Goal: keep persistence Spring components constrained to repository packages.

- [x] Scan production Java sources for `@Repository` components.
- [x] Fail CI when a repository component lives outside a `*/repository/*` package.

## Block 34 - Web controller placement guard

Goal: keep HTTP endpoint components constrained to API packages.

- [x] Scan production Java sources for controller and controller-advice stereotypes.
- [x] Fail CI when web controllers or advice live outside a `*/api/*` package.

## Block 35 - Service component placement guard

Goal: keep service components from drifting into API or repository layers.

- [x] Scan production Java sources for `@Service` components.
- [x] Fail CI when service components live in `*/api/*` or `*/repository/*` packages.

## Block 36 - Generic component placement guard

Goal: keep generic Spring components from blurring API and repository layer ownership.

- [x] Scan production Java sources for `@Component` classes.
- [x] Fail CI when generic components live in `*/api/*` or `*/repository/*` packages.

## Block 37 - Repository application independence guard

Goal: keep repositories independent from application-layer records and services.

- [x] Replace `OutboxRepository`'s application `OutboxEvent` return with a repository record.
- [x] Map claimed outbox records to application events in `OutboxWorker`.
- [x] Fail CI when repository code depends on `*.application.*`.

## Block 38 - API DTO transport purity guard

Goal: keep API records as transport contracts instead of leaking service or persistence dependencies into request/response DTOs.

- [x] Discover every `*/api` package automatically in the architecture boundary test.
- [x] Scan API `record` types separately from controllers/configuration.
- [x] Fail CI when API records depend on application, repository, or JDBC types.

## Block 39 - Repository transport/security independence guard

Goal: keep persistence code independent from HTTP, servlet, and Spring Security concerns.

- [x] Discover every `*/repository` package automatically in the architecture boundary test.
- [x] Fail CI when repository code depends on Spring HTTP, Spring Web, Spring Security, or servlet types.

## Block 40 - API transaction/persistence independence guard

Goal: keep API code from owning transactions or persistence mappings.

- [x] Discover every `*/api` package automatically in the architecture boundary test.
- [x] Fail CI when API code depends on Spring transaction or persistence/JPA types.

## Block 41 - Repository transaction boundary guard

Goal: keep transaction ownership in application services instead of repository classes.

- [x] Discover every `*/repository` package automatically in the architecture boundary test.
- [x] Fail CI when repository code depends on Spring transaction or persistence/JPA mapping types.

## Block 42 - Access application API decoupling

Goal: remove API DTO ownership from the access service while preserving the existing `/me/access` response contract.

- [x] Add application-level access snapshot/item records.
- [x] Move `/me/access` API DTO mapping into `AccessController`.
- [x] Store the access cache payload as the application snapshot instead of the API response DTO.

## Block 43 - Auth application API decoupling

Goal: remove auth request/response DTO ownership from `AuthService` while preserving the existing auth HTTP contracts.

- [x] Add application-level registered-player and token-pair records.
- [x] Move auth request/response DTO mapping into `AuthController`.
- [x] Update integration helpers to call the application registration API directly.

## Block 44 - Catalog snapshot application API decoupling

Goal: remove catalog snapshot API DTO ownership from `CatalogService` and catalog cache while preserving `/catalog/snapshot`.

- [x] Add application-level catalog snapshot/item/mount/module records.
- [x] Move `/catalog/snapshot` API DTO mapping into `CatalogController`.
- [x] Store the catalog snapshot cache payload as the application snapshot instead of the API response DTO.

## Block 45 - Catalog lifecycle application API decoupling

Goal: remove catalog lifecycle request/response DTO ownership from `CatalogLifecycleService` while preserving admin publish/rollback contracts.

- [x] Add application-level publish/rollback command records and lifecycle result record.
- [x] Move admin catalog request/result mapping into `AdminCatalogController`.
- [x] Keep idempotency replay payload shape compatible through matching lifecycle result fields.

## Block 46 - Notifications application API decoupling

Goal: remove notification response DTO ownership from `PlayerNotificationService` while preserving player notification endpoints.

- [x] Add application-level notification page/entry/acknowledgement records.
- [x] Move notification response DTO mapping into `NotificationsController`.
- [x] Keep notification read and acknowledgement behavior unchanged.

## Block 47 - Post-match application API decoupling

Goal: remove post-match pending change request/response DTO ownership from `PostMatchPendingChangesService`.

- [x] Add application-level pending change page/entry/resolution records.
- [x] Move post-match pending change response DTO mapping into `PostMatchPendingChangesController`.
- [x] Pass the validated resolution value into the service instead of the API request DTO.

## Block 48 - Runtime events application API decoupling

Goal: remove runtime event request/response DTO ownership from `RuntimeEventsService` while preserving `/server/runtime-events`.

- [x] Add application-level runtime event command/result records.
- [x] Move runtime event request/result mapping into `RuntimeEventsController`.
- [x] Keep idempotency replay payload shape compatible through matching result fields.

## Block 49 - Runtime preset changes application API decoupling

Goal: remove runtime preset change request/response DTO ownership from runtime change application services while preserving `/server/runtime-preset-changes`.

- [x] Add application-level runtime preset change command/result records.
- [x] Move runtime preset request/result mapping into `RuntimePresetChangeController`.
- [x] Update runtime operation recorder/stream/conflict helpers to use the application command.

## Block 50 - Runtime preset payload application decoupling

Goal: remove runtime preset change payload/step DTO ownership from application and post-match services.

- [x] Add application-level runtime preset change payload/step records.
- [x] Map transport payload/step DTOs into application records in `RuntimePresetChangeController`.
- [x] Parse pending post-match runtime payloads into application records.

## Block 51 - Match profile build command decoupling

Goal: remove `BuildMatchProfileRequest` ownership from match-profile and server-match application services.

- [x] Add an application-level match profile build command.
- [x] Map `/server/match-profile/build` transport request into the application command in `MatchProfileController`.
- [x] Update catalog selection, dependency loading, cache lookup, snapshot build, and match assignment to use the application command.

## Block 52 - Match profile snapshot response decoupling

Goal: remove match profile response DTO ownership from application services and Redis cache.

- [x] Add application-level match profile snapshot, weapon, module, outfit, and dependency records.
- [x] Move `/server/match-profile/build` response mapping into `MatchProfileController`.
- [x] Store and read match profile cache payloads as application snapshots.

## Block 53 - Presets read response decoupling

Goal: remove `/me/presets` read response DTO ownership from `PresetsService`.

- [x] Add application-level player presets, weapon preset, outfit preset, slot, module, and outfit item records.
- [x] Return application snapshots from preset read methods.
- [x] Move `/me/presets` response mapping into `PresetsController`.

## Block 54 - Presets save command decoupling

Goal: remove weapon preset save request/response DTO ownership from `PresetsService`.

- [x] Add application-level weapon preset save command/result records.
- [x] Move weapon preset save request mapping into `PresetsController`.
- [x] Move weapon preset save response mapping into `PresetsController`.

## Block 55 - Admin item access update DTO decoupling

Goal: remove admin item access update request/response DTO ownership from application services.

- [x] Add application-level admin item access update command/result records.
- [x] Move direct admin item access request/result mapping into admin API controllers.
- [x] Update item operation and weapon access adapters to call the application command/result API.

## Block 56 - Admin item operation command decoupling

Goal: remove explicit admin item operation request DTO ownership from `AdminItemOperationService`.

- [x] Add an application-level admin item operation command.
- [x] Move item operation request mapping into `AdminItemOperationsController`.
- [x] Keep item operation idempotency replay on application command/result types.

## Block 57 - Admin maintenance DTO decoupling

Goal: remove admin maintenance request/response DTO ownership from `AdminAccessMaintenanceService`.

- [x] Add application-level projection rebuild, cache invalidate, and server identity revoke command/result records.
- [x] Move maintenance request/result mapping into `AdminMaintenanceController`.
- [x] Keep maintenance idempotency replay on application command/result types.

## Block 58 - Admin control command decoupling

Goal: remove admin control request DTO ownership from `AdminControlService`.

- [x] Add application-level admin control reason and weapon access commands.
- [x] Move admin control request mapping into `AdminControlController`.
- [x] Remove remaining non-common API DTO imports from application services.

## Block 59 - Application feature API dependency guard

Goal: prevent feature API DTOs from returning to application services after the decoupling pass.

- [x] Discover application packages automatically in the architecture boundary test.
- [x] Fail CI when application code depends on non-common `*.api` packages.
- [x] Keep `common.api.ApiException` available as the shared exception contract.

## Block 60 - Admin control typed result decoupling

Goal: remove raw response maps from `AdminControlService` while preserving legacy admin control JSON responses.

- [x] Add typed application results for player cache invalidation, server identity revoke, and outbox retry.
- [x] Keep idempotency replay on typed application result classes.
- [x] Map typed results back to legacy response maps in `AdminControlController`.

## Block 61 - Admin status typed service boundary

Goal: remove raw dashboard response maps from the public `AdminStatusService` API while preserving existing admin JSON responses.

- [x] Return typed application status records from overview, list, search, and weapon access methods.
- [x] Map typed status records back to legacy JSON envelopes in `AdminStatusController`.
- [x] Add an architecture guard against top-level application service methods exposing raw response maps.

## Block 62 - Admin status repository row typing

Goal: remove raw dashboard row maps from `AdminRepository` query APIs while preserving existing admin JSON responses.

- [x] Return typed repository row records for dashboard servers, matches, audit events, players, weapon access, and active catalog.
- [x] Move derived dashboard response maps into `AdminStatusService` response adapters.
- [x] Add an architecture guard against repository components exposing raw `Map<String, Object>` row APIs.

## Block 63 - Admin status transport mapping boundary

Goal: move admin status JSON response mapping out of application records and back into the API boundary.

- [x] Replace `AdminOverview` raw response map with typed overview records.
- [x] Remove `asResponse()` transport helpers from admin status application records.
- [x] Map typed admin status records to legacy JSON only inside `AdminStatusController`.

## Block 64 - Outbox handler payload parser boundary

Goal: keep raw outbox JSON payload maps inside the parser instead of leaking them into event handlers.

- [x] Add typed `OutboxPayloadParser` accessors for required player id, string fields, and payload validation.
- [x] Update outbox handlers to use parser accessors instead of local `Map<String, Object>` payloads.
- [x] Add an architecture guard that prevents handlers from calling raw payload parsing directly.

## Block 65 - Typed outbox recording for access invalidation

Goal: stop high-traffic access and match-profile invalidation events from being recorded through generic payload maps.

- [x] Add typed `OutboxService` methods for `player_access.changed` and `match_profile.staled`.
- [x] Update admin access and match-profile invalidation services to use typed outbox recording.
- [x] Add an architecture guard that blocks these typed events from going through generic `outboxService.record(...)` calls.

## Block 66 - Typed outbox recording for preset events

Goal: move preset save and sanitization outbox payload creation behind typed `OutboxService` methods.

- [x] Add typed outbox recorders for weapon preset saves and weapon/outfit preset sanitization.
- [x] Update preset save and loadout sanitization services to use typed outbox recording.
- [x] Extend the typed outbox guard to block preset events from generic `outboxService.record(...)` calls.

## Block 67 - Typed outbox recording for catalog lifecycle

Goal: move catalog publish/rollback outbox payload creation behind a typed `OutboxService` method.

- [x] Add a typed catalog lifecycle outbox recorder for publish and rollback events.
- [x] Update `CatalogLifecycleService` to use typed catalog outbox recording.
- [x] Extend the typed outbox guard to block catalog lifecycle events from generic `outboxService.record(...)` calls.

## Block 68 - Typed outbox recording for admin maintenance

Goal: move admin maintenance outbox payload creation behind typed `OutboxService` methods.

- [x] Add typed outbox recorders for projection rebuild, player cache invalidation, and server identity revoke events.
- [x] Update `AdminAccessMaintenanceService` to use typed maintenance outbox recording.
- [x] Extend the typed outbox guard to block maintenance events from generic `outboxService.record(...)` calls.

## Block 69 - Typed outbox recording for runtime preset changes

Goal: move runtime preset applied/failed outbox payload creation behind typed `OutboxService` methods.

- [x] Add typed outbox recorders for `weapon_preset.runtime_changed` and `weapon_preset.runtime_failed`.
- [x] Update `RuntimePresetChangeService` to use typed runtime preset outbox recording.
- [x] Extend the typed outbox guard to block runtime preset events from generic `outboxService.record(...)` calls.

## Block 70 - Typed outbox recording for post-match changes

Goal: move post-match pending-change resolution outbox payload creation behind typed `OutboxService` methods.

- [x] Add typed outbox recorders for `weapon_preset.post_match_applied` and `post_match_pending_change.resolved`.
- [x] Update `PostMatchPendingChangesService` to use typed post-match outbox recording.
- [x] Extend the typed outbox guard to block post-match events from generic `outboxService.record(...)` calls.

## Block 71 - Typed outbox recording for pending-change conflicts

Goal: move runtime conflict pending-change creation outbox payload creation behind a typed `OutboxService` method.

- [x] Add a typed outbox recorder for `post_match_pending_change.created`.
- [x] Update `RuntimeChangeConflictService` to use typed pending-change creation outbox recording.
- [x] Extend the typed outbox guard to block pending-change creation from generic `outboxService.record(...)` calls.

## Block 72 - Typed outbox recording for runtime events

Goal: move dedicated-server runtime event outbox payload creation behind a typed `OutboxService` method.

- [x] Add a typed outbox recorder for `server_runtime_event.recorded`.
- [x] Update `RuntimeEventsService` to use typed runtime event outbox recording.
- [x] Extend the typed outbox guard to block runtime event recording from generic `outboxService.record(...)` calls.

## Block 73 - Stage 3 public OpenAPI route drift guard

Goal: keep implemented public HTTP routes synchronized with `contracts/openapi/public-api.yaml`.

- [x] Add a contract test that compares implemented `/auth`, `/catalog`, and `/me` controller mappings with public OpenAPI operations.
- [x] Keep operational health routes outside the public API contract guard.
- [x] Run the Stage 3 OpenAPI verifier after adding the guard.

## Block 74 - Stage 3 public OpenAPI auth guard

Goal: keep authenticated public HTTP operations explicit about bearer-token security in OpenAPI.

- [x] Add a contract test that requires `BearerAuth` on all `/me/*` operations and `/auth/logout`.
- [x] Leave anonymous public operations (`/auth/register`, `/auth/login`, `/auth/refresh`, `/catalog/snapshot`) unchanged.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 75 - Stage 3 server OpenAPI security guard

Goal: keep Dedicated Server HTTP operations explicit about private mTLS and server identity requirements in OpenAPI.

- [x] Add a contract test that requires `ServerMutualTls`, `ServerIdentityHeader`, and `X-Server-Id` on every `/server/*` operation.
- [x] Preserve the per-operation security contract instead of relying on implicit documentation.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 76 - Stage 3 admin OpenAPI security guard

Goal: keep admin HTTP operations explicit about `X-Admin-Token` security in OpenAPI.

- [x] Add a contract test that requires global `admin_token_header` security in `admin-api.yaml`.
- [x] Prevent per-operation `/admin/*` security overrides from dropping `admin_token_header`.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 77 - Stage 3 OpenAPI error model guard

Goal: keep documented error responses aligned with the shared `ProblemDetails` error contract.

- [x] Add a contract test that scans 4xx/5xx OpenAPI responses across public, server, and admin contracts.
- [x] Require error responses to reference reusable response components backed by `ProblemDetails`-compatible schemas.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 78 - Stage 3 OpenAPI matrix error-code guard

Goal: keep the documented minimum error-code checklist synchronized with OpenAPI contracts.

- [x] Add a contract test that reads minimum checked error codes from `docs/openapi-contract-test-matrix.md`.
- [x] Require every documented minimum error code to remain represented in public, server, or admin OpenAPI contracts.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 79 - Stage 3 OpenAPI operationId guard

Goal: keep generated-client operation identifiers stable and collision-free across OpenAPI contracts.

- [x] Add a contract test that scans `operationId` values across public, server, and admin OpenAPI contracts.
- [x] Require operation ids to be unique and snake_case.
- [x] Run the Stage 3 OpenAPI verifier and focused contract tests after adding the guard.

## Block 80 - Stage 3 OpenAPI request body guard

Goal: keep write-operation request payload contracts explicit and schema-backed in OpenAPI.

- [x] Add a contract test that requires POST/PUT/PATCH operations with bodies to declare `required: true` request bodies.
- [x] Require request bodies to point at reusable schema components for generated clients.
- [x] Align `POST /auth/logout` with the backend `@RequestBody` requirement.

## Block 81 - Stage 3 OpenAPI success response guard

Goal: keep generated-client success DTO contracts explicit for non-empty responses.

- [x] Add a contract test that scans `2xx` OpenAPI responses across public, server, and admin contracts.
- [x] Require every non-`204` success response to declare an `application/json` body with a reusable schema reference.
- [x] Allow `204` as the explicit no-content success response.

## Block 82 - Stage 3 OpenAPI path parameter guard

Goal: keep URL template variables synchronized with documented path parameters for generated clients.

- [x] Add a contract test that scans templated OpenAPI paths across public, server, and admin contracts.
- [x] Resolve reusable `components/parameters` references and require exact `in: path` parameter names.
- [x] Fail on missing, extra, or renamed path parameters.

## Block 83 - Stage 3 OpenAPI reusable parameter guard

Goal: keep reusable OpenAPI parameter components resolvable and explicit about required semantics.

- [x] Add a contract test that resolves every `#/components/parameters/*` reference used by OpenAPI operations.
- [x] Require reusable parameters to declare `name`, `in`, and `schema`.
- [x] Require path parameters and critical headers (`Idempotency-Key`, `X-Admin-Confirm`, `X-Server-Id`, `If-Match`) to stay required.

## Block 84 - Stage 3 OpenAPI schema reference guard

Goal: keep generated-client schema references resolvable and backed by structured reusable components.

- [x] Add a contract test that resolves every `#/components/schemas/*` reference across OpenAPI contracts.
- [x] Fail on missing schema components before generated clients consume broken refs.
- [x] Require referenced schemas to declare an explicit schema shape (`type`, composition, `enum`, or `const`).

## Block 85 - Stage 3 OpenAPI reusable response guard

Goal: keep reusable OpenAPI response components resolvable and descriptive for clients.

- [x] Add a contract test that resolves every `#/components/responses/*` reference across OpenAPI contracts.
- [x] Require reusable response components to keep a `description`.
- [x] Require JSON response components to point at reusable schema components.

## Block 86 - Stage 3 OpenAPI tag guard

Goal: keep generated-client operation grouping stable and explicit.

- [x] Add a contract test that validates operation tags across public, server, and admin OpenAPI contracts.
- [x] Require each operation to declare exactly one tag.
- [x] Require operation tags to be declared top-level and use snake_case naming.

## Block 87 - Stage 3 OpenAPI summary guard

Goal: keep generated API docs and SDK method metadata usable.

- [x] Add a contract test that validates operation summaries across public, server, and admin OpenAPI contracts.
- [x] Require every OpenAPI operation to keep a concrete non-empty summary.
- [x] Reject placeholder summaries before they reach generated clients.

## Block 88 - Stage 3 OpenAPI relative server guard

Goal: prevent environment-specific hosts from leaking into generated clients.

- [x] Replace localhost server URLs in public, server, and admin OpenAPI contracts with relative `/`.
- [x] Add a contract test that rejects absolute `servers.url` values.
- [x] Keep host selection in deployment/client configuration instead of OpenAPI source contracts.

## Block 89 - Stage 3 OpenAPI metadata guard

Goal: keep OpenAPI document metadata stable for contract tooling and generated clients.

- [x] Add a contract test that validates top-level OpenAPI metadata across public, server, and admin contracts.
- [x] Require `openapi: 3.1.0` and a UE5 backend title.
- [x] Require semver-like `info.version` values.

## Block 90 - Stage 3 OpenAPI security scheme guard

Goal: keep OpenAPI security requirements resolvable for generated clients and documentation tooling.

- [x] Add a contract test that scans every security requirement in public, server, and admin OpenAPI contracts.
- [x] Require each security requirement name to resolve to `components.securitySchemes`.
- [x] Require referenced security schemes to declare an explicit `type`.

## Block 91 - Stage 3 OpenAPI header reference guard

Goal: keep reusable response header contracts resolvable for generated clients.

- [x] Add a contract test that resolves every `#/components/headers/*` reference across OpenAPI contracts.
- [x] Require referenced header components to exist.
- [x] Require reusable header components to declare a `schema`.

## Block 92 - Stage 3 OpenAPI schema required-property guard

Goal: keep schema `required` declarations aligned with actual DTO properties.

- [x] Add a contract test that scans reusable OpenAPI schema components.
- [x] Extract inline and block `required` fields.
- [x] Require every required field to exist in the same schema component `properties` block.

## Block 93 - Stage 3 OpenAPI enum value guard

Goal: keep enum contracts stable for generated clients and backend DTO mapping.

- [x] Add a contract test that scans inline and block-style OpenAPI enum declarations.
- [x] Require enum values to be non-empty and unique.
- [x] Require enum values to use lower snake_case naming.

## Block 94 - Stage 3 OpenAPI array items guard

Goal: keep array schemas precise for generated clients.

- [x] Add a contract test that scans every `type: array` declaration across OpenAPI contracts.
- [x] Require array schemas to declare `items`.
- [x] Fail before generated clients fall back to untyped arrays.

## Block 95 - Stage 3 OpenAPI numeric bounds guard

Goal: keep numeric constraints valid for generated clients and validators.

- [x] Add a contract test that scans OpenAPI `minimum` and `maximum` bounds.
- [x] Require numeric bound values to parse as numbers.
- [x] Require `minimum <= maximum` when both are declared in one schema block.

## Block 96 - Stage 3 OpenAPI map-like object guard

Goal: keep object schemas explicit when they represent arbitrary maps/payloads.

- [x] Add a contract test that scans every `type: object` declaration across OpenAPI contracts.
- [x] Allow regular DTO objects with `properties`.
- [x] Require property-less object schemas to declare `additionalProperties`.

## Block 97 - Stage 3 OpenAPI schema format guard

Goal: keep scalar schema formats stable and type-compatible for generated clients.

- [x] Add a contract test that scans every OpenAPI `format` declaration.
- [x] Allow only known generated-client-safe formats.
- [x] Require string formats and integer formats to match their declared schema `type`.

## Block 98 - Stage 3 OpenAPI string length guard

Goal: keep string validation bounds valid for generated clients and request validators.

- [x] Add a contract test that scans OpenAPI `minLength` and `maxLength` declarations.
- [x] Require string length bounds to be non-negative integers.
- [x] Require `minLength <= maxLength` when both are declared in one schema block.

## Block 99 - Stage 3 OpenAPI array bounds guard

Goal: keep array validation bounds valid for generated clients and request validators.

- [x] Add a contract test that scans OpenAPI `minItems`, `maxItems`, and `uniqueItems` declarations.
- [x] Require array item bounds to be non-negative integers.
- [x] Require `minItems <= maxItems` and boolean `uniqueItems` values.

## Block 100 - Stage 3 OpenAPI object property bounds guard

Goal: keep object/map validation bounds valid for generated clients and request validators.

- [x] Add a contract test that scans OpenAPI `minProperties` and `maxProperties` declarations.
- [x] Require object property bounds to be non-negative integers.
- [x] Require `minProperties <= maxProperties` when both are declared in one schema block.

## Block 101 - Stage 3 OpenAPI scalar sample guard

Goal: keep scalar defaults and examples type-compatible for generated clients and documentation.

- [x] Add a contract test that scans inline OpenAPI `default` and `example` scalar values.
- [x] Validate integer and boolean sample literals against their schema `type`.
- [x] Leave structured named examples to the existing schema-backed request/response contract checks.

## Block 102 - Stage 3 OpenAPI media type guard

Goal: keep HTTP payload media types explicit and stable for generated clients.

- [x] Add a contract test that scans media type keys in OpenAPI `content` blocks.
- [x] Allow JSON success/request payloads via `application/json`.
- [x] Allow error payloads via `application/problem+json` only.

## Block 103 - Stage 3 OpenAPI nullable flag guard

Goal: keep nullable schema flags unambiguous for generated clients.

- [x] Add a contract test that scans OpenAPI `nullable` declarations.
- [x] Require every `nullable` value to be an explicit boolean.
- [x] Fail before non-boolean nullable values reach generated DTOs.

## Block 104 - Stage 3 OpenAPI required flag guard

Goal: keep requestBody and parameter required flags unambiguous for generated clients.

- [x] Add a contract test that scans scalar OpenAPI `required` declarations.
- [x] Require scalar `required` values to be explicit booleans.
- [x] Leave schema `required` arrays and block lists to the existing required-property guard.

## Block 105 - Stage 4 release gate entrypoint

Goal: start Stage 4 by making the release verification path explicit and scriptable.

- [x] Add a Stage 4 gate script with `Fast` and `Release` modes.
- [x] Reuse the existing Gradle plus OpenAPI test runner for the fast gate.
- [x] Keep release-only bootJar, prod smoke, mTLS smoke, and load smoke wired behind explicit Stage 4 switches.

## Block 106 - Stage 4 release gate documentation guard

Goal: make the Stage 4 gate the documented release verification entrypoint.

- [x] Update production deployment release checks to point at `run-stage4-gate.ps1`.
- [x] Update status maintenance instructions to use the Stage 4 fast/release gates.
- [x] Add regression coverage that release docs mention the Stage 4 gate and its required smoke/load checks.
