# Baseline Verification

Verified 2026-05-10 after match-profile/build hardening (12 SQL, sync audit, V017 indexes).

| Script | Result | Details |
|---|---|---|
| `tools/test/run-all-tests.ps1` | ✅ pass | Gradle tests + OpenAPI stage 3 |
| `tools/mtls/run-mtls-smoke.ps1` | ✅ pass | 6 checks (1 positive + 5 negative) |
| `tools/load/run-load-smoke.ps1 -Vus 25 -Duration 3m` | ✅ pass | 2950 iters, 20650 checks (100%), 0% failures, avg 70ms, p95 315ms |

## Updating

Re-run the three scripts above, then update the date and results in this table.
