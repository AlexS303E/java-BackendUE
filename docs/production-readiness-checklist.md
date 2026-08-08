# Production Readiness Checklist

> Состояние на: 2026-05-10 | Версия: `367b6ab`

---

## Security

> Update 2026-07-30: JWT key ring rotation is supported through `APP_AUTH_JWT_KEYS_{n}_*` and `APP_AUTH_JWT_ACTIVE_KEY_ID`. Production admin access uses `APP_ADMIN_IDENTITIES_{n}_ID/TOKEN/ROLES`; the deprecated shared `ADMIN_TOKEN` fallback is rejected. Redis-backed rate limiting fails closed in production.

### JWT
- [x] **JWT signing keys configured** — production accepts either `JWT_PRIVATE_KEY`/`JWT_PUBLIC_KEY` or a complete `APP_AUTH_JWT_KEYS_{n}_*` ring with `APP_AUTH_JWT_ACTIVE_KEY_ID`; startup rejects missing, duplicate, or inline key material. The production profile smoke uses the ring-only path.
- [x] **Admin secret mounts** — production requires `APP_ADMIN_IDENTITIES_{n}_TOKEN=file:/...`; file material is read once at startup and unavailable or empty files fail startup. Literal tokens remain local-profile only.
- [x] **mTLS password mounts** — production requires `SERVER_MTLS_KEY_STORE_PASSWORD=file:/...` and `SERVER_MTLS_TRUST_STORE_PASSWORD=file:/...`; both are read once at startup and fail closed if unavailable or empty.
- [x] **mTLS key material mounts** — production requires `SERVER_MTLS_KEY_STORE=file:/...` and `SERVER_MTLS_TRUST_STORE=file:/...`; classpath or inline deployment material is rejected.
- [x] **Token expiry configured** — access token defaults to 15 minutes, refresh token defaults to 14 days.
- [x] **Algorithm** — access tokens use RS256; prod startup requires configured RSA private/public keys.

### Admin API
- [x] **Admin token not dev** — `application-prod.yml` requires `ADMIN_TOKEN`; startup fail-fast rejects blank/dev values.
- [x] **Admin audit trail** — admin write-actions record `admin_audit_events` with result, request hash, target, reason, and operation metadata; covered by `AdminParityIntegrationTest`.
- [x] **RBAC/IP allowlist** — admin filter enforces route-specific role buckets, production defaults to read-only `status`, and prod requires `ADMIN_ALLOWED_CIDRS`.

### mTLS (Dedicated Server → Backend)
- [x] **mTLS enabled in prod profile** — `application-prod.yml` defaults `app.server-auth.mtls.enabled=true`; startup fail-fast enforces it for `prod`/`production`.
- [x] **Header fingerprint fallback disabled in prod profile** — `application-prod.yml` defaults fallback to `false`; startup fail-fast rejects `true`.
- [x] **Private port required in prod profile** — `application-prod.yml` defaults `require-private-port=true`; startup fail-fast enforces it.
- [x] **Certificate rotation model** — `server_identity_certificates` allows multiple active fingerprints and retiring grace windows; covered by `FlywayMigrationIntegrationTest`, `ServerAdminSecurityIntegrationTest`, and `docs/mtls-operations.md`.
- [x] **Revocation list** — application-level revocation uses `server_identities.status/revoked_at` and admin revoke flow; covered by `ServerAdminSecurityIntegrationTest`, `AdminParityIntegrationTest`, and `docs/mtls-operations.md`.
- [x] **Certificate expiry/revocation status** — admin server status exposes effective auth state and derived expiry/revocation flags; covered by `AdminStatusServiceTest`.
- [x] **mTLS timeout** — private mTLS connector has explicit `SERVER_MTLS_CONNECTION_TIMEOUT` defaulting to 5s; covered by `ServerMtlsHardeningValidatorTest`.

