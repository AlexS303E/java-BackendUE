# Baseline Verification

Verified 2026-05-15 after PR-02 ItemAccessPolicy extraction.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after extracting common item access policy |
| targeted Gradle tests | pass | ItemAccessPolicyIntegrationTest, LoadoutValidationIntegrationTest, RuntimePresetChangeIdempotencyTest, MatchProfileBuildIntegrationTest, OutboxWorkerIntegrationTest |

Verified 2026-05-15 after PR-03 LoadoutValidationService extraction.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after moving preset/runtime loadout validation into LoadoutValidationService |
| targeted Gradle tests | pass | LoadoutValidationServiceIntegrationTest, LoadoutValidationIntegrationTest, RuntimePresetChangeIdempotencyTest, ItemAccessPolicyIntegrationTest |

Verified 2026-05-15 after PR-04 MatchProfileService split-1.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after extracting CatalogVersionSelector and MatchProfileCacheService |
| targeted Gradle tests | pass | CatalogVersionSelectorTest, MatchProfileCacheServiceIntegrationTest, MatchProfileBuildIntegrationTest, MatchProfileQueryCountIntegrationTest |

Verified 2026-05-15 after PR-04 MatchProfileService split-2.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after extracting MatchProfileSnapshotBuilder |
| targeted Gradle tests | pass | MatchProfileSnapshotBuilderIntegrationTest, CatalogVersionSelectorTest, MatchProfileCacheServiceIntegrationTest, MatchProfileBuildIntegrationTest, MatchProfileQueryCountIntegrationTest |

Verified 2026-05-15 after PR-04 MatchProfileService split-3.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after extracting MatchProfileDependencyService; MatchProfileService now has no direct JDBC |
| targeted Gradle tests | pass | MatchProfileDependencyServiceIntegrationTest, MatchProfileSnapshotBuilderIntegrationTest, MatchProfileCacheServiceIntegrationTest, MatchProfileBuildIntegrationTest, MatchProfileQueryCountIntegrationTest |

Verified 2026-05-15 after PR-05 Outbox handlers.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after replacing routing if/switch with explicit OutboxEventHandler implementations |
| targeted Gradle tests | pass | OutboxWorkerIntegrationTest, OutboxEventHandlersTest |

Verified 2026-05-15 after PR-06 Runtime changes service split.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after extracting RuntimeOperationRecorder, RuntimeOperationStreamService, and RuntimeChangeConflictService |
| targeted Gradle tests | pass | RuntimeOperationRecorderIntegrationTest, RuntimeOperationStreamServiceIntegrationTest, RuntimeChangeConflictServiceIntegrationTest, WeaponPresetRuntimeChangeApplierIntegrationTest, RuntimePresetChangeIdempotencyTest |

Verified 2026-05-15 after PR-07 DTO/OpenAPI cleanup split-1.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after tightening DTO contract validation for duplicate catalog versions, duplicate weapon slots, duplicate module mounts, and numeric bounds |
| `tools\openapi\verify-openapi-stage3.ps1` | pass | OpenAPI stage 3 verification passed after adding matching min/max/unique array constraints |
| targeted Gradle tests | pass | DtoContractValidationTest |

Verified 2026-05-15 after PR-07 DTO/OpenAPI cleanup completion.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after aligning runtime event, post-match, admin access/control, catalog lifecycle, and OpenAPI request contracts |
| `tools\openapi\verify-openapi-stage3.ps1` | pass | OpenAPI stage 3 verification passed after documenting admin access/control request bodies, enum constraints, and numeric/schema bounds |
| targeted Gradle tests | pass | DtoContractValidationTest |

Verified 2026-05-15 after PR-08 Production hardening baseline.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after adding production profile, prod secret fail-fast validation, and actuator/graceful-shutdown baseline |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully with production profile resources included |
| targeted Gradle tests | pass | ProductionHardeningValidatorTest, ServerMtlsHardeningValidatorTest, ServerMtlsFallbackDisabledIntegrationTest |

Verified 2026-05-15 after PR-08 Production hardening rate limiting.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after adding production-enabled fixed-window rate limiting for auth/server/admin routes |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully with rate limiting code and production profile resources included |
| targeted Gradle tests | pass | RateLimitingFilterTest, ProductionHardeningValidatorTest |
| `tools\openapi\verify-openapi-stage3.ps1` | pass | OpenAPI stage 3 verification unchanged after adding rate limiting filter |

