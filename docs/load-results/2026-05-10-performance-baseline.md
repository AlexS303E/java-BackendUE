# Performance Baseline

**Дата:** 2026-05-10
**Версия:** `367b6ab` (пакетная загрузка предустановок и проверка соответствия профилей)
**Инструмент:** k6 v2.0.0-rc1 (native, без Docker)
**Backend:** Spring Boot 3.4.1, Java 21, PostgreSQL 16, Redis 7
**Запуск:** bootRun (dev mode)
**Hikari Pool:** max=30, min-idle=10, connection-timeout=5s

---

## 1. Endpoint Isolation (25 VUs, 3 min each)

| Endpoint | Reqs | Failures | avg | p90 | p95 | p99 | max | RPS |
|---|---|---|---|---|---|---|---|---|
| GET /actuator/health | 162 000 | 0% | — | — | 53ms | — | — | 900 |
| GET /catalog/snapshot | 262 000 | 0% | — | — | 28ms | — | — | 1 454 |
| GET /me/access | 154 000 | 0% | 29ms | — | 49ms | — | — | 813 |
| GET /me/presets | 140 000 | 0% | 32ms | — | 69ms | — | — | ~777 |
| POST /server/match-profile/build (old, mixed) | 8 125 | 0% | 557ms | 909ms | 1 120ms | 2 387ms | 4 793ms | 39 |
| POST /server/match-profile/build (cold, post-opt) | **65 800** | **0%** | **68ms** | **94ms** | **135ms** | — | 2.94s | **346** |
| POST /server/match-profile/build (warm, post-opt) | **85 841** | **0%** | **52ms** | **68ms** | **82ms** | — | 304ms | **462** |
| POST /server/runtime-preset-changes | 87 041 | 0% | 51ms | — | 81ms | — | — | 475 |

**Условия:** Все endpoint'ы tested in isolation (1 endpoint per script). Auth-зависимые используют предварительную регистрацию в setup() + JWT bearer token.

---

## 2. Match-Profile Cold vs Warm (25 VUs, 3 min)

Изолированные сценарии для точного измерения двух режимов работы.

| Сценарий | Reqs | Failures | avg | p90 | p95 | p99 | max | RPS |
|---|---|---|---|---|---|---|---|---|
| **Cold build** (новый match_id) | 65 800 | 0% | 68ms | 94ms | **135ms** | — | 2.94s | 346 |
| **Warm reuse** (тот же match_id) | 85 841 | 0% | 52ms | 68ms | **82ms** | — | 304ms | 462 |

### Целевые ориентиры

| Сценарий | Цель | Факт | Статус |
|---|---|---|---|
| Warm p95 | ≤ 120ms | **82ms** | ✅ PASS |
| Cold p95 | ≤ 300–500ms | **135ms** | ✅ PASS (hugely below target) |

### Что изменилось (от baseline 557ms avg / 1.12s p95)

| Оптимизация | Влияние |
|---|---|
| SQL count 18 → 12 | −6 SQL (profile reuse, Redis cache, merge queries, accessRevision join) |
| Sync audit (no REQUIRES_NEW) | −overhead транзакции на success-пути |
| Индексы V017 | heap lookups устранены в validateCanUseBatch |

---

## 4. Mixed Smoke Test (25 VUs, 3 min)

**Сценарий:** 7 endpoint'ов round-robin на 1 iteration + 0.1s sleep
1. POST /auth/login
2. GET /catalog/snapshot
3. GET /me/access
4. GET /me/presets
5. POST /server/match-profile/build
6. POST /server/runtime-preset-changes
7. GET /actuator/health

| Метрика | Значение |
|---|---|
| HTTP requests | 18 519 |
| Failures | 0.00% |
| avg duration | 96.3ms |
| p95 duration | 356.56ms |
| Iterations | 2 642 |
| Checks passed | 100% |

---

## 6. SQL Query Count Regression

