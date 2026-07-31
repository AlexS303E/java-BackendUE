# Консолидированный план модернизации

Дата: 2026-07-28

Этот документ объединяет статическое ревью проекта и план из
`java-BackendUE_План_исправлений_по_ревью.md`. Он заменяет список разрозненных
замечаний единым порядком работ. Пункты, подтверждённые запуском, отмечены
как «проверено»; остальные должны быть валидированы отдельным тестом или
архитектурным решением до реализации.

## Исходные результаты ревью (исторические)

## Выполнено в рабочей ветке

- [x] Сделан устойчивым query-plan gate: тест не привязан к единственному допустимому имени индекса или `Index Only Scan`.
- [x] Заголовки `X-Forwarded-For` используются только за доверенным proxy CIDR.
- [x] Rate limit перенесён в Redis с атомарным TTL-счётчиком, метриками allowed/blocked/error и fail-closed политикой в production.
- [x] Некорректный ответ Redis Lua-счётчика (`null`) классифицируется как data-access
  failure и подчиняется той же fail-open/fail-closed политике.
- [x] Local backup/restore scripts validate PostgreSQL and Docker Compose identifiers
  before using them in shell or SQL commands; negative parameter tests cover injection-like input.
- [x] Outbox использует fenced lease с owner/token и отвергает завершение устаревшим worker.
- [x] Запуск требует явный профиль `local` или `prod`; local secret не является базовым default.
- [x] Audit actor больше не берётся из `X-Admin-Id`; production требует независимые admin credentials и роли.
- [x] JWT RS256 проверяет `iss`, `aud`, `kid`, `jti`, `nbf` и `auth_version`, поддерживает key ring/ротацию; login нормализуется до поиска.
- [x] Bootstrap entitlement ledger вставляется set-based SQL.
- [x] Management interface в production по умолчанию связан с loopback; добавлен GitHub Actions gate с PostgreSQL, Redis, Gradle, Flyway, bootJar и OpenAPI.
- [x] CI и локальный Fast gate сканируют отслеживаемые исходники и конфигурацию на
  сигнатуры private key, AWS access key, GitHub и Slack tokens; сканер имеет
  негативную проверку.
- [x] Gradle dependency locking фиксирует полный граф runtime и test-зависимостей;
  обновление зависимостей требует явного запуска с `--write-locks`.
- [x] Gradle verification metadata содержит SHA-256 используемых артефактов, а CI
  запускает test и bootJar с `--dependency-verification=strict`.
- [x] CI создаёт и сохраняет CycloneDX SBOM из полного транзитивного графа
  зависимостей как release-артефакт.
- [x] Dependabot еженедельно создаёт reviewable обновления для Gradle-зависимостей
  и GitHub Actions; обновления по-прежнему проходят обычный CI gate.

Остаётся до production DoD: настроить реальный внешний OIDC либо secret-managed source для admin credentials и JWT key ring, включить off-host immutable backup/PITR, а также выполнить multi-replica, Redis outage и restore drill в CI/стенде.

- Проверено: штатный Gradle-набор не проходит: 214 тестов, 2 ошибки в
  `QueryIndexCoverageIntegrationTest`. Проверка жёстко привязана к конкретному
  PostgreSQL-плану, хотя планировщик выбрал допустимые primary-key индексы.
- Проверено: `tools/openapi/verify-openapi-stage3.ps1` проходит.
- Проверено: `X-Forwarded-For` безусловно влияет и на admin CIDR allowlist, и
  на rate limit.
- Проверено: `X-Admin-Id` передаётся клиентом и записывается в аудит без
  криптографически подтверждённой связи с credential.
- Проверено: outbox не содержит claim owner/token; завершение старой попытки
  может перезаписать состояние события, которое уже подобрал другой worker.
- Проверено: bootstrap игрока выполняет отдельный SQL insert для каждого
  доступного item, а стартовые значения жёстко заданы в Java.

## Приоритеты и релизные границы