Verified 2026-05-15 after PR-08 Postgres observability hardening.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | pass | Full test suite passed after adding Flyway V027 and Postgres observability settings |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully with V027 migration included |
| targeted Gradle tests | pass | FlywayMigrationIntegrationTest verifies V027, `pg_stat_statements`, `track_io_timing`, and `log_min_duration_statement=200` |
| `docker compose up -d postgres` | pass | Postgres service recreated with `shared_preload_libraries=pg_stat_statements` |

Verified 2026-05-16 after PR-08 Audit retention hardening.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat cleanTest test` (Gradle) | pass | Full test suite passed after adding audit retention service, production properties, and V028 created_at indexes |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully with audit retention service and V028 migration included |
| targeted Gradle tests | pass | AuditRetentionServiceIntegrationTest and FlywayMigrationIntegrationTest pass after adding scheduled audit TTL cleanup and V028 indexes |

Verified 2026-05-16 after PR-08 Production hardening completion.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat cleanTest test` (Gradle) | pass | Full test suite passed after completing production hardening |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully after production hardening completion |
| `tools\openapi\verify-openapi-stage3.ps1` | pass | OpenAPI stage 3 verification unchanged by production hardening |
| targeted Gradle tests | pass | CorsConfigurationSourceTest, ProductionHardeningValidatorTest, AuditRetentionServiceIntegrationTest, FlywayMigrationIntegrationTest |
| `tools\backup\backup-postgres.ps1` | pass | Created PostgreSQL custom-format dump with local retention cleanup |
| `tools\backup\verify-postgres-backup.ps1` | pass | Restored the dump into a temporary verification database and validated Flyway history |
| `tools\smoke\prod-profile-smoke.ps1` | pass | Fat JAR started with `SPRING_PROFILES_ACTIVE=prod`; health/info reachable, metrics blocked, CORS preflight allowed only configured origin |
| `tools\mtls\run-mtls-smoke.ps1` | pass | Real mTLS private connector passed 1 positive and 5 negative checks |