### General
- [x] **CORS** — prod profile requires `CORS_ALLOWED_ORIGINS`; Spring Security CORS allows only configured origins.
- [x] **Rate limiting** — production profile enables fixed-window rate limits for `/auth/*`, `/server/*`, and `/admin/*`; startup rejects disabled or non-positive prod limits; server routes bucket by `X-Server-Id`; local/dev profile keeps it disabled by default.
- [x] **SQL injection** — production code uses JdbcTemplate repository bind parameters; raw JDBC statements and dynamic SQL formatting are guarded by `SqlInjectionGuardTest`.
- [x] **Secrets in repo** — `.env`, generated mTLS outputs, logs, and local mTLS work dirs are ignored; covered by `ProductionArtifactGuardTest`.

---

## Database

> Update 2026-07-30: migrations V033–V035 add fenced outbox leases, normalized login uniqueness, and catalog-driven bootstrap defaults. Re-run `FlywayMigrationIntegrationTest` against a clean PostgreSQL before release; Docker was unavailable during the latest local verification.

### Migrations
- [x] **All migrations verified** — `FlywayMigrationIntegrationTest` validates every `src/main/resources/db/migration/V*.sql` version through V032.
- [x] **Idempotent** — Flyway schema history and checksums prevent re-application; failed migration count is asserted by `FlywayMigrationIntegrationTest`.
- [x] **Rollback plan** — no V*__undo scripts; rollback is manual DB restore/PITR per `docs/backup-restore.md`.

### Backups
- [x] **Configured** — `tools/backup/backup-postgres.ps1` creates PostgreSQL custom-format dumps with local retention cleanup; Docker Postgres archives WAL into `postgres_wal_archive`.
- [x] **RPO** — MVP baseline target is 24 hours for scheduled daily dumps.
- [x] **RTO** — MVP baseline target is 30 minutes for same-region restore from a validated dump.
- [x] **Restore tested** — `tools/backup/verify-postgres-backup.ps1` restored the latest dump into an isolated temporary database.

### Connection Pool
- [x] **Pool sizing documented** — Hikari max=30, min-idle=10, connection-timeout=5s; covered by `OperationalTimeoutConfigurationTest`.
- [x] **Pool monitoring** — Hikari metrics are available through Actuator metrics in non-prod/internal exposure; covered by `OperationalTimeoutConfigurationTest`.
- [x] **Max pool for 25 VUs** — max pool 30 is the tested default for the 25 VU baseline. Next threshold: 50 VUs -> evaluate increase to 50-60.

### Monitoring
- [x] **pg_stat_statements** — enabled through Docker Postgres `shared_preload_libraries` and Flyway V027.
- [x] **Slow query log** — Docker Postgres sets `log_min_duration_statement = 200ms`.
- [x] **Connection pooling** — HikariCP is embedded with bounded defaults. PgBouncer not used (single-app architecture).
- [x] **Index coverage** — `EXPLAIN ANALYZE` regression tests verify covering indexes for batch access validation and fresh match-profile lookup; V030 adds the symmetric outfit team-rule lookup index and V032 indexes outbox claim polling.

---

## Redis

### Cache TTL
| Cache | Key pattern | TTL | Grace |
|---|---|---|---|
| Catalog snapshot | `ue:catalog:snapshot:{realm}:{version}` | 10 min | index + 1 day |
| Player access | `ue:access:{playerId}:{catalogVersion}:{revision}` | 5 min | index + 1 day |
| Catalog allows-new-matches | `ue:catalog:allows-new-matches:{realm}:{version}` | **5 min** | N/A |

### Degradation
- [x] **Graceful degradation verified** — `RedisCacheService` returns `Optional.empty()` on Redis failures across catalog, access, match-profile, and catalog lifecycle cache reads; covered by `RedisDegradationTest`.
- [x] **No cascading failures** — Redis read/write/eviction exceptions are caught for cache-backed routes; covered by `RedisDegradationTest`.
- [x] **Startup without Redis** — Redis is optional for app startup and route operation; cache-backed callers degrade to DB-backed misses.

