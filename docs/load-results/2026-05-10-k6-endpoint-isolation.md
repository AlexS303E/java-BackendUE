# Отчёт нагрузочного тестирования

**Дата:** 2026-05-10
**Инструмент:** k6 v2.0.0-rc1 (native, без Docker)
**Backend:** Spring Boot 3.4.1, Java 21, PostgreSQL 16, Redis 7
**Запуск:** bootRun (dev mode)
**Hikari Pool:** max=30, min-idle=10, connection-timeout=5s

---

## 1. Endpoint Isolation Tests (25 VUs, 3 min each)

| Endpoint | HTTP Reqs | Failures | RPS | avg | p95 | p99 | max |
|---|---|---|---|---|---|---|---|
| **GET /actuator/health** | 162 000 | 0% | 900 | — | 53ms | — | — |
| **GET /catalog/snapshot** | 262 000 | 0% | 1 454 | — | 28ms | — | — |
| **GET /me/access** | 154 000 | 0% | 813 | 29ms | 49ms | — | — |
| **GET /me/presets** (after N+1 fix) | 140 000 | 0% | ~777 | 32ms | 69ms | — | — |
| **POST /auth/login** | — | — | — | — | — | — | — |
| **POST /server/match-profile/build** (v2) | 8 125 | **0%** ✓ | 39 | 557ms | 1 120ms | 2 387ms | 4 793ms |

---

## 2. POST /server/match-profile/build — Детально

### Условия
- **Скрипт:** `endpoint-match-profile-only.js` (исправлен: `options.vus` вместо `__ENV.K6_VUS`)
- **25 VUs**, duration **3 min**
- **Setup:** регистрация 25 аккаунтов + login → JWT, загрузка catalog snapshot
- **Default function:** `POST /server/match-profile/build` с match_id, presets, catalog version
- **Check:** `r.status === 200 || r.status === 422`

### Результаты (v2)

| Метрика | Значение |
|---|---|
| HTTP requests | **8 125** |
| Iterations completed | **8 074** |
| Failure rate | **0.00%** — threshold `rate<0.05` **PASSED** ✓ |
| Checks passed | **100%** (8 074/8 074) |
| Статусы ответов | 100% **200 OK** |
| avg duration | **557 ms** |
| median | **473 ms** |
| p90 | **909 ms** |
| p95 | **1 120 ms** |
| p99 | **2 387 ms** |
| max | **4 793 ms** |
| min | **52 ms** |
| RPS | **39 req/s** |
| Data received | 7.8 MB (37 kB/s) |
| Data sent | 4.6 MB (22 kB/s) |

### Сравнение v1 (broken) vs v2 (fixed)

| Метрика | v1 (broken) | v2 (fixed) |
|---|---|---|
| HTTP requests | 137 | **8 125** |
| Iterations | 86 | **8 074** |
| Failure rate | 62.77% | **0.00%** |
| avg duration | 29.82s | **557ms** |
| Checks passed | 0% | **100%** |
| Причина | `__ENV.K6_VUS` undefined → 0 accounts в setup | `options.vus` + Hikari pool 10→30 |

---

## 3. Найденные и исправленные проблемы

### 3.1 Баг скриптов k6: `__ENV.K6_VUS`
- **Файлы:** `endpoint-match-profile-only.js`, `endpoint-presets-only.js`, `endpoint-access-only.js`, `endpoint-auth-only.js`
- **Симптом:** `setup()` создавал 0 аккаунтов → краш `default()` на `data.accounts[NaN]` → 6.2M итераций с 1 HTTP req
- **Причина:** `K6_VUS` — runtime-опция k6, а не переменная окружения. `__ENV.K6_VUS === undefined`
- **Фикс:** замена на `options.vus` (25 из экспортируемого объекта options)
- **Статус:** исправлено во всех 4 скриптах

### 3.2 Hikari Connection Pool Exhaustion
- **Симптом:** При 25 VUs пул из 10 соединений полностью насыщался → таймауты запросов (30s+)
- **Доказательство:** `hikaricp.connections.active=10`, `hikaricp.connections.pending=11`
- **Фикс:** `maximum-pool-size: 30`, `connection-timeout: 5000ms` в `application.yml:8-13`
- **Результат:** После увеличения пула до 30 — 0% failures, стабильные 39 RPS

