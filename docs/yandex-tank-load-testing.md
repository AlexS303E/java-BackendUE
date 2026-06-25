# Yandex.Tank diagnostic load testing

> Yandex.Tank is diagnostic only for Stage 1. The authoritative release gate is native k6 as defined in
> `docs/load-test-policy.md`.

This project keeps a self-hosted Yandex.Tank profile for optional RPS-shaping experiments.

## Why this layer exists

The fast smoke tests answer: "does the vertical flow work?"

Yandex.Tank can help answer: "does the backend stay stable under a reproducible RPS profile?"

The first target profile is intentionally conservative:

```text
line(5, 50, 2m) const(50, 2m)
```

After the baseline is stable, increase to:

```text
line(10, 100, 5m) const(100, 5m)
line(25, 250, 10m) const(250, 10m)
```

## Current scenario

The default script generates real accounts and JWTs, then builds Phantom request-style ammo for:

- `GET /catalog/snapshot`
- `POST /auth/login`
- `GET /me/access`
- `GET /me/presets`
- `POST /server/match-profile/build`

The server endpoint is tested through the development header-fingerprint fallback because this load layer is focused on backend throughput.
Real mTLS handshake is covered separately by `tools/mtls/run-mtls-smoke.ps1`.

## Diagnostic rules

The script fails if Yandex.Tank exits with a non-zero code or if the parsed phout error rate is at least 1%.
Passing this script is not release evidence because Docker Desktop networking distorted the recorded baseline.

Inspect:

```text
tools/load/tank/results/phout-summary.csv
tools/load/tank/results/tank.log
tools/load/tank/results/phout.log
```

Initial latency targets from the architecture spec:

| Endpoint | p95 target |
|---|---:|
| `POST /auth/login` | <= 500 ms |
| `GET /catalog/snapshot` | <= 300 ms full body, <= 100 ms cache hit |
| `GET /me/access` | <= 200 ms |
| `GET /me/presets` | <= 200 ms |
| `POST /server/match-profile/build` | <= 300 ms rebuild |

## Recommended workflow

1. Run normal tests:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\test\run-all-tests.ps1
   ```

2. Run real mTLS smoke:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\mtls\run-mtls-smoke.ps1
   ```

3. Run second-level load test:

   ```powershell
   powershell -ExecutionPolicy Bypass -File tools\load\tank\run-yandex-tank.ps1
   ```

4. Increase RPS only after the baseline is stable.

## Important limitations

- The default test creates new players in the configured database.
- Do not point it at a shared/prod database.
- Mutable endpoints such as preset save and runtime preset changes are intentionally excluded from the default loop because Phantom loops ammo entries and repeated optimistic-lock/idempotency requests would become functional-conflict tests instead of load tests.
- For full `/server/*` mTLS load, add a dedicated Pandora/HTTPS profile after the transport smoke remains stable.
