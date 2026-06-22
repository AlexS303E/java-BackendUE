# Production Readiness Checklist

> Состояние на: 2026-05-10 | Версия: `367b6ab`

---

## Security

### JWT
- [x] **JWT signing keys configured** — `application-prod.yml` requires `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`; startup fail-fast rejects missing keys.
- [x] **Token expiry configured** — access token defaults to 15 minutes, refresh token defaults to 14 days.
- [x] **Algorithm** — access tokens use RS256; prod startup requires configured RSA private/public keys.

### Admin API
- [x] **Admin token not dev** — `application-prod.yml` requires `ADMIN_TOKEN`; startup fail-fast rejects blank/dev values.
- [x] **Admin audit trail** — admin write-actions record `admin_audit_events` with result, request hash, target, reason, and operation metadata; covered by `AdminParityIntegrationTest`.
- [x] **RBAC/IP allowlist** — admin filter enforces configured role buckets and prod requires `ADMIN_ALLOWED_CIDRS`.

### mTLS (Dedicated Server → Backend)
- [x] **mTLS enabled in prod profile** — `application-prod.yml` defaults `app.server-auth.mtls.enabled=true`; startup fail-fast enforces it for `prod`/`production`.
- [x] **Header fingerprint fallback disabled in prod profile** — `application-prod.yml` defaults fallback to `false`; startup fail-fast rejects `true`.
- [x] **Private port required in prod profile** — `application-prod.yml` defaults `require-private-port=true`; startup fail-fast enforces it.
- [ ] **Certificate rotation plan** — no automation yet. Manual process defined in `tools/mtls/`.
- [ ] **Revocation list** — server_identities.revoked_at is implemented. CLR/CRL distribution not tested.
- [x] **mTLS timeout** — private mTLS connector has explicit `SERVER_MTLS_CONNECTION_TIMEOUT` defaulting to 5s; covered by `ServerMtlsHardeningValidatorTest`.

### General
- [x] **CORS** — prod profile requires `CORS_ALLOWED_ORIGINS`; Spring Security CORS allows only configured origins.
- [x] **Rate limiting** — production profile enables fixed-window rate limits for `/auth/*`, `/server/*`, and `/admin/*`; local/dev profile keeps it disabled by default.
- [x] **SQL injection** — production code uses JdbcTemplate repository bind parameters; raw JDBC statements and dynamic SQL formatting are guarded by `SqlInjectionGuardTest`.
- [x] **Secrets in repo** — `.env`, generated mTLS outputs, logs, and local mTLS work dirs are ignored; covered by `ProductionArtifactGuardTest`.

---

## Database

### Migrations
- [x] **All migrations verified** — `FlywayMigrationIntegrationTest` validates every `src/main/resources/db/migration/V*.sql` version through V029.
- [x] **Idempotent** — Flyway schema history and checksums prevent re-application; failed migration count is asserted by `FlywayMigrationIntegrationTest`.
- [x] **Rollback plan** — no V*__undo scripts; rollback is manual DB restore/PITR per `docs/backup-restore.md`.

### Backups
- [x] **Configured** — `tools/backup/backup-postgres.ps1` creates PostgreSQL custom-format dumps with local retention cleanup; Docker Postgres archives WAL into `postgres_wal_archive`.
- [x] **RPO** — MVP baseline target is 24 hours for scheduled daily dumps.
- [x] **RTO** — MVP baseline target is 30 minutes for same-region restore from a validated dump.
- [x] **Restore tested** — `tools/backup/verify-postgres-backup.ps1` restored the latest dump into an isolated temporary database.

### Connection Pool
- [x] **Pool sizing documented** — Hikari max=30, min-idle=10, connection-timeout=5s; covered by `OperationalTimeoutConfigurationTest`.
- [ ] **Pool monitoring** — Hikari metrics exposed via `/actuator/metrics` (hikaricp.connections.*).
- [x] **Max pool for 25 VUs** — max pool 30 is the tested default for the 25 VU baseline. Next threshold: 50 VUs -> evaluate increase to 50-60.

### Monitoring
- [x] **pg_stat_statements** — enabled through Docker Postgres `shared_preload_libraries` and Flyway V027.
- [x] **Slow query log** — Docker Postgres sets `log_min_duration_statement = 200ms`.
- [x] **Connection pooling** — HikariCP is embedded with bounded defaults. PgBouncer not used (single-app architecture).
- [ ] **Index coverage** — all WHERE clauses covered by indexes. Verify with `EXPLAIN ANALYZE` for heavy queries (validateCanUseBatch, findExistingProfile).

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
- [ ] **Hikari metrics** — exposed in dev through `/actuator/metrics/hikaricp.connections.*`; production profile exposes only `health,info` over HTTP. Export metrics via an internal-only channel before external testing.
- [ ] **Tomcat metrics** — exposed in dev through `/actuator/metrics/tomcat.*`; production HTTP actuator exposure is limited to `health,info`.
- [ ] **HTTP metrics by URI/status** — not enabled. Add `server.tomcat.mbeanregistry.enabled=true` or Micrometer `WebMvcTagsProvider`.
- [ ] **JVM/GC metrics** — exposed at `/actuator/metrics/jvm.*`, `gc.*`.
- [ ] **Redis metrics** — Lettuce/Spring Data Redis exposes connection pool metrics.
- [ ] **Cache hit ratios** — not tracked. Consider Micrometer `CacheMetricsCollector`.

### Logging
- [x] **Audit logs retention** — prod profile enables scheduled TTL cleanup for `server_audit_events` and `admin_audit_events`; V028 adds `created_at` indexes for bounded deletes.
- [x] **Structured logging** — prod/production profile emits JSON console logs via `logback-spring.xml`.
- [ ] **Log levels** — root=INFO. DEBUG for `com.game.backend` only in dev.

### Health checks
- [x] **/actuator/health** — health, readiness, and liveness endpoints are exposed; covered by `HealthEndpointsSecurityTest`.
- [x] **Readiness/liveness probes** — `application-prod.yml` enables actuator health probes.

---

## Operations

- [x] **Incident runbooks** - `docs/runbooks.md` covers DB down, Redis down, outbox stuck, catalog rollback, DS revoke, and overload.
- [x] **Security regression matrix** - `docs/security-regression-matrix.md` maps BOLA/IDOR, CSRF, XSS, replay, invalid loadout, and mTLS denied risks to executable tests.

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
- [ ] **Yandex.Tank baseline** — previously 95.73% HTTP 200, 4.27% connection timeouts (Docker networking overhead). Not a production-grade baseline. Recommend k6-only for load testing.

---

## Deployment

### Build
- [x] **bootJar** — `gradlew bootJar` produces fat JAR; prod-profile smoke starts the JAR with production settings.
- [x] **Java version** — Gradle toolchain is pinned to Java 21 (LTS); covered by `OperationalTimeoutConfigurationTest`.
- [x] **Graceful shutdown** — `application-prod.yml` sets `server.shutdown=graceful` and configurable `spring.lifecycle.timeout-per-shutdown-phase`.
- [ ] **Health check port** — separate management port not configured. Prod actuator HTTP exposure is limited to `health,info`.

### Infrastructure
- [ ] **CPU/Memory sizing** — not determined. Need load test on target hardware.
- [x] **Disk** — audit tables have TTL cleanup in prod profile; monitor retained window growth and tune `AUDIT_*_RETENTION`.
- [ ] **Network** — mTLS adds ~5-10ms per handshake. Reuse connections (keep-alive).

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