### 3.3 N+1 SQL (исправлено ранее)
- **PresetsService:** ~31 SQL-запросов на 1 вызов /me/presets → batch-load (5 queries)
- **MatchProfileService:** ~60 запросов (N+1 presets + 48 EXISTS) → batch-валидация (4 batch-запроса)

---

## 4. Выводы

1. **Корень таймаутов — N+1 SQL** в PresetsService и MatchProfileService, истощавшие Hikari pool
2. После batch-фиксов endpoint'ы `/me/presets`, `/me/access`, `/catalog/snapshot`, `/actuator/health` работают **0% failures** при 25 VUs
3. **Match-profile** — самый тяжёлый endpoint: 557ms avg (vs 29-32ms для GET-запросов), ~39 RPS
   - P95=1.12s приемлемо для write-heavy POST-endpoint'а
   - 0% failures после увеличения Hikari pool до 30
4. **Следующий предел:** Hikari pool=30 — если увеличить нагрузку >30 VUs, потребуется дальнейшее увеличение pool_size или оптимизация запросов

---

## 5. Файлы

- `tools/load/k6/endpoint-match-profile-only.js` — скрипт изоляции match-profile
- `tools/load/k6/results/match-profile-25vu3m-v2.csv` — сырые данные v2
- `backend/src/main/resources/application.yml` — конфиг Hikari pool
- `backend/src/main/java/com/game/backend/matchprofile/application/MatchProfileService.java` — batch-валидация match-profile
- `backend/src/main/java/com/game/backend/presets/application/PresetsService.java` — batch-load presets

---

## 6. Regression Tests against N+1

Для предотвращения регрессии N+1 добавлены query-count integration tests.

### Механизм
- **DataSourceQueryCounter** (`backend/src/test/java/.../DataSourceQueryCounter.java`) — JDK dynamic proxy, оборачивающий `DataSource` и считающий SQL-запросы (prepareStatement + prepareCall + createStatement)
- **QueryCountTestConfig** (`backend/src/test/java/.../QueryCountTestConfig.java`) — `@TestConfiguration` с `BeanPostProcessor`, внедряющий счётчик в primary DataSource
- Нулевая зависимость от внешних библиотек (без P6Spy/datasource-proxy)

### Результаты

| Endpoint | SQL Queries | Threshold | Status |
|---|---|---|---|
| **GET /me/presets** | ≤ 8 | 8 | ✅ PASS |
| **POST /server/match-profile/build** | 18 (current) | 20 | ✅ PASS |

### Запуск
```bash
cd backend
gradlew.bat test --tests *PresetsQueryCountIntegrationTest --tests *MatchProfileQueryCountIntegrationTest
```

### Цель
- `GET /me/presets ≤ 8` — текущий уровень (5 batch + overhead)
- `POST /server/match-profile/build ≤ 15` — целевой уровень (требует оптимизации, сейчас 18)

---

## 7. Mixed Smoke Test (k6, 25 VUs, 3 min)

После endpoint isolation проведён интеграционный mixed прогон через `load-smoke.js`.

### Сценарий (на 1 iteration)
1. `POST /auth/login` — login
2. `GET /catalog/snapshot` — catalog
3. `GET /me/access` — access
4. `GET /me/presets` — presets
5. `PUT /me/presets/*` — save preset
6. `POST /server/match-profile/build` — build profile
7. `POST /server/runtime-preset-changes` — runtime change
8. `sleep(1s)`

### Результаты

| Метрика | Значение |
|---|---|
| HTTP requests | **18 519** |
| HTTP failures | **0.00%** ✓ (`rate<0.05` PASSED) |
| Iterations | 2 642 |
| avg http_req_duration | **96.3ms** |
| p95 http_req_duration | **356.56ms** |
| avg iteration_duration | 1.7s (includes 1s sleep) |
| Read endpoints | ✅ all 100% |
| Save preset | ✅ 100% |
| Match profile | **❗ check bug** (match_id not in response) |
| Runtime change | ✅ 100% |

### Анализ
- **Все read endpoints** (catalog, access, presets): p95 << 200ms ✅
- **match-profile/build**: возвращает 200 OK, но check падает из-за отсутствия `match_id` в ответе (bug скрипта, исправлен)
- `http_req_failed=0.00%` при 25 VUs — Hikari pool=30 справляется со смешанной нагрузкой

---

## 8. Оптимизация match-profile/build — Анализ

### Текущие SQL-запросы (18 observed, 15 business)