| Приоритет | Значение |
|---|---|
| P0 | Блокирует публичное развёртывание: обход авторизации/лимитов, риск потери или некорректной доставки данных, красный release gate. |
| P1 | Обязательно до production release: эксплуатационная надёжность, управляемость секретов и масштабирование. |
| P2 | Развитие игровой платформы; не смешивать с durable player backend. |

## P0 — безопасность, целостность и зелёный gate

### 1. Восстановить детерминированный тестовый gate

Проверить и исправить `QueryIndexCoverageIntegrationTest`: не требовать
единственный текст `EXPLAIN` для микроскопического набора данных и строки,
вставленной в текущей транзакции. PostgreSQL вправе выбрать PK и не обязан
выполнять index-only scan.

Решение: отдельно проверять наличие миграций/индексов, а performance-тест
строить на репрезентативном объёме данных после `ANALYZE`, измеряя бюджет
времени или число буферов. Не утверждать конкретный plan node, если он не
является обязательным свойством запроса.

Готово, когда: `gradlew test`, OpenAPI-проверка и Stage 4 fast gate стабильно
проходят на чистой БД и в CI.

### 2. Ввести доверенную границу для forwarded headers

Не принимать `X-Forwarded-For` от прямого клиента. Ingress обязан удалять
входящие `X-Forwarded-*` и создавать их заново. Backend использует forwarded
данные только если `remoteAddr` принадлежит allowlist доверенных proxy CIDR;
иначе — только `remoteAddr`.

Это изменение должно использоваться единым компонентом для rate limit,
admin allowlist и аудита. Добавить отрицательные тесты на поддельный header.

Готово, когда: заголовок не меняет определённый IP при прямом запросе и не
обходит admin CIDR либо ограничение входа.

### 3. Сделать rate limit глобальным и ограниченным по памяти

Перенести основное ограничение на ingress/API gateway или в Redis. Использовать
атомарный token bucket либо sliding/fixed window с TTL; локальная карта может
остаться только короткой защитой от burst с жёстким лимитом ключей.

Ключи строить только по подтверждённым данным: trusted client IP, нормализованный
login, authenticated player/server/admin identity. Для Redis заранее определить
fail-open/fail-closed политику для каждого класса endpoint.

Готово, когда: лимит одинаков при нескольких replicas, не сбрасывается после
рестарта одной реплики, а метрики показывают allowed/blocked/error.

### 4. Заменить общий admin token подтверждённой identity

Целевой вариант — OIDC/JWT с уникальным `sub`, индивидуальными ролями, коротким
TTL и отзывом. Допустимый переходный вариант — отдельные хешированные токены
в БД, связанные с конкретным admin ID и ролями.

Не принимать `X-Admin-Id` как источник автора. Аудит должен хранить
подтверждённый субъект, роли, endpoint, request ID, источник, результат и
reason/comment.

Готово, когда: два администратора имеют независимые credentials, их роли и
отзыв независимы, а автор аудита не подделывается заголовком.

### 5. Добавить fenced lease для outbox

Расширить `outbox_events`: `processing_owner`, `processing_token`,
`processing_started_at`, `processing_deadline`, `processing_version`. При
claim создавать уникальный token; `markProcessed`, `markFailed` и dead-letter
выполнять условно по event ID, статусу `processing` и token.

Delivery-модель документируется как at-least-once; handlers остаются
идемпотентными. Добавить метрику lost lease и интеграционный тест с задержанным
worker и повторным claim.

Готово, когда: старый worker не может завершить новую попытку, а повторная
доставка не меняет бизнес-результат.

### 6. Сделать запуск production fail-closed

Сохранить local-послабления только в `application-local.yml`, удалить dev
секреты и небезопасные значения из базовой конфигурации. Deployment обязан
явно задавать профиль и обязательные секреты до открытия HTTP-портов.

Нужно отдельно согласовать UX локального запуска: локальная разработка не
должна требовать production secrets, но отсутствие или неизвестность режима
не должно давать возможность случайно развернуть сервис с local defaults.

Готово, когда: production не запускается без профиля, секретов, mTLS и rate
limit; local запускается только с явно указанным `local`.

## P1 — надёжность и эксплуатация