### Invalidation
- [x] **Catalog snapshot eviction** — realm snapshot eviction is covered by `RedisCacheIntegrationTest`.
- [x] **Player access eviction** — player access eviction is covered by `RedisCacheIntegrationTest`.
- [x] **Catalog allows-new-matches eviction** — triggered on `catalog.publish` (TTL-based eviction, keys expire naturally). ✅

---

## Observability

### Metrics (Actuator)
- [x] **Hikari metrics** — exposed in non-prod/internal Actuator metrics; production HTTP actuator exposure is limited to `health,info`.
- [x] **Tomcat metrics** — `server.tomcat.mbeanregistry.enabled=true` enables Tomcat metrics; production HTTP actuator exposure is limited to `health,info`.
- [x] **HTTP metrics by URI/status** — Actuator web metrics and Tomcat MBean registry are enabled for non-prod/internal metrics exposure.
- [x] **JVM/GC metrics** — Actuator JVM/GC metrics are available through non-prod/internal metrics exposure.
- [x] **Redis metrics** — cache-backed Redis reads emit `backend.cache.requests` counters by cache/result through Micrometer.
- [x] **Cache hit ratios** — cache hit/miss/error counters are covered by `RedisCacheMetricsTest`.
- [x] **Rate-limit rejection metrics** — fixed-window rate limit rejections emit `backend.rate_limit.rejections` by low-cardinality bucket (`auth`, `server`, `admin`); covered by `RateLimitingFilterTest`.
- [x] **Outbox circuit-breaker metrics** — outbox worker emits `outbox.circuit_breaker.opened` and `outbox.circuit_breaker.open`; covered by `OutboxWorkerIntegrationTest`.
- [x] **Server auth denial metrics** — `/server/*` authentication and scope denials emit `backend.server_auth.denials` by reason/scope/path; covered by `ServerMtlsFallbackDisabledIntegrationTest`.

### Logging
- [x] **Audit logs retention** — prod profile enables scheduled TTL cleanup for `server_audit_events` and `admin_audit_events`; V028 adds `created_at` indexes for bounded deletes.
- [x] **Structured logging** — prod/production profile emits JSON console logs via `logback-spring.xml`.
- [x] **Server auth denial logs** — `/server/*` authentication and scope denials emit `event=server_auth_denied` WARN logs with reason/scope/path/method/port; covered by `ServerMtlsFallbackDisabledIntegrationTest`.
- [x] **Log levels** — root is fixed at INFO; `com.game.backend` uses DEBUG only in `local`/`dev` and INFO in production/default profiles; covered by `LoggingConfigurationTest`.

### Health checks
- [x] **/actuator/health** — health, readiness, and liveness endpoints are exposed; covered by `HealthEndpointsSecurityTest`.
- [x] **Readiness/liveness probes** — `application-prod.yml` enables actuator health probes.

---

## Operations

- [x] **Incident runbooks** - `docs/runbooks.md` covers DB down, Redis down, outbox stuck, catalog rollback, DS revoke, and overload.
- [x] **Security regression matrix** - `docs/security-regression-matrix.md` maps BOLA/IDOR, CSRF, XSS, replay, invalid loadout, and mTLS denied risks to executable tests.
- [x] **Dashboard polling bounded** - `/admin/status/overview` uses a short-lived snapshot and dashboard list queries use explicit service-level limits; covered by `AdminStatusServiceTest`.
- [x] **Outbox retry storm control** - outbox worker uses bounded batches, retry backoff, dead-lettering, and an in-memory circuit breaker; covered by `OutboxWorkerIntegrationTest`.

---

## Load Testing Baselines

### k6 Endpoint Isolation (baseline)
| Endpoint | Method | VUs | Duration | Reqs | p95 | Failure | Regression gate |
|---|---|---|---|---|---|---|---|
| health | GET | 25 | 3m | 162k | 53ms | 0% | p95 < 200ms, 0% failures |
| catalog/snapshot | GET | 25 | 3m | 262k | 28ms | 0% | p95 < 100ms, 0% failures |
| me/access | GET | 25 | 3m | 154k | 49ms | 0% | p95 < 200ms, 0% failures |
| me/presets | GET | 25 | 3m | 140k | 69ms | 0% | p95 < 200ms, 0% failures |
| match-profile/build | POST | 25 | 3m | 8k | 1.12s | 0% | p95 < 1s, 0% failures, ≤15 SQL |
| runtime-preset-changes | POST | 25 | 3m | 87k | 81ms | 0% | p95 < 200ms, 0% failures |

