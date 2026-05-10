# Production Readiness Checklist

> Состояние на: 2026-05-10 | Версия: `367b6ab`

---

## Security

### JWT
- [ ] **JWT secret not dev** — `app.jwt.secret` in application.yml uses dev-only value. Must be externalized to env/ vault in production.
- [ ] **Token expiry configured** — current: 24h. Consider shorter (15-60 min) + refresh token.
- [ ] **Algorithm** — HMAC-SHA256 (HS256). Consider RS256 for multi-service verification.

### Admin API
- [ ] **Admin token not dev** — `app.admin.api-key` in application.yml is dev-only. Must be externalized.
- [ ] **Admin audit trail** — all admin operations logged to `admin_audit_events`. Verified.
- [ ] **IP allowlist** — not implemented. Consider restricting admin API to internal/VPN IPs.

### mTLS (Dedicated Server → Backend)
- [ ] **mTLS enabled** — currently `app.server-auth.mtls.enabled=false` in dev.
- [ ] **Header fingerprint fallback disabled** — currently `app.server-auth.mtls.allow-header-fingerprint-fallback=true` in dev. Must be `false` in prod.
- [ ] **Private port required** — currently `app.server-auth.mtls.require-private-port=false` in dev. Must be `true` in prod.
- [ ] **Certificate rotation plan** — no automation yet. Manual process defined in `tools/mtls/`.
- [ ] **Revocation list** — server_identities.revoked_at is implemented. CLR/CRL distribution not tested.
- [ ] **mTLS timeout** — handshake timeout not explicitly configured.

### General
- [ ] **CORS** — not configured. Prod must restrict origins.
- [ ] **Rate limiting** — not implemented. No protection against brute-force login.
- [ ] **SQL injection** — all queries use parameterized statements. ✅
- [ ] **Secrets in repo** — `.env` in gitignore, `.env.example` committed without secrets. ✅

---

## Database

### Migrations
- [ ] **All migrations verified** — `FlywayMigrationIntegrationTest` validates V1..V7 apply cleanly.
- [ ] **Idempotent** — Flyway checksums prevent re-application. ✅
- [ ] **Rollback plan** — no V*__undo scripts. Manual rollback via DB restore.

### Backups
- [ ] **Configured** — not configured in MVP. Requires `pg_dump` cron or WAL archiving.
- [ ] **RPO** — not defined.
- [ ] **RTO** — not defined.
- [ ] **Restore tested** — not tested.

### Connection Pool
- [ ] **Pool sizing documented** — Hikari max=30, min-idle=10, connection-timeout=5s.
- [ ] **Pool monitoring** — Hikari metrics exposed via `/actuator/metrics` (hikaricp.connections.*).
- [ ] **Max pool for 25 VUs** — 30 sufficient. Next threshold: 50 VUs → evaluate increase to 50-60.

### Monitoring
- [ ] **pg_stat_statements** — not enabled. Required for slow query identification.
- [ ] **Slow query log** — not configured in postgresql.conf. Add `log_min_duration_statement = 200ms`.
- [ ] **Connection pooling** — HikariCP is embedded. PgBouncer not used (single-app architecture).
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
- [ ] **Graceful degradation verified** — `RedisCacheService` returns `Optional.empty()` on any Redis failure → falls back to DB. ✅
- [ ] **No cascading failures** — Redis exceptions are caught at every call site. ✅
- [ ] **Startup without Redis** — backend starts if Redis is down (cache is optional). Verify.

### Invalidation
- [ ] **Catalog snapshot eviction** — triggered on `catalog.publish` and `catalog.rollback`. ✅
- [ ] **Player access eviction** — triggered on grant/revoke operations. ✅
- [x] **Catalog allows-new-matches eviction** — triggered on `catalog.publish` (TTL-based eviction, keys expire naturally). ✅

---

## Observability

### Metrics (Actuator)
- [ ] **Hikari metrics** — exposed at `/actuator/metrics/hikaricp.connections.*`. `active`/`idle`/`pending`/`timeout`.
- [ ] **Tomcat metrics** — exposed at `/actuator/metrics/tomcat.*`. Thread pool, error count.
- [ ] **HTTP metrics by URI/status** — not enabled. Add `server.tomcat.mbeanregistry.enabled=true` or Micrometer `WebMvcTagsProvider`.
- [ ] **JVM/GC metrics** — exposed at `/actuator/metrics/jvm.*`, `gc.*`.
- [ ] **Redis metrics** — Lettuce/Spring Data Redis exposes connection pool metrics.
- [ ] **Cache hit ratios** — not tracked. Consider Micrometer `CacheMetricsCollector`.

### Logging
- [ ] **Audit logs retention** — `server_audit_events` and `admin_audit_events` are unbounded. Need retention policy (TTL-based cleanup or archive).
- [ ] **Structured logging** — not configured. Consider Logback JSON encoder for log aggregation.
- [ ] **Log levels** — root=INFO. DEBUG for `com.game.backend` only in dev.

### Health checks
- [ ] **/actuator/health** — returns UP. Includes DB health (DataSourceHealthIndicator). ✅
- [ ] **Readiness/liveness probes** — not configured. For K8s: add `/actuator/health/readiness`, `/actuator/health/liveness`.

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
- [ ] **mTLS smoke test** — not yet run. Must test with `mTLS enabled` + `header fallback disabled` + `private port`.

### Yandex.Tank
- [ ] **Yandex.Tank baseline** — previously 95.73% HTTP 200, 4.27% connection timeouts (Docker networking overhead). Not a production-grade baseline. Recommend k6-only for load testing.

---

## Deployment

### Build
- [ ] **bootJar** — `gradlew bootJar` produces fat JAR. Tested.
- [ ] **Java version** — 21 (LTS). ✅
- [ ] **Graceful shutdown** — not explicitly configured (`server.shutdown=graceful` recommended).
- [ ] **Health check port** — separate management port not configured.

### Infrastructure
- [ ] **CPU/Memory sizing** — not determined. Need load test on target hardware.
- [ ] **Disk** — audit tables unbounded. Monitor growth.
- [ ] **Network** — mTLS adds ~5-10ms per handshake. Reuse connections (keep-alive).

### Environment
- [ ] **application-prod.yml** — not created. Must externalize:
  - `app.jwt.secret`
  - `app.admin.api-key`
  - `spring.datasource.url/username/password`
  - `spring.data.redis.host/port`
- [ ] **Profile activation** — `SPRING_PROFILES_ACTIVE=prod` or `--spring.profiles.active=prod`.

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

### ❌ Needs work before prod
1. JWT/admin secrets externalization
2. mTLS production configuration
3. `pg_stat_statements` + slow query log
4. Rate limiting
5. Audit log retention policy
6. bootJar + prod profile
7. mTLS smoke test
8. Backup strategy
9. K8s readiness/liveness probes
10. CORS configuration

---

*See `docs/load-results/2026-05-10-performance-baseline.md` for baseline numbers.*