Verified 2026-05-16 after production maturity pass.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat cleanTest test` (Gradle) | pass | Full test suite passed after RS256 JWT, admin RBAC/IP allowlist, WAL/PITR baseline, and structured logging |
| `.\gradlew.bat bootJar` (Gradle) | pass | Fat JAR produced successfully with RS256/admin/logging changes |
| `tools\openapi\verify-openapi-stage3.ps1` | pass | OpenAPI stage 3 verification unchanged |
| targeted Gradle tests | pass | JwtTokenServiceTest, AdminAuthenticationFilterTest, ProductionHardeningValidatorTest, FlywayMigrationIntegrationTest, ServerAdminSecurityIntegrationTest |
| `docker compose config` | pass | Compose config validates Postgres WAL archive settings |
| `docker compose up -d postgres` | pass | Postgres recreated with `archive_mode=on` and `wal_level=replica` |
| `tools\smoke\prod-profile-smoke.ps1` | pass | Prod fat JAR starts with RSA JWT key files, admin CIDR allowlist, JSON logging, CORS, mTLS, and restricted actuator exposure |
| `tools\mtls\run-mtls-smoke.ps1` | pass | Real mTLS private connector still passes 1 positive and 5 negative checks |
| `tools\backup\backup-postgres.ps1` | pass | Created PostgreSQL custom-format dump after WAL/PITR baseline |
| `tools\backup\verify-postgres-backup.ps1` | pass | Restored latest dump into a temporary verification database and validated Flyway history |

Verified 2026-05-11 after Fix 1-8: + routing outbox, catalog cache eviction, access is_enabled, Solution A doc.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | ✅ pass | 31/31 tests pass (2026-05-11 after Fix 5-7) |
| `tools/test/run-all-tests.ps1` | ✅ pass | Gradle tests + OpenAPI stage 3 |
| `tools/mtls/run-mtls-smoke.ps1` | ✅ pass | 6 checks (1 positive + 5 negative) |
| `tools/load/run-load-smoke.ps1 -Vus 25 -Duration 3m` | ✅ pass | 2950 iters, 20650 checks (100%), 0% failures, avg 70ms, p95 315ms |
| `tools/test/run-all-tests.ps1` (Gradle + OpenAPI) | ✅ pass | 2026-05-11 after Fix 1-4 |

## Fixes Applied 2026-05-11

| Fix | Description |
|---|---|
| 9-11 | Из предыдущего сеанса (team rules split, dead code removal, admin ledger event_type partial) |
| 12 | eventType передаётся из AdminControlService.changeWeaponAccess() (request.action()). Добавлен resolvedEventType() helper. AdminItemOperationService передаёт operation.eventType() |
| 13 | Bean Validation: @Min(1) на operationSeq, @Min(0) на weaponPresetSlot/outfitPresetSlot в RuntimePresetChangeRequest и BuildMatchProfileRequest |
| 14 | Security: /actuator/metrics удалён из .permitAll(), mTLS header fallback default=false (yml + Java), дубликат dev-сертификатов очищен |
| V021 | Упрощён: только FK runtime_preset_change_operations.pending_change_id → post_match_pending_changes(change_id) (FK на match_id удалены из-за нарушения тестовыми данными) |
| V021 NOT VALID | Удалён (единственный FK ссылается на NULL-able pending_change_id, NOT VALID не нужен) |
| Bug | MatchProfileService.queryTeamCompliantItems(): params[] размер 1+itemIds.size() вместо 2+itemIds.size() — пропущен teamTag |
| Bug | MatchProfileService.weapons(): LEFT JOIN player_weapon_preset_weapon_config_modules не содержит AND wcm.weapon_id = ws.selected_weapon_id — при нескольких конфигах оружия в одном слоте (AK12/M4 на primary) возвращались модули от старого оружия |
| Bug | MatchProfileService.filterRestrictedItems(): team-restricted weapon добавлял warning + continue, но не удалял weapon из snapshot |
| Bug | MatchProfileService.queryTeamCompliantItems(): NOT EXISTS с team_tag <> ? некорректно для предметов, разрешенных нескольким командам. Заменено на AND (EXISTS 'all' OR EXISTS 'specific' AND team_tag = ? OR NOT EXISTS any rule) |
| Bug | WeaponPresetRuntimeChangeApplier.validateCanUse(): team rules проверялись на runtime preset change с teamTag = "all". Удалены — team restrictions только в MatchProfileService (с gameModeId) |
| V023 | outfit_item_team_rules: отдельная таблица для командных ограничений одежды (вместо item_team_rules). MatchProfileService: queryOutfitTeamCompliantItems() для одежды, filterRestrictedItems() принимает outfitTeamUsableItems отдельно |
| Validation | BuildMatchProfileRequest: weaponPresetSlot/outfitPresetSlot @Min(0)→@Min(1), @Size(max=10) на supportedCatalogVersions |
| Validation | RuntimePresetChangePayload: @Size(max=100) на changes |
| Validation | RuntimePresetChangeRequest: weaponPresetSlot @Min(0)→@Min(1) |
| Validation | WeaponPresetSaveRequest: @Size(max=20) на slots |
| Validation | SaveWeaponSlotRequest: @Size(max=20) на modules |
| Validation | MatchProfileService.chooseCatalogVersion: .distinct() для supportedCatalogVersions (нормализация дублей) |
| Fix 6 | chooseCatalogVersion: добавлена проверка дубликатов supported_catalog_versions → DUPLICATE_CATALOG_VERSIONS error |
| Fix 7 | RuntimePresetChangeStep.op: @Pattern(regexp = "set_weapon|clear_weapon|set_module|clear_module") |
| Fix 5 | RoutingOutboxPublisher: critical side effects (preset/access) пробрасывают исключение; только notification-like (match_profile.staled) логирует и глотает. weapon_preset.*/outfit_preset.* → invalidationService.invalidateForPlayer (mark match profiles stale); player_access.* → evictPlayerAccess + invalidateForPlayer |
| V024 | match_id nullable + FK REFERENCES server_matches(match_id) на runtime_preset_change_operations и post_match_pending_changes. CatalogLifecycleService.createManualCatalogConflict: match_id=null вместо operationId |
| Fix 1 | RuntimePresetChangeService.submit(): `runtimeChangeApplier.apply()` failure caught inside transaction → operation status updated to `rejected`/`failed`, operation stream advanced, controlled response returned. Operation history preserved (no rollback). |
| Fix 2 | OutboxWorker.markFailed(): `attempts >= maxAttempts` → status = `dead_letter` (instead of silent exclusion from poll). |
| V025 | outbox_events CHECK constraint: added `'dead_letter'` to allowed status values. |
| V026 | runtime_preset_change_operations: added `updated_at` column. `updateOperationStatus()` now sets `updated_at` instead of overwriting `created_at`. |
| Load smoke | load-smoke.js: добавлен game_mode_id в matchProfileBuildBody |
| mTLS smoke | run-mtls-smoke.ps1: добавлен game_mode_id в buildBody |

## Compatibility (Transition Mode)

DS authoritative сохраняется. Backend выполняет best-effort validation на основе своей (возможно частичной) БД-версии compatibility. При расхождении:

- DS говорит **можно** → в transition mode **можно**
- DS говорит **нельзя** → **нельзя**

Backend-версия compatibility используется для UI, предварительной проверки, админки и диагностики, но **не является финальным authority** до завершения catalog pipeline.

Backend catalog transition mode **(вариант B)** — backend знает:

- `item_id`, `item_type`, `country_code`, `factory_id`
- display metadata (display_name, и т.д.)
- `mount_id`, `mount_type`, `allowed_module_ids`
- связь weapon → mounts → modules

Backend **не является source of truth** для:

- `damage`, `recoil`, `fire_rate`, `reload_time`, `spread_curve`
- actual hard references
- sockets/meshes/animations (если живут только в UE)

## Known Technical Debt (MVP)

| Issue | Description | Priority |
|---|---|---|
| PlayerBootstrapService hardcoded | Default loadout baked into Java (class.assault, weapon.ak12, team.red/blue jacket). Needs `default_loadout_rules` / `starter_items` / `default_outfit_by_team_class` tables. | Medium |
| match registration coupled with build | `POST /server/match-profile/build` creates match assignment implicitly. Production should separate: `POST /server/matches/register` then `POST /server/match-profile/build`. | Low |
| Production security | `app.admin.token` plain header, JWT_SECRET dev default, dev mTLS certs in repo, /actuator/metrics accessible to any authenticated user, no rate limiting on /auth/* /server/* /admin/*. Needs dedicated security milestone before external testing. | High |

## Critical Side Effects

| Component | Severity | Behavior on failure |
|---|---|---|
| `RedisCacheService.evictPlayerAccess()` / `evictIndexed()` | **best-effort** | Catches `RuntimeException` internally — access keys are revisioned, stale reads are harmless |
| `MatchProfileInvalidationService.invalidateForPlayer()` / `invalidateForPlayerAccessChange()` | **critical** | `JdbcTemplate` propagates exception → outbox event stays unprocessed → retry/dead-letter |
| `RoutingOutboxPublisher` (critical events: preset/access) | **critical** | Parse failure or missing `player_id` → throws `RuntimeException` → retry via outbox worker |
| `RoutingOutboxPublisher` (notification: `match_profile.staled`) | **best-effort** | Caught-and-logged; no downstream action in MVP |

## Updating

Re-run the following when making changes:
1. `tools/test/run-stage4-gate.ps1 -Mode Fast`
2. `tools/test/run-stage4-gate.ps1 -Mode Release -ListSteps`
3. `tools/test/test-stage4-summary-validator.ps1 -RepoRoot .`
4. `tools/test/run-stage4-gate.ps1 -Mode Release -SkipDocker`
5. Validate the generated `artifacts/stage4/stage4-gate-summary.json` with `tools/test/validate-stage4-summary.ps1` and attach it to release evidence, unless the run was an explicit `-NoSummary` local dry run.
6. Record any intentional `-Skip*` release gate switches with `-SkipReason` and the separately executed evidence.

## Stage 4 Closure

Stage 4 is complete. The release gate entrypoint, summary artifact, schema validator, negative validator smoke, status instructions, and readiness checklist are synchronized around `artifacts/stage4/stage4-gate-summary.json`.

## Release evidence update — 2026-08-01

`tools/test/run-stage4-gate.ps1 -Mode Release` completed successfully on revision
`315adab306e21959ff608a24681855930763e82b` with a clean worktree.

| Check | Result |
|---|---|
| Stage 4 release gate | passed, 5 of 5 steps executed |
| Gradle/Flyway integration tests | passed against PostgreSQL and Redis |
| OpenAPI and `bootJar` checks | passed |
| Production-profile and mTLS smokes | passed |
| k6 load smoke | passed: 845 requests, 0% failures, 100% checks |

This is release evidence for the implemented application changes, not confirmation of
external production controls such as managed secrets, OIDC, immutable backups/PITR,
or multi-replica recovery drills.
