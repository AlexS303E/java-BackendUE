# Security regression matrix

Stage 1 keeps the security regression set explicit so readiness cannot drift
away from the test suite.

| Risk | Coverage | Required evidence |
|---|---|---|
| BOLA/IDOR | `ServerAdminSecurityIntegrationTest` | Rejects server scope, realm, build, and match ownership violations with `403` responses. |
| Server auth negative cases | `ServerAdminSecurityIntegrationTest` | Rejects missing `X-Server-Id`, unknown server identity, wrong fingerprint, revoked/expired identities, wrong realm, wrong server build, wrong match owner, and insufficient scope. |
| CSRF | `OpenApiContractMatrixTest` and `ServerAdminSecurityIntegrationTest` | Admin write actions require `X-Admin-Confirm`; admin endpoints require `X-Admin-Token`. |
| XSS | `OpenApiContractMatrixTest` and `DtoContractValidationTest` | API contracts use JSON request/response DTOs and `ProblemDetails`; no backend-rendered HTML surface is part of Stage 1. |
| Replay | `RuntimePresetChangeIdempotencyTest` and `AdminParityIntegrationTest` | Duplicate idempotency keys replay the original response, and reused keys with different bodies are rejected for server runtime changes and admin write-actions. |
| Invalid loadout | `LoadoutValidationIntegrationTest` | Invalid durable and match-profile loadouts return `422 LOADOUT_VALIDATION_FAILED` and do not produce accepted profiles. |
| mTLS denied | `ServerMtlsFallbackDisabledIntegrationTest` and `ServerMtlsHardeningValidatorTest` | Header fingerprint fallback is denied when disabled, and production hardening rejects unsafe mTLS settings. |

## Stage gate

- New admin write endpoints must update `docs/openapi-contract-test-matrix.md`
  and include `X-Admin-Confirm`.
- New `/server/*` endpoints must have a denied-authentication or denied-scope
  regression test before production rollout.
- Any new browser-rendered admin surface must add explicit XSS and CSRF
  regression coverage before write-actions are enabled.