| # | Транзакция | Запрос | Можно кэшировать? |
|---|---|---|---|
| 1 | REQUIRES_NEW (match assign) | INSERT INTO server_matches … ON CONFLICT | Нет (write) |
| 2 | REQUIRES_NEW (match assign) | SELECT FROM server_matches | Нет (freshness) |
| 3 | outer @Transactional | SELECT EXISTS FROM catalog_deployments | **✅ Redis** (редко меняется) |
| 4 | outer | SELECT FROM player_weapon_presets | Нет (player-specific) |
| 5 | outer | SELECT FROM player_outfit_presets | Нет (player-specific) |
| 6 | outer | SELECT access_revision | **можно merge** с query #11 |
| 7 | outer | SELECT weapon_slots | Нет (player-specific) |
| 8 | outer | SELECT weapon_config_modules | Нет (player-specific) |
| 9 | outer | SELECT outfit_items | Нет (player-specific) |
| 10 | outer | SELECT FROM class_weapon_slot_rules | **✅ Redis** (static) |
| 11 | outer | Complex join: items + access + class/team rules | **частично Redis** (rules static) |
| 12 | outer | SELECT FROM weapon_mount_allowed_modules | **✅ Redis** (static per catalog) |
| 13 | outer | SELECT FROM clothing_slot_definitions | **✅ Redis** (static) |
| 14 | outer | INSERT INTO player_match_profiles (UPSERT) | Нет (write) |
| 15 | REQUIRES_NEW (audit) | INSERT INTO server_audit_events | Нет (write) |

### Приоритетные оптимизации

**1. Profile reuse by dependency key (🔥 highest impact)**
- Если `player_match_profiles` уже содержит fresh profile для тех же dependency revisions → вернуть его, **скипнуть весь build flow**
- Уникальный ключ уже есть в `ON CONFLICT` — остаётся только `SELECT` перед build
- **Экономия: ~12 queries** (18 → ~5)
- **Сложность: средняя** (read-before-write check)

**2. Redis caching статических данных (⚡ medium impact)**
- `catalogVersionAllowsNewMatches` (query #3)
- `class_weapon_slot_rules` (query #10) — per class_tag, rarely changes
- `weapon_mount_allowed_modules` (query #12) — static per catalog_version
- `clothing_slot_definitions` (query #13) — rarely changes
- **Экономия: ~4 queries** (18 → ~14)
- **Сложность: низкая**

**3. Merge access_revision с validateCanUseBatch (📦 low impact)**
- access_revision (query #6) можно получить из того же запроса, что и validateCanUseBatch (query #11), который уже читает `player_item_access`
- **Экономия: 1 query**

**4. Исправить savepoint overhead от REQUIRES_NEW**
- `ensureAssignedForBuild` и `serverAuditService.record()` используют `@Transactional(REQUIRES_NEW)`
- Каждый REQUIRES_NEW порождает savepoint + createStatement overhead
- **Экономия: ~2-3 queries**
- **Сложность: высокая** (требует async audit или in-memory audit буфер)

### Фактически выполнено

| Оптимизация | SQL saved | Status |
|---|---|---|
| Profile reuse (read-before-write) | ~12 (18 → 6 на hit) | ✅ |
| `INSERT ... RETURNING` (ServerMatchService) | −1 | ✅ |
| In-memory cache for static validation rules | −3 | ✅ |
| Redis cache for `catalogVersionAllowsNewMatches` | −1 | ✅ |
| Merge weapons + modules (LEFT JOIN) | −1 | ✅ |
| **Итого (fresh build path)** | **18 → 13 SQL** | ✅ |

### Дополнительные оптимизации (post-baseline)

| Оптимизация | SQL saved | Status |
|---|---|---|
| Merge accessRevision into weaponPreset (JOIN) | −1 | ✅ |
| Sync audit for success path (remove REQUIRES_NEW) | −0 (latency) | ✅ |
| Indexes: `catalog_items(catalog,enabled,item)`, `item_class_rules_lookup`, `item_team_rules_lookup` | −0 (latency) | ✅ (V017) |
| **Итого (fresh build path)** | **18 → 12 SQL** | ✅ |

### Оставшиеся возможности
1. **Async audit** (outbox) → eliminate REQUIRES_NEW overhead для failure audit (−savepoint cost, currently only for error path)
2. **Cache validateCanUseBatch** через Redis access projection (−1 SQL, heavy query)
3. **Cold vs warm separation** — скрипты `endpoint-match-profile-cold-only.js` / `warm-only.js`
