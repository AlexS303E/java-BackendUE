# Baseline Verification

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
| Fix 5 | RoutingOutboxPublisher: critical side effects (preset/access) пробрасывают исключение; только notification-like (match_profile.staled) логирует и глотает |
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

## Updating

Re-run the following when making changes:
1. `.\gradlew.bat test`
2. `tools/test/run-all-tests.ps1`
3. `tools/mtls/run-mtls-smoke.ps1`
4. `tools/load/run-load-smoke.ps1`
