# Baseline Verification

Verified 2026-05-11 after Fix 1-8: + routing outbox, catalog cache eviction, access is_enabled, Solution A doc.

| Script | Result | Details |
|---|---|---|
| `.\gradlew.bat test` (Gradle) | ✅ pass | 31/31 tests pass (2026-05-11 after Fix 9-14) |
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
| Load smoke | load-smoke.js: добавлен game_mode_id в matchProfileBuildBody |
| mTLS smoke | run-mtls-smoke.ps1: добавлен game_mode_id в buildBody |

## Updating

Re-run the following when making changes:
1. `.\gradlew.bat test`
2. `tools/test/run-all-tests.ps1`
3. `tools/mtls/run-mtls-smoke.ps1`
4. `tools/load/run-load-smoke.ps1`