1. **JWT и login.** Перейти на Spring Security/Nimbus; добавить `iss`, `aud`,
   `kid`, `jti`, `nbf`, `auth_version` и ротацию ключей. Для несуществующего
   login выполнять dummy BCrypt check; нормализовать login до поиска и rate
   limit.
2. **Management surface.** Не слушать `0.0.0.0` без сетевой изоляции: internal
   interface/service, NetworkPolicy и отдельная аутентификация scraper.
3. **Bootstrap и каталог.** Заменить по-item ledger insert на set-based SQL или
   global default policy + player overrides. Перенести starter loadouts,
   item/module IDs и outfit rules в версионируемые каталоговые данные.
4. **CI и поставка.** Защитить main-ветку обязательными compile, unit/integration,
   Flyway, architecture, query-count, OpenAPI, bootJar, prod smoke и dependency
   scan. Периодически выполнять SBOM, container scan и restore drill.
5. **Backup и recovery.** Проверить реальный PITR/WAL archive, внешнее immutable
   хранение, возраст backup и archive lag. Цель: RPO 5–15 минут и RTO 15–30
   минут для accounts/ledger/presets.
6. **Нагрузка и артефакты.** Добавить step/open-model, soak, spike, recovery,
   cold-cache, Redis outage, DB-pool exhaustion и multi-replica сценарии. До
   публикации удалять из артефактов JWT, refresh tokens, identifiers, cookies
   и login names; добавить автоматический secret scan.

## P2 — отдельная session/master platform

Durable player backend оставить владельцем accounts, catalog, entitlements,
presets, profile persistence, audit и outbox. Realtime/session orchestration
выделить в отдельный модуль или сервис.

Последовательность: game-server registry → heartbeat/lease → атомарный
allocation → player reservation и join token → match lifecycle → party/lobby
и matchmaking → fleet orchestration. У состояний должны быть versioning,
idempotency, request ID и защита от stale heartbeat.

## Порядок реализации и коммитов

Каждый пункт — отдельный коммит после прохождения релевантных тестов; не
объединять security-изменения с рефакторингом.

1. `Fix query-plan gate determinism` — тесты и, если нужно, миграции индексов.
2. `Harden trusted client IP resolution` — общий resolver, конфигурация и тесты.
3. `Use distributed rate limiting` — Redis/gateway contract, metrics и отказные сценарии.
4. `Add fenced outbox leases` — миграция, worker, repository, concurrency tests.
5. `Introduce verified admin identities` — migration/adapter, role model, audit.
6. `Make deployment configuration fail closed` — profiles, validators, prod smoke.
7. `Modernize JWT and login hardening` — стандартная проверка и anti-enumeration tests.
8. `Optimize player bootstrap and catalog defaults` — set-based SQL и data-driven defaults.
9. `Harden operations and release evidence` — management policy, CI, backup/load/artifact controls.

Для каждого коммита обязательны: актуальные unit/integration тесты, негативные
security-тесты при изменении границы доверия, и запуск `tools/test/run-stage4-gate.ps1 -Mode Fast` после восстановления зелёного gate. Коммиты создаются
локально; push выполняется только по отдельному запросу.

## Production Definition of Done

### Verified release evidence (2026-08-01)

- [x] `tools/test/run-stage4-gate.ps1 -Mode Release` passed on
  `315adab306e21959ff608a24681855930763e82b` with a clean worktree: all 5 steps
  executed, including Gradle/Flyway, OpenAPI, `bootJar`, production-profile, mTLS,
  and k6 load smoke checks (845 requests, 0% failures, 100% checks).
- [x] Flyway migrations V033--V035 and their application integration are covered by
  the integration suite executed by that gate.

- [x] Все P0-пункты в границах приложения завершены, а release gate зелёный.
- [x] Forwarded headers, rate limit и admin identity используют подтверждённые данные.
- [x] Outbox защищён fenced lease и проверен конкурирующими workers.
- [ ] Management endpoints не доступны из публичной сети.
- [ ] JWT rotation, backup restore и multi-replica degradation сценарии проверены.
- [ ] CI блокирует merge при падении migration, contract или security тестов.
- [ ] Нагрузочные артефакты не содержат секретов либо персональных идентификаторов.