| Endpoint | Threshold | Baseline | Status |
|---|---|---|---|
| GET /me/presets | ≤ 8 | ≤ 8 | ✅ PASS |
| POST /server/match-profile/build | ≤ 12 | ≤ 12 | ✅ PASS |

**Прирост:** 15 → 12 SQL (Redis cache −1, merge weapons+modules −1, merge accessRevision −1)

**Прочие оптимизации:**
- Sync audit (no REQUIRES_NEW) для success-пути — убран overhead транзакции
- Индексы V017: `idx_catalog_items_catalog_enabled`, `idx_item_class_rules_lookup`, `idx_item_team_rules_lookup`
- k6 скрипты: cold-build и warm-reuse изолированы для точного измерения

**Механизм:** `DataSourceQueryCounter` — JDK dynamic proxy, оборачивающий DataSource. Нулевая зависимость от внешних библиотек.

**Запуск:**
```bash
cd backend
.\gradlew.bat test --tests *PresetsQueryCountIntegrationTest --tests *MatchProfileQueryCountIntegrationTest
```

---

## 7. Hikari Connection Pool

| Параметр | До (v1 broken) | После (v2 fixed) |
|---|---|---|
| maximum-pool-size | 10 | 30 |
| minimum-idle | 10 | 10 |
| connection-timeout | 30s | 5s |
| Connection exhaustion (25 VUs) | 100% (pending queue) | **0%** |
| match-profile/build reqs | 137 (3 min) | 8 125 (3 min) |
| match-profile/build failures | 62.77% | **0%** |

---

## 8. N+1 SQL Fixes

| Сервис | До | После | Метод |
|---|---|---|---|
| PresetsService | ~31 SQL | **5 queries** | Batch-load: iteration + mapped batch queries |
| MatchProfileService.validation | ~60 SQL (N+1 EXISTS) | **4 batch queries** (IN) | Batch EXISTS + in-memory cache for static rules |
| MatchProfileService.weapons | 2 queries | **1 query** | LEFT JOIN merge (post-baseline) |

### Баги найденные в процессе
- **3 ошибки связывания параметров JDBC** в validateCanUseBatch — превращали `PSQLException` в 403 через `AccessDeniedHandler`
- **k6 скрипты**: `__ENV.K6_VUS` не работал — исправлено на `options.vus`

---

## 9. Configuration Reference

### application.yml (backend/src/main/resources/application.yml)
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 30
      minimum-idle: 10
      connection-timeout: 5000
```

### k6 options
```javascript
export const options = {
  vus: 25,
  duration: "3m",
  thresholds: {
    http_req_failed: ["rate<0.05"],
  },
};
```

### Скрипты
- `tools/load/k6/endpoint-match-profile-only.js` — mixed cold/warm
- `tools/load/k6/endpoint-match-profile-cold-only.js` — **new match_id на каждую iteration** (cold build path)
- `tools/load/k6/endpoint-match-profile-warm-only.js` — **тот же match_id из setup** (profile reuse path)
- `tools/load/k6/endpoint-presets-only.js`
- `tools/load/k6/endpoint-access-only.js`
- `tools/load/k6/endpoint-auth-only.js`
- `tools/load/k6/endpoint-runtime-changes-only.js`
- `tools/load/k6/load-smoke.js`

---

## 10. Точки роста (post-baseline)

| Область | Текущее | Цель | Подход |
|---|---|---|---|
| match-profile/build cold p95 | **135ms** ✅ | ≤ 500ms | **Цель достигнута** |
| match-profile/build warm p95 | **82ms** ✅ | ≤ 120ms | **Цель достигнута** |
| match-profile/build SQL | **12** | ≤ 10 | Cache validateCanUseBatch (Redis access projection), async audit outbox |
| Hikari pool saturation | 30 @ 25 VUs | 30 @ 50 VUs | Оптимизация write-запросов, async audit |
| mTLS | dev only | production | Включить mTLS, отключить header fallback |

---

*Этот документ — отправная точка для сравнения производительности после изменений.*