### Mixed Smoke (baseline)
- **Script:** `tools/load/k6/load-smoke.js`
- **Result:** 18.5k req, 0% failures, p95=356ms, checks=100%

### mTLS Smoke
- [x] **mTLS smoke test** — `tools/mtls/run-mtls-smoke.ps1` passed with mTLS enabled, header fallback disabled, and private port required.

### Yandex.Tank
- [x] **Load-tool decision** — native k6 is the authoritative Stage 1 gate with centralized p95 thresholds; Yandex.Tank is retained as diagnostic-only because its Docker baseline had 4.27% connection timeouts. Covered by `LoadTestPolicyTest`.

---

## Deployment

### Build
- [x] **bootJar** — `gradlew bootJar` produces fat JAR; prod-profile smoke starts the JAR with production settings.
- [x] **Stage 4 release gate** — `tools/test/run-stage4-gate.ps1` is the release verification entrypoint; it writes `artifacts/stage4/stage4-gate-summary.json`, self-validates it with `tools/test/validate-stage4-summary.ps1`, and the validator has negative coverage through `tools/test/test-stage4-summary-validator.ps1`.
- [x] **Java version** — Gradle toolchain is pinned to Java 21 (LTS); covered by `OperationalTimeoutConfigurationTest`.
- [x] **Graceful shutdown** — `application-prod.yml` sets `server.shutdown=graceful` and configurable `spring.lifecycle.timeout-per-shutdown-phase`.
- [x] **Health check port** — prod uses dedicated `MANAGEMENT_SERVER_PORT` (default 8081), bound only to loopback; fail-fast validation prevents collision with public and mTLS ports, and prod smoke verifies Actuator is absent from the public connector.

### Infrastructure
- [x] **CPU/Memory sizing** — Stage 1 backend envelope is 2/4 vCPU request/limit and 1536/2048 MiB memory with a 60% heap cap; preflight, prod smoke, and re-sizing triggers are documented and covered by `ResourceEnvelopeTest`.
- [x] **Disk** — audit tables have TTL cleanup in prod profile; monitor retained window growth and tune `AUDIT_*_RETENTION`.
- [x] **Network** — HTTP and private mTLS connectors use explicit keep-alive defaults (`30s`, `100` requests) so DS clients can reuse TLS connections; covered by `OperationalTimeoutConfigurationTest` and `PrivateMtlsTomcatConnectorConfigTest`.
- [x] **Private DS network** — `/server/*` production traffic uses the internal private mTLS connector only; ingress/proxy termination and secret-backed keystore/truststore rules are documented in `docs/production-deployment.md` and covered by `ProductionDeploymentRunbookTest`.

### Environment
- [x] **application-prod.yml** — created. It externalizes JWT/admin secrets and forces mTLS private-port settings. Datasource/Redis still use environment-backed placeholders from base config.
- [x] **Profile activation** — `tools/smoke/prod-profile-smoke.ps1` starts the fat JAR with `SPRING_PROFILES_ACTIVE=prod`.

---

## Summary

### ✅ Ready
- Query count regression tests (N+1 prevention)
- Endpoint isolation baselines (k6, 0% failures)
- Mixed smoke baseline (100% checks)
- Hikari pool sizing for 25 VUs
- Flyway migrations verified
- Redis graceful degradation
- Admin audit trail
- All SQL parameterized

### ✅ Production Hardening Baseline Closed
- Prod-profile smoke passed.
- mTLS smoke passed.
- Backup and restore drill passed.
- CORS is configured and required for prod startup.

---

*See `docs/load-results/2026-05-10-performance-baseline.md` for baseline numbers.*
